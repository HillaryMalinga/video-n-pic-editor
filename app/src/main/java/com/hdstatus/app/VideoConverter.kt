package com.hdstatus.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

@UnstableApi
object VideoConverter {

    /** Reads display size (rotation applied) and duration. */
    fun probe(context: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return VideoInfo(w, h, dur)
        } finally {
            r.release()
        }
    }

    /** Output size: short side capped at [shortSide], aspect kept, both dimensions even. */
    fun targetSize(info: VideoInfo, shortSide: Int): Pair<Int, Int> {
        val s = min(info.width, info.height)
        val scale = if (s > shortSide) shortSide.toFloat() / s else 1f
        fun even(x: Float) = ((x / 2f).roundToInt() * 2).coerceAtLeast(2)
        return even(info.width * scale) to even(info.height * scale)
    }

    /** Evenly spaced preview frames for the trim bar. */
    fun thumbnails(context: Context, uri: Uri, durationMs: Long, count: Int, height: Int): List<android.graphics.Bitmap> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val info = probe(context, uri)
            val w = if (info.height > 0) (height * info.width / info.height).coerceAtLeast(1) else height
            return (0 until count).mapNotNull { i ->
                val t = (durationMs * (i + 0.5) / count).toLong() * 1000
                r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, height)
            }
        } finally {
            r.release()
        }
    }

    /**
     * Converts the [startMs]..[endMs] range of [uri] into one or more WhatsApp-friendly
     * MP4s (H.264 + AAC), split into [VideoSettings.segmentSeconds]-long parts.
     */
    suspend fun convert(
        context: Context,
        uri: Uri,
        settings: VideoSettings,
        startMs: Long,
        endMs: Long,
        outDir: File,
        onProgress: (Float) -> Unit,
    ): List<File> {
        val info = probe(context, uri)
        val (w, h) = targetSize(info, settings.shortSide)
        val segMs = settings.segmentSeconds * 1000L
        val rangeEnd = if (endMs <= 0 || (info.durationMs > 0 && endMs > info.durationMs)) info.durationMs else endMs
        val length = (rangeEnd - startMs).coerceAtLeast(0)
        val parts = if (length <= 0) 1 else ceil(length / segMs.toDouble()).toInt().coerceAtLeast(1)

        val outputs = mutableListOf<File>()
        for (i in 0 until parts) {
            val start = startMs + i * segMs
            val end = min(start + segMs, rangeEnd)
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(start)
                .apply { if (length > 0 && end < info.durationMs) setEndPositionMs(end) }
                .build()
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()

            val effects = buildList<androidx.media3.common.Effect> {
                add(FrameDropEffect.createDefaultFrameDropEffect(settings.maxFps.toFloat()))
                add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
                if (settings.sharpness > 0) add(SharpenEffect(sharpnessToAmount(settings.sharpness)))
            }
            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(listOf(), effects))
                .build()

            @Suppress("DEPRECATION")
            val composition = Composition.Builder(EditedMediaItemSequence(edited))
                // HDR phone clips look washed out on WhatsApp; convert them to normal SDR.
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()

            val out = File(outDir, if (parts > 1) "status_part${i + 1}.mp4" else "status.mp4")
            exportOne(context, composition, out, settings) { p ->
                onProgress((i + p) / parts)
            }
            outputs += out
        }
        onProgress(1f)
        return outputs
    }

    private suspend fun exportOne(
        context: Context,
        composition: Composition,
        out: File,
        settings: VideoSettings,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val bitrate = (settings.bitrateMbps * 1_000_000).toInt()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(bitrate).build()
                )
                .setEnableFallback(true)
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            out.delete()
            transformer.start(composition, out.absolutePath)

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    handler.postDelayed(this, 250)
                }
            }
            handler.post(poll)

            cont.invokeOnCancellation {
                handler.post { transformer.cancel() }
            }
        }
    }
}
