package com.hdstatus.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Picked {
    val uri: Uri
    data class Video(override val uri: Uri, val info: VideoInfo) : Picked
    data class Image(override val uri: Uri, val width: Int, val height: Int, val preview: Bitmap) : Picked
}

/** Crop shapes for photos. `ratio` is width / height; null means free or original. */
enum class AspectOption(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    R9_16("9:16", 9f / 16f),
    R4_5("4:5", 4f / 5f),
    R1_1("1:1", 1f),
    R16_9("16:9", 16f / 9f),
    FREE("Free", null),
}

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var videoSettings by mutableStateOf(store.loadVideo()); private set
    var imageSettings by mutableStateOf(store.loadImage()); private set

    var picked by mutableStateOf<Picked?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var outputs by mutableStateOf<List<File>>(emptyList()); private set
    var message by mutableStateOf<String?>(null); private set

    // Photo crop
    var aspect by mutableStateOf(AspectOption.ORIGINAL); private set
    var crop by mutableStateOf(NRect.FULL)

    // Video trim
    var trimStartMs by mutableLongStateOf(0L); private set
    var trimEndMs by mutableLongStateOf(0L); private set
    var thumbnails by mutableStateOf<List<Bitmap>>(emptyList()); private set

    private var job: Job? = null
    private val outDir get() = File(getApplication<Application>().cacheDir, "out").apply { mkdirs() }

    fun updateVideo(v: VideoSettings) { videoSettings = v; store.save(videoSettings, imageSettings) }
    fun updateImage(i: ImageSettings) { imageSettings = i; store.save(videoSettings, imageSettings) }
    fun resetSettings() { updateVideo(VideoSettings()); updateImage(ImageSettings()) }
    fun clearMessage() { message = null }

    fun pick(uri: Uri, mimeHint: String? = null) {
        val ctx = getApplication<Application>()
        job?.cancel()
        outputs = emptyList()
        message = null
        loading = true
        viewModelScope.launch {
            try {
                val mime = ctx.contentResolver.getType(uri) ?: mimeHint ?: ""
                val p = withContext(Dispatchers.IO) {
                    if (mime.startsWith("video")) {
                        Picked.Video(uri, VideoConverter.probe(ctx, uri))
                    } else {
                        val (w, h) = ImageConverter.probe(ctx, uri)
                        Picked.Image(uri, w, h, ImageConverter.loadPreview(ctx, uri))
                    }
                }
                picked = p
                aspect = AspectOption.ORIGINAL
                crop = NRect.FULL
                thumbnails = emptyList()
                if (p is Picked.Video) {
                    trimStartMs = 0
                    trimEndMs = p.info.durationMs
                    thumbnails = withContext(Dispatchers.IO) {
                        runCatching { VideoConverter.thumbnails(ctx, uri, p.info.durationMs, 10, 120) }.getOrDefault(emptyList())
                    }
                }
            } catch (e: Exception) {
                picked = null
                message = "Couldn't open that file: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun selectAspect(option: AspectOption) {
        val p = picked as? Picked.Image ?: return
        aspect = option
        val imgRatio = p.width.toFloat() / p.height
        crop = when (option) {
            AspectOption.ORIGINAL, AspectOption.FREE -> NRect.FULL
            else -> centeredCrop(option.ratio!!, imgRatio)
        }
    }

    /** Crop ratio to enforce while dragging, as width / height in pixels (null = free). */
    fun lockedRatio(): Float? {
        val p = picked as? Picked.Image ?: return null
        return when (aspect) {
            AspectOption.FREE -> null
            AspectOption.ORIGINAL -> p.width.toFloat() / p.height
            else -> aspect.ratio
        }
    }

    fun setTrim(startMs: Long, endMs: Long) {
        trimStartMs = startMs
        trimEndMs = endMs
    }

    fun convert() {
        val p = picked ?: return
        val ctx = getApplication<Application>()
        job?.cancel()
        job = viewModelScope.launch {
            busy = true
            progress = 0f
            outputs = emptyList()
            message = null
            try {
                outDir.listFiles()?.forEach { it.delete() }
                outputs = when (p) {
                    is Picked.Video -> VideoConverter.convert(
                        ctx, p.uri, videoSettings, trimStartMs, trimEndMs, outDir,
                    ) { progress = it }
                    is Picked.Image -> listOf(ImageConverter.convert(ctx, p.uri, imageSettings, crop, outDir))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "Cancelled"
                throw e
            } catch (e: Exception) {
                message = "Conversion failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun cancel() { job?.cancel() }

    val outputMime: String get() = if (picked is Picked.Video) "video/mp4" else "image/jpeg"
    val outputIsVideo: Boolean get() = picked is Picked.Video

    fun saveToGallery() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { Sharing.saveToGallery(ctx, outputs, outputIsVideo) }
                "Saved to gallery"
            } catch (e: Exception) {
                "Save failed: ${e.message}"
            }
        }
    }
}

/** Largest centred crop of pixel ratio [ratio] inside an image of pixel ratio [imgRatio]. */
fun centeredCrop(ratio: Float, imgRatio: Float): NRect {
    return if (ratio > imgRatio) {
        val h = imgRatio / ratio
        NRect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    } else {
        val w = ratio / imgRatio
        NRect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
    }
}
