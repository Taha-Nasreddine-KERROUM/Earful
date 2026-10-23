package app.earful.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.earful.ui.components.Format
import app.earful.ui.components.Haptics
import app.earful.ui.components.Text
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Apple Music's scrubber: no knob, a thin bar that swells under your finger.
 * Dragging is relative (the bar doesn't jump to your finger, so you can't lose your place);
 * a plain tap jumps. The lighter segment is how much is already saved offline.
 */
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    downloaded: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    ads: List<app.earful.data.AdSegment> = emptyList(),
) {
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var held by remember { mutableStateOf<Float?>(null) } // shown until the player catches up

    val dur = durationMs.coerceAtLeast(1)
    val live = (positionMs.toFloat() / dur).coerceIn(0f, 1f)
    val target = when {
        dragging -> dragFraction
        held != null -> held!!
        else -> live
    }
    LaunchedEffect(held) { if (held != null) { delay(700); held = null } }
    // Jumps (a tap, ±10 s, a skipped sponsor) glide the bar to its new place so your eye can follow
    // where you went; normal playback and dragging stay 1:1 (no animation, no extra frames).
    val shown = remember { androidx.compose.animation.core.Animatable(target) }
    LaunchedEffect(target, dragging) {
        if (!dragging && kotlin.math.abs(target - shown.value) > 0.015f) shown.animateTo(target, Motion.sheet())
        else shown.snapTo(target)
    }
    val fraction = shown.value

    val barHeight by animateDpAsState(if (dragging) 11.dp else 6.dp, Motion.snappy(), label = "bar")
    val brightness by animateFloatAsState(if (dragging) 1f else 0.78f, Motion.snappy(), label = "bright")
    val timeShift by animateDpAsState(if (dragging) 6.dp else 0.dp, Motion.snappy(), label = "shift")

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp) // generous hit area around a thin bar (Fitts)
                .semantics {
                    contentDescription = "Playback position"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { onSeek((it * dur).toLong()); true }
                }
                .pointerInput(dur) {
                    detectTapGestures { o ->
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        held = f; onSeek((f * dur).toLong()); Haptics.tick(view)
                    }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragFraction = held ?: live; dragging = true; Haptics.tick(view)
                        },
                        onDragEnd = {
                            dragging = false; held = dragFraction
                            onSeek((dragFraction * dur).toLong()); Haptics.tick(view)
                        },
                        onDragCancel = { dragging = false },
                    ) { change, dx ->
                        change.consume()
                        dragFraction = (dragFraction + dx / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(barHeight)) {
                val h = size.height
                val r = CornerRadius(h / 2, h / 2)
                drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
                if (downloaded > 0f) {
                    drawRoundRect(Color.White.copy(alpha = 0.16f), size = Size(size.width * downloaded, h), cornerRadius = r)
                }
                drawRoundRect(
                    Color.White.copy(alpha = brightness), topLeft = Offset.Zero,
                    size = Size((size.width * fraction).coerceAtLeast(h), h), cornerRadius = r,
                )
                // Sponsor sections that get skipped, in yellow, so you can see where they were.
                ads.forEach { a ->
                    val x0 = size.width * (a.startMs.toFloat() / dur).coerceIn(0f, 1f)
                    val x1 = size.width * (a.endMs.toFloat() / dur).coerceIn(0f, 1f)
                    if (x1 > x0) drawRoundRect(
                        Color(0xFFFFD60A).copy(alpha = 0.85f), topLeft = Offset(x0, 0f),
                        size = Size((x1 - x0).coerceAtLeast(h * 0.6f), h), cornerRadius = r,
                    )
                }
            }
        }
        val shownMs = (fraction * dur).toLong()
        Row(Modifier.fillMaxWidth().offset(y = timeShift).padding(top = 2.dp)) {
            val style = Type.caption.copy(fontFeatureSettings = "tnum")
            val c = Color.White.copy(alpha = if (dragging) 0.9f else 0.55f)
            Text(Format.duration(shownMs / 1000), style, color = c)
            Spacer(Modifier.weight(1f))
            Text("−" + Format.duration(((dur - shownMs).coerceAtLeast(0)) / 1000), style, color = c)
        }
    }
}

/** Mini player's bar: just a hairline that shows where you are. Not interactive by design. */
@Composable
fun MiniProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(2.dp).graphicsLayer { }) {
        drawRect(color.copy(alpha = 0.15f))
        drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}
