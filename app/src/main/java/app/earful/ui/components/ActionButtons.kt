package app.earful.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import kotlinx.coroutines.launch

/**
 * Save for later. Like a ribbon bookmark slipped into a book: the colour runs down the ribbon
 * from the top while the whole bookmark dips and springs back. Un-saving pulls the colour back up.
 */
@Composable
fun SaveButton(saved: Boolean, onToggle: () -> Unit, tint: Color, size: Int = 22, hit: Int = 44) {
    val view = LocalView.current
    val accent = LocalPalette.current.accent
    val fill = remember { Animatable(if (saved) 1f else 0f) }
    val dip = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(saved) {
        if (first) { first = false; fill.snapTo(if (saved) 1f else 0f); return@LaunchedEffect }
        if (saved) {
            launch { dip.animateTo(1f, tween(90)); dip.animateTo(0f, Motion.lively()) }
            fill.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        } else {
            launch { dip.animateTo(-0.5f, tween(90)); dip.animateTo(0f, Motion.lively()) }
            fill.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier
            .size(hit.dp) // ~Apple's minimum hit target
            .pressable(scaleTo = 0.85f, role = Role.Switch, label = if (saved) "Remove from Saved" else "Save for later") {
                if (!saved) Haptics.confirm(view) else Haptics.tick(view)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer {
            translationY = dip.value * 3.dp.toPx()
            val s = 1f + 0.06f * dip.value
            scaleX = s; scaleY = s
        }) {
            Icon(
                Icons.Bookmark, tint = lerp(tint, accent, fill.value), size = size.dp,
                contentDescription = if (saved) "Saved" else "Save for later",
            )
            Icon(
                Icons.BookmarkFill, tint = accent, size = size.dp,
                modifier = Modifier.drawWithContent {
                    clipRect(bottom = size.dp.toPx() * fill.value) { this@drawWithContent.drawContent() }
                },
            )
        }
    }
}

/** Video ids currently waiting in (or playing from) the queue; drives the queue toggles. */
val LocalQueuedIds = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/**
 * Add to / remove from queue — a toggle, like the bookmark. Adding: the list's lines ripple
 * outward one after another (the list making room) while the plus turns a quarter and sinks
 * away as a tick is drawn in. It stays ticked while the video is queued. Tapping again
 * un-draws the tick and the plus turns back: the video leaves the queue.
 */
@Composable
fun QueueButton(queued: Boolean, onToggle: () -> Unit, tint: Color, hit: Int = 44, size: Int = 22) {
    val view = LocalView.current
    val accent = LocalPalette.current.accent
    val done = remember { Animatable(if (queued) 1f else 0f) }  // plus ↔ tick
    val wave = remember { Animatable(0f) }                      // lines ripple
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(queued) {
        if (first) { first = false; done.snapTo(if (queued) 1f else 0f); return@LaunchedEffect }
        if (queued) {
            launch { wave.snapTo(0f); wave.animateTo(1f, tween(520)) }
            done.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        } else {
            done.animateTo(0f, Motion.lively())
        }
    }

    Box(
        Modifier
            .size(hit.dp)
            .semantics { contentDescription = if (queued) "Remove from queue" else "Add to queue" }
            .pressable(scaleTo = 0.85f, role = Role.Switch, label = if (queued) "Remove from queue" else "Add to queue") {
                if (!queued) Haptics.confirm(view) else Haptics.tick(view)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.dp)) {
            val u = this.size.minDimension / 24f
            val t = done.value.coerceIn(0f, 1f)
            val color = lerp(tint, accent, t)
            val stroke = 1.9f * u

            // Three list lines; each stretches briefly in turn (top → bottom), like a list shuffling down.
            listOf(Triple(6.5f, 15f, 0), Triple(11.5f, 15f, 1), Triple(16.5f, 11f, 2)).forEach { (y, end, i) ->
                val local = ((wave.value - i * 0.18f) / 0.46f).coerceIn(0f, 1f)
                val bump = if (local <= 0f || local >= 1f) 0f else kotlin.math.sin(local * Math.PI).toFloat()
                val reach = if (i == 2) 1.2f else 2.5f // the short line sits next to the tick: smaller stretch
                drawLine(color, Offset(4f * u, y * u), Offset((end + reach * bump) * u, y * u), stroke, StrokeCap.Round)
            }

            // The plus: turns 90° and shrinks into the tick (and back out when released).
            if (t < 1f) {
                val c = Offset(18f * u, 16.5f * u)
                rotate(90f * t, pivot = c) {
                    scale(1f - t, pivot = c) {
                        drawLine(color, Offset(c.x, c.y - 3f * u), Offset(c.x, c.y + 3f * u), stroke, StrokeCap.Round)
                        drawLine(color, Offset(c.x - 3f * u, c.y), Offset(c.x + 3f * u, c.y), stroke, StrokeCap.Round)
                    }
                }
            }

            // The tick: drawn in like a pen stroke, un-drawn on release.
            val draw = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (draw > 0f) {
                val path = Path().apply {
                    moveTo(14.5f * u, 16.8f * u); lineTo(16.7f * u, 19f * u); lineTo(21f * u, 14.4f * u)
                }
                val m = PathMeasure().apply { setPath(path, false) }
                val seg = Path()
                m.getSegment(0f, m.length * draw, seg, true)
                drawPath(seg, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
