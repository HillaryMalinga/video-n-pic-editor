package com.hdstatus.app

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MIN_CLIP_MS = 500L

@OptIn(UnstableApi::class)
@Composable
fun VideoTrimmer(
    uri: Uri,
    info: VideoInfo,
    thumbnails: List<Bitmap>,
    startMs: Long,
    endMs: Long,
    onTrimChange: (Long, Long) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    var position by remember(uri) { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    // Follow playback; stop at the trim end and jump back to the start.
    LaunchedEffect(player) {
        while (true) {
            if (player.isPlaying) {
                val p = player.currentPosition
                if (p >= end) {
                    player.pause()
                    player.seekTo(start)
                    position = start
                } else {
                    position = p
                }
            }
            playing = player.isPlaying
            delay(30)
        }
    }

    fun seek(ms: Long) {
        position = ms
        player.seekTo(ms)
    }

    val ratio = if (info.height > 0) info.width.toFloat() / info.height else 9f / 16f
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.player = player },
                modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledIconButton(onClick = {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (position < start || position >= end - 50) seek(start)
                    player.play()
                }
            }) {
                if (playing) PauseIcon() else Icon(Icons.Default.PlayArrow, "Play")
            }
            Text(
                "${fmtTime(position)}  /  ${fmtTime(info.durationMs)}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            )
        }

        TrimBar(
            thumbnails = thumbnails,
            durationMs = info.durationMs,
            startMs = startMs,
            endMs = endMs,
            positionMs = position,
            onStart = { s -> player.pause(); onTrimChange(s, end); seek(s) },
            onEnd = { e -> player.pause(); onTrimChange(start, e); seek(e) },
            onSeek = { p -> player.pause(); seek(p) },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledTime("Start", startMs)
            LabeledTime("Length", endMs - startMs, center = true)
            LabeledTime("End", endMs, end = true)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { if (position < end - MIN_CLIP_MS) onTrimChange(position, end) },
                modifier = Modifier.weight(1f),
            ) { Text("Start here") }
            OutlinedButton(
                onClick = { if (position > start + MIN_CLIP_MS) onTrimChange(start, position) },
                modifier = Modifier.weight(1f),
            ) { Text("End here") }
            OutlinedButton(
                onClick = { onTrimChange(0, info.durationMs) },
                modifier = Modifier.weight(1f),
            ) { Text("Reset") }
        }
    }
}

@Composable
private fun PauseIcon() {
    val c = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.height(18.dp).aspectRatio(1f)) {
        val bw = size.width * 0.3f
        drawRect(c, Offset(size.width * 0.12f, 0f), Size(bw, size.height))
        drawRect(c, Offset(size.width * 0.58f, 0f), Size(bw, size.height))
    }
}

