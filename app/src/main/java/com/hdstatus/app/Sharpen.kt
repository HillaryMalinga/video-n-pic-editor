package com.hdstatus.app

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Maps the 0–100 "Sharpness" slider to the filter strength used below. */
fun sharpnessToAmount(sharpness: Int): Float = sharpness / 100f

/**
 * Video sharpening: a GPU unsharp-mask (centre pixel minus its 4 neighbours),
 * applied after scaling so it sharpens at the final output resolution.
 */
@UnstableApi
class SharpenEffect(private val amount: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SharpenShaderProgram(amount, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = amount <= 0f
}

@UnstableApi
private class SharpenShaderProgram(private val amount: Float, useHdr: Boolean) :
    BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var width = 1
    private var height = 1

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / width, 1f / height))
            program.setFloatUniform("uAmount", amount)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uAmount;
            varying vec2 vTexSamplingCoord;
            void main() {
              vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
              vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb
                     + texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb
                     + texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb
                     + texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
              vec3 sharp = c.rgb + uAmount * (4.0 * c.rgb - n);
              gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
            }
        """
    }
}

/** Photo sharpening: same unsharp-mask as the video filter, on the CPU. */
fun sharpenBitmap(src: Bitmap, amount: Float): Bitmap {
    if (amount <= 0f) return src
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val out = px.copyOf()
    val c = 1f + 4f * amount
    for (y in 1 until h - 1) {
        val row = y * w
        for (x in 1 until w - 1) {
            val i = row + x
            val p = px[i]
            val l = px[i - 1]
            val r = px[i + 1]
            val u = px[i - w]
            val d = px[i + w]
            out[i] = (p and -0x1000000) or
                (channel(p, l, r, u, d, 16, c, amount) shl 16) or
                (channel(p, l, r, u, d, 8, c, amount) shl 8) or
                channel(p, l, r, u, d, 0, c, amount)
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

private fun channel(p: Int, l: Int, r: Int, u: Int, d: Int, s: Int, c: Float, k: Float): Int {
    val sum = ((l shr s) and 255) + ((r shr s) and 255) + ((u shr s) and 255) + ((d shr s) and 255)
    val v = c * ((p shr s) and 255) - k * sum
    return when {
        v < 0f -> 0
        v > 255f -> 255
        else -> v.toInt()
    }
}
