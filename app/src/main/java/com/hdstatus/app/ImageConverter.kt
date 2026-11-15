package com.hdstatus.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A crop rectangle in 0..1 coordinates of the upright (EXIF-rotated) image. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
    }
}

object ImageConverter {

    /** Returns upright width/height (EXIF rotation applied). */
    fun probe(context: Context, uri: Uri): Pair<Int, Int> {
        val opts = bounds(context, uri)
        val rot = rotationDegrees(context, uri)
        return if (rot == 90 || rot == 270) opts.outHeight to opts.outWidth else opts.outWidth to opts.outHeight
    }

    /** Upright, downscaled copy for showing on screen. */
    fun loadPreview(context: Context, uri: Uri, maxSide: Int = 1440): Bitmap {
        val b = bounds(context, uri)
        var sample = 1
        while (max(b.outWidth, b.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = decode(context, uri, sample)
        return transform(bmp, rotationDegrees(context, uri), 1f)
    }

    /** Crops, resizes to [ImageSettings.longSide] (never upscales), sharpens, saves high-quality JPEG. */
    suspend fun convert(
        context: Context,
        uri: Uri,
        settings: ImageSettings,
        crop: NRect,
        outDir: File,
    ): File = withContext(Dispatchers.Default) {
        val (dw, dh) = probe(context, uri)
        require(dw > 0 && dh > 0) { "Could not read this image" }

        // Decode at a power-of-two reduction where the cropped area is still >= target size.
        val cropLong = max(crop.width * dw, crop.height * dh)
        var sample = 1
        while (cropLong / (sample * 2) >= settings.longSide) sample *= 2
        val upright = transform(decode(context, uri, sample), rotationDegrees(context, uri), 1f)

        val x = (crop.left * upright.width).roundToInt().coerceIn(0, upright.width - 1)
        val y = (crop.top * upright.height).roundToInt().coerceIn(0, upright.height - 1)
        val w = (crop.width * upright.width).roundToInt().coerceIn(1, upright.width - x)
        // Derive height from width so the chosen ratio (e.g. 1:1) comes out exact.
        val exactRatio = (crop.height * upright.height) / (crop.width * upright.width)
        val h = (w * exactRatio).roundToInt().coerceIn(1, upright.height - y)
        val long = max(w, h)
        val scale = if (long > settings.longSide) settings.longSide.toFloat() / long else 1f
        val m = Matrix().apply { postScale(scale, scale) }
        val cropped = Bitmap.createBitmap(upright, x, y, w, h, m, true)
        if (cropped !== upright) upright.recycle()

        val sharp = sharpenBitmap(cropped, sharpnessToAmount(settings.sharpness))
        if (sharp !== cropped) cropped.recycle()

        val out = File(outDir, "status.jpg")
        out.outputStream().use { sharp.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, it) }
        sharp.recycle()
        out
    }

    private fun bounds(context: Context, uri: Uri) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, this) }
    }

    private fun decode(context: Context, uri: Uri, sample: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Could not decode this image")
    }

    private fun transform(bmp: Bitmap, rotation: Int, scale: Float): Bitmap {
        if (rotation == 0 && scale == 1f) return bmp
        val m = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (t !== bmp) bmp.recycle()
        return t
    }

    private fun rotationDegrees(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    } catch (_: Exception) {
        0
    }
}
