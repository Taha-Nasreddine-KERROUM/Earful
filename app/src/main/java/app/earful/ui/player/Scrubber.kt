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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlinx.coroutines.launch

/**
 * Apple Music's scrubber, made of water: no knob, a thin bar that swells under your finger like a
 * bead of water, and wobbles flat when you let go. Dragging is relative (the bar doesn't jump to
 * your finger, so you can't lose your place); a plain tap jumps. The lighter segment is how much
 * is already saved offline.
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
    val scope = rememberCoroutineScope()
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

    // How much the bead of water under your finger swells (dp): up while held, wobbling flat after.
    val swell = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(dragging) {
        if (dragging) swell.animateTo(6f, Motion.swell()) else if (swell.value != 0f) swell.animateTo(0f, Motion.wobble())
    }
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
                        // a tap is a drop landing on the bar: it swells, then wobbles flat
                        scope.launch { swell.snapTo(5f); swell.animateTo(0f, Motion.wobble()) }
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
            val band = remember { Path() }
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2
                val base = 6.dp.toPx()
                val bulge = swell.value.dp.toPx()
                val sigma = 18.dp.toPx()
                val px = size.width * fraction
                val thin = 1.5.dp.toPx()
                // Thickness along the bar: the bead of water is the bar itself swelling around the
                // playhead (no separate knob, so nothing to stick out or leave a gap).
                fun th(x: Float): Float {
                    val g = (x - px) / sigma
                    return kotlin.math.max(thin, base + bulge * kotlin.math.exp(-g * g))
                }
                // One liquid shape for the whole bar; every part (played, saved offline, sponsor
                // sections) is the same shape cut at its ends, so they always line up exactly.
                val w = size.width
                val r0 = th(0f) / 2
                val r1 = th(w) / 2
                val a = r0
                val b = kotlin.math.max(a, w - r1)
                band.reset()
                band.moveTo(a, cy - th(a) / 2)
                var x = a
                val step = 1.5.dp.toPx()
                while (x < b) { x = kotlin.math.min(b, x + step); band.lineTo(x, cy - th(x) / 2) }
                band.arcTo(Rect(Offset(b, cy), th(b) / 2), -90f, 180f, false)
                x = b
                while (x > a) { x = kotlin.math.max(a, x - step); band.lineTo(x, cy + th(x) / 2) }
                band.arcTo(Rect(Offset(a, cy), th(a) / 2), 90f, 180f, false)
                band.close()
                drawPath(band, Color.White.copy(alpha = 0.18f))
                if (downloaded > 0f) clipRect(right = w * downloaded) { drawPath(band, Color.White.copy(alpha = 0.16f)) }
                // The played part ends in a half circle exactly as thick as the bar is there, so
                // its head is round like the bar's own ends.
                val played = Color.White.copy(alpha = brightness)
                var rc = th(px) / 2
                rc = th(px - rc) / 2
                val hx = (px - rc).coerceAtLeast(r0)
                if (px > 0.5f) {
                    clipRect(right = hx) { drawPath(band, played) }
                    clipPath(band) { clipRect(left = hx) { drawCircle(played, th(hx) / 2, Offset(hx, cy)) } }
                }
                // Sponsor sections that get skipped, in yellow, so you can see where they were.
                ads.forEach { ad ->
                    val x0 = w * (ad.startMs.toFloat() / dur).coerceIn(0f, 1f)
                    val x1 = w * (ad.endMs.toFloat() / dur).coerceIn(0f, 1f)
                    if (x1 > x0) clipRect(left = x0, right = kotlin.math.max(x1, x0 + base * 0.6f)) {
                        drawPath(band, Color(0xFFFFD60A).copy(alpha = 0.85f))
                    }
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