@Composable
private fun LabeledTime(label: String, ms: Long, center: Boolean = false, end: Boolean = false) {
    Column(horizontalAlignment = when { center -> Alignment.CenterHorizontally; end -> Alignment.End; else -> Alignment.Start }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmtTime(ms), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/**
 * Filmstrip with two trim handles and a playhead. Drag a handle to set start/end,
 * drag or tap anywhere else to move the playhead.
 */
@Composable
private fun TrimBar(
    thumbnails: List<Bitmap>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val images = remember(thumbnails) { thumbnails.map { it.asImageBitmap() } }
    val primary = MaterialTheme.colorScheme.primary
    val s by rememberUpdatedState(startMs)
    val e by rememberUpdatedState(endMs)
    val onStartCb by rememberUpdatedState(onStart)
    val onEndCb by rememberUpdatedState(onEnd)
    val onSeekCb by rememberUpdatedState(onSeek)
    val dur = durationMs.coerceAtLeast(1)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            // Stop Android's edge "back" swipe from stealing handle drags.
            .systemGestureExclusion()
            .pointerInput(dur) {
                detectTapGestures { pos -> onSeekCb(xToMs(pos.x, size.width.toFloat(), 14.dp.toPx(), dur).coerceIn(s, e)) }
            }
            .pointerInput(dur) {
                val pad = 14.dp.toPx()
                val grab = 28.dp.toPx()
                var target = 0 // 1 start, 2 end, 3 playhead
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat()
                        val sx = msToX(s, w, pad, dur)
                        val ex = msToX(e, w, pad, dur)
                        val ds = abs(pos.x - sx)
                        val de = abs(pos.x - ex)
                        target = when {
                            ds <= grab && ds <= de -> 1
                            de <= grab -> 2
                            else -> 3
                        }
                        if (target == 3) onSeekCb(xToMs(pos.x, w, pad, dur).coerceIn(s, e))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val ms = xToMs(change.position.x, size.width.toFloat(), pad, dur)
                        when (target) {
                            1 -> onStartCb(ms.coerceIn(0, e - MIN_CLIP_MS))
                            2 -> onEndCb(ms.coerceIn(s + MIN_CLIP_MS, dur))
                            else -> onSeekCb(ms.coerceIn(s, e))
                        }
                    },
                )
            },
    ) {
        val pad = 14.dp.toPx()
        val w = size.width - 2 * pad
        val top = 6.dp.toPx()
        val h = size.height - 2 * top

        // Filmstrip
        if (images.isNotEmpty()) {
            val slot = w / images.size
            images.forEachIndexed { i, img ->
                // Centre-crop each frame to the slot shape instead of stretching it.
                val slotRatio = slot / h
                val imgRatio = img.width.toFloat() / img.height
                val (sw, sh) = if (imgRatio > slotRatio) (img.height * slotRatio).roundToInt() to img.height
                else img.width to (img.width / slotRatio).roundToInt()
                drawImage(
                    img,
                    srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2),
                    srcSize = IntSize(sw.coerceAtLeast(1), sh.coerceAtLeast(1)),
                    dstOffset = IntOffset((pad + i * slot).roundToInt(), top.roundToInt()),
                    dstSize = IntSize(slot.roundToInt() + 1, h.roundToInt()),
                )
            }
        } else {
            drawRect(Color.DarkGray, Offset(pad, top), Size(w, h))
        }

        val sx = msToX(startMs, size.width, pad, dur)
        val ex = msToX(endMs, size.width, pad, dur)
        // Dim the parts that will be cut off.
        val shade = Color.Black.copy(alpha = 0.6f)
        drawRect(shade, Offset(pad, top), Size(sx - pad, h))
        drawRect(shade, Offset(ex, top), Size(pad + w - ex, h))
        // Selection frame + handles.
        val bw = 3.dp.toPx()
        drawRect(primary, Offset(sx, top - bw), Size(ex - sx, bw))
        drawRect(primary, Offset(sx, top + h), Size(ex - sx, bw))
        val hw = 12.dp.toPx()
        drawRoundRect(primary, Offset(sx - hw, top - bw), Size(hw, h + 2 * bw), CornerRadius(4.dp.toPx()))
        drawRoundRect(primary, Offset(ex, top - bw), Size(hw, h + 2 * bw), CornerRadius(4.dp.toPx()))
        val grip = Color.White
        drawLine(grip, Offset(sx - hw / 2, top + h * 0.35f), Offset(sx - hw / 2, top + h * 0.65f), 2.dp.toPx())
        drawLine(grip, Offset(ex + hw / 2, top + h * 0.35f), Offset(ex + hw / 2, top + h * 0.65f), 2.dp.toPx())
        // Playhead
        val px = msToX(positionMs, size.width, pad, dur)
        drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 3.dp.toPx())
        drawCircle(Color.White, 5.dp.toPx(), Offset(px, 3.dp.toPx()))
    }
}

private fun msToX(ms: Long, width: Float, pad: Float, dur: Long) = pad + (width - 2 * pad) * (ms.toFloat() / dur)

private fun xToMs(x: Float, width: Float, pad: Float, dur: Long): Long =
    (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * dur).roundToLong()

fun fmtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val m = t / 60_000
    val s = (t / 1000) % 60
    val tenth = (t / 100) % 10
    return "%d:%02d.%d".format(m, s, tenth)
}
