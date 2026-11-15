package com.hdstatus.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Shows [bitmap] with a crop box on top. Drag inside the box to move it, drag a
 * corner to resize. [lockedRatio] (width / height) keeps the shape fixed; null = free.
 */
@Composable
fun ImageCropper(
    bitmap: Bitmap,
    crop: NRect,
    lockedRatio: Float?,
    onCropChange: (NRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val imgRatio = bitmap.width.toFloat() / bitmap.height
    val currentCrop by rememberUpdatedState(crop)
    val onChange by rememberUpdatedState(onCropChange)

    Box(modifier.fillMaxWidth().heightIn(max = 460.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(imgRatio, matchHeightConstraintsFirst = imgRatio < 1f)) {
            Image(image, contentDescription = "Selected photo", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier
                    .fillMaxSize()
                    .systemGestureExclusion()
                    .pointerInput(lockedRatio) {
                        val handleSlop = 36.dp.toPx()
                        val minSize = 48.dp.toPx()
                        var mode = -1 // -1 none, 0 move, 1..4 corner (TL, TR, BL, BR)
                        var startRect = Rect.Zero
                        var anchor = Offset.Zero
                        var pointer = Offset.Zero
                        var total = Offset.Zero

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pos = down.position
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val r = currentCrop.toPx(w, h)
                            startRect = r
                            pointer = pos
                            total = Offset.Zero
                            val corners = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)
                            val nearest = corners.indices.minBy { (corners[it] - pos).getDistance() }
                            val isFull = r.width >= w - 1f && r.height >= h - 1f
                            mode = when {
                                (corners[nearest] - pos).getDistance() <= handleSlop -> nearest + 1
                                r.contains(pos) && !isFull -> 0
                                else -> -1
                            }
                            // Not on the box: leave the touch alone so the page can scroll.
                            if (mode < 0) return@awaitEachGesture
                            if (mode > 0) anchor = corners[3 - (mode - 1)] // opposite corner

                            val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            drag(first.id) { change ->
                                val d = change.positionChange()
                                change.consume()
                                total += d
                                pointer += d
                                val newRect = if (mode == 0) {
                                    val dx = total.x.coerceIn(-startRect.left, w - startRect.right)
                                    val dy = total.y.coerceIn(-startRect.top, h - startRect.bottom)
                                    startRect.translate(dx, dy)
                                } else {
                                    resize(anchor, pointer, mode, lockedRatio, w, h, minSize)
                                }
                                onChange(newRect.toNorm(w, h))
                            }
                        }
                    },
            ) {
                val r = crop.toPx(size.width, size.height)
                val shade = Color.Black.copy(alpha = 0.55f)
                // Darken everything outside the crop.
                drawRect(shade, Offset.Zero, Size(size.width, r.top))
                drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
                drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
                drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
                // Rule-of-thirds guides.
                val guide = Color.White.copy(alpha = 0.45f)
                for (i in 1..2) {
                    val x = r.left + r.width * i / 3
                    val y = r.top + r.height * i / 3
                    drawLine(guide, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(guide, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
                drawRect(Color.White, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                // Corner handles.
                val len = 22.dp.toPx()
                val sw = 4.dp.toPx()
                listOf(
                    r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f),
                    r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f),
                ).forEach { (c, d) ->
                    drawLine(Color.White, c, c + Offset(d.x * len, 0f), sw)
                    drawLine(Color.White, c, c + Offset(0f, d.y * len), sw)
                }
            }
        }
    }
}

private fun resize(anchor: Offset, p: Offset, mode: Int, ratio: Float?, w: Float, h: Float, minSize: Float): Rect {
    // Direction from the fixed (anchor) corner towards the dragged corner.
    val sx = if (mode == 2 || mode == 4) 1f else -1f
    val sy = if (mode == 3 || mode == 4) 1f else -1f
    val maxW = if (sx > 0) w - anchor.x else anchor.x
    val maxH = if (sy > 0) h - anchor.y else anchor.y
    var cw = ((p.x - anchor.x) * sx).coerceAtLeast(0f)
    var ch = ((p.y - anchor.y) * sy).coerceAtLeast(0f)
    if (ratio != null) {
        cw = max(cw, ch * ratio)
        cw = max(cw, minSize).coerceAtMost(min(maxW, maxH * ratio))
        ch = cw / ratio
    } else {
        cw = cw.coerceIn(min(minSize, maxW), maxW)
        ch = ch.coerceIn(min(minSize, maxH), maxH)
    }
    val left = if (sx > 0) anchor.x else anchor.x - cw
    val top = if (sy > 0) anchor.y else anchor.y - ch
    return Rect(left, top, left + cw, top + ch)
}

private fun NRect.toPx(w: Float, h: Float) = Rect(left * w, top * h, right * w, bottom * h)

private fun Rect.toNorm(w: Float, h: Float) = NRect(
    (left / w).coerceIn(0f, 1f), (top / h).coerceIn(0f, 1f),
    (right / w).coerceIn(0f, 1f), (bottom / h).coerceIn(0f, 1f),
)
