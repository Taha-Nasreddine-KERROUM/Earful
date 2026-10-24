package app.earful.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Liquid in a glass, for the Save and Queue buttons: tapping fills the icon from the bottom with
 * a sloshing surface (and the icon pops); tapping again drains it, with a drip falling from the
 * bottom. Works in the icon's own 24-unit space.
 */
@Stable
private class Glass(initial: Float) {
    var level by mutableFloatStateOf(initial)
    var slosh by mutableFloatStateOf(0f)
    var phase by mutableFloatStateOf(0f)
    /** < 0: no drop; 0..1: the drip falling. */
    var drip by mutableFloatStateOf(-1f)
    val pop = Animatable(1f)

    suspend fun fill() = coroutineScope {
        launch { pop.snapTo(1.12f); pop.animateTo(1f, Motion.pop()) }
        drip = -1f
        // A quick fill with one slosh (a spring, stiffness 150, damping 0.5).
        var v = 0f
        var time = 0f
        var last = 0L
        var done = false
        while (!done) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val n = max(1, ceil(dt / 0.004f).toInt())
            val h = dt / n
            var l = level
            for (i in 0 until n) { v += (-150f * (l - 1f) - 2f * 0.5f * sqrt(150f) * v) * h; l += v * h }
            level = l
            time += dt
            slosh = 1.8f * exp(-time * 2.5f)
            phase += dt * 9f
            done = abs(l - 1f) < 0.001f && abs(v) < 0.004f
        }
        level = 1f
        // The last of the slosh settles.
        val a0 = slosh
        var t = 0f
        last = 0L
        while (t < 0.4f) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1e9f
            last = now
            t += dt
            slosh = a0 * (1f - Motion.inOutCubic.transform((t / 0.4f).coerceIn(0f, 1f)))
            phase += dt * 9f
        }
        slosh = 0f
    }

    suspend fun drain() = coroutineScope {
        // Tapped again mid-pop: let the pop finish settling rather than stay a little big.
        if (pop.value != 1f) launch { pop.animateTo(1f, Motion.pop()) }
        val l0 = level
        var t = 0f
        var last = 0L
        while (t < 0.55f) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else (now - last) / 1e9f
            last = now
            t += dt
            val u = (t / 0.55f).coerceIn(0f, 1f)
            level = l0 * (1f - Motion.inCubic.transform(u))
            slosh = 0.8f * sin(PI.toFloat() * u)
            phase += dt * 12f
            drip = if (u > 0.4f) (u - 0.4f) / 0.6f else -1f
        }
        level = 0f; slosh = 0f; drip = -1f
    }

    suspend fun set(full: Boolean) { level = if (full) 1f else 0f; slosh = 0f; drip = -1f; pop.snapTo(1f) }

    /** The liquid's body: everything below its (sloshing) surface, in 24-unit space. */
    fun liquid(path: Path) {
        val base = 21.8f - level * 24.8f
        path.reset()
        path.moveTo(-2f, 27f)
        var x = -2f
        while (x <= 26f) {
            path.lineTo(x, base + slosh * sin(x * 0.55f + phase))
            x += 1.5f
        }
        path.lineTo(26f, 27f)
        path.close()
    }

    /** The drip leaving the bottom as it drains. */
    fun DrawScope.drawDrip(cx: Float, fromY: Float, color: Color) {
        val d = drip
        if (d < 0f) return
        drawCircle(color.copy(alpha = color.alpha * (1f - d)), radius = 1.33f * (1f - d * 0.5f), center = Offset(cx, fromY + d * d * 14.7f))
    }
}

private val bookmarkOutline = PathParser().parsePathString(Icons.BookmarkPath).toPath()
private val bookmarkFill = PathParser().parsePathString(Icons.BookmarkFillPath).toPath()

/**
 * Save for later. The bookmark is a glass: saving fills it with colour from the bottom, sloshing
 * once as it settles, while the bookmark pops. Un-saving drains it, and a drop falls from the bottom.
 */
@Composable
fun SaveButton(saved: Boolean, onToggle: () -> Unit, tint: Color, size: Int = 22, hit: Int = 44) {
    val view = LocalView.current
    val accent = LocalPalette.current.accent
    val glass = remember { Glass(if (saved) 1f else 0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(saved) {
        if (first) { first = false; glass.set(saved); return@LaunchedEffect }
        if (saved) glass.fill() else glass.drain()
    }
    val liquid = remember { Path() }
    Box(
        Modifier
            .size(hit.dp) // ~Apple's minimum hit target
            .pressable(scaleTo = 0.85f, role = Role.Switch, label = if (saved) "Remove from Saved" else "Save for later") {
                if (!saved) Haptics.confirm(view) else Haptics.tick(view)
                onToggle()
            }
            .semantics { contentDescription = if (saved) "Saved" else "Save for later" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.dp).graphicsLayer { scaleX = glass.pop.value; scaleY = glass.pop.value }) {
            val u = this.size.minDimension / 24f
            scale(u, u, pivot = Offset.Zero) {
                if (glass.level > 0f) {
                    glass.liquid(liquid)
                    clipPath(bookmarkFill) { drawPath(liquid, accent) }
                }
                drawPath(
                    bookmarkOutline, lerp(tint, accent, (glass.level * 3f).coerceIn(0f, 1f)),
                    style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                with(glass) { drawDrip(12f, 18.3f, accent) }
            }
        }
    }
}

/** Video ids currently waiting in (or playing from) the queue; drives the queue toggles. */
val LocalQueuedIds = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/**
 * Add to / remove from queue — a toggle, like the bookmark, and also a glass: adding fills the
 * icon with colour from the bottom with a slosh, while the list's lines ripple outward one after
 * another (the list making room) and the plus turns a quarter and sinks away as a tick is drawn in.
 * Tapping again un-draws the tick, the plus turns back and the colour drains out with a drip.
 */
@Composable
fun QueueButton(queued: Boolean, onToggle: () -> Unit, tint: Color, hit: Int = 44, size: Int = 22) {
    val view = LocalView.current
    val accent = LocalPalette.current.accent
    val done = remember { Animatable(if (queued) 1f else 0f) }  // plus ↔ tick
    val wave = remember { Animatable(0f) }                      // lines ripple
    val glass = remember { Glass(if (queued) 1f else 0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(queued) {
        if (first) { first = false; done.snapTo(if (queued) 1f else 0f); glass.set(queued); return@LaunchedEffect }
        if (queued) {
            launch { wave.snapTo(0f); wave.animateTo(1f, tween(520)) }
            launch { glass.fill() }
            done.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        } else {
            launch { glass.drain() }
            done.animateTo(0f, Motion.lively())
        }
    }
    val liquid = remember { Path() }

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
        Canvas(Modifier.size(size.dp).graphicsLayer { scaleX = glass.pop.value; scaleY = glass.pop.value }) {
            val u = this.size.minDimension / 24f
            scale(u, u, pivot = Offset.Zero) {
                // Drawn in the plain colour, then again in the accent wherever the liquid has risen to.
                queueGlyph(tint, done.value.coerceIn(0f, 1f), wave.value)
                if (glass.level > 0f) {
                    glass.liquid(liquid)
                    clipPath(liquid) { queueGlyph(accent, done.value.coerceIn(0f, 1f), wave.value) }
                }
                with(glass) { drawDrip(11f, 17.5f, accent) }
            }
        }
    }
}

/** The queue glyph in 24-unit space: three list lines, and a plus that turns into a tick. */
private fun DrawScope.queueGlyph(color: Color, t: Float, wave: Float) {
    val stroke = 1.9f
    // Three list lines; each stretches briefly in turn (top → bottom), like a list shuffling down.
    listOf(Triple(6.5f, 15f, 0), Triple(11.5f, 15f, 1), Triple(16.5f, 11f, 2)).forEach { (y, end, i) ->
        val local = ((wave - i * 0.18f) / 0.46f).coerceIn(0f, 1f)
        val bump = if (local <= 0f || local >= 1f) 0f else sin(local * PI).toFloat()
        val reach = if (i == 2) 1.2f else 2.5f // the short line sits next to the tick: smaller stretch
        drawLine(color, Offset(4f, y), Offset(end + reach * bump, y), stroke, StrokeCap.Round)
    }
    // The plus: turns 90° and shrinks into the tick (and back out when released).
    if (t < 1f) {
        val c = Offset(18f, 16.5f)
        rotate(90f * t, pivot = c) {
            scale(1f - t, pivot = c) {
                drawLine(color, Offset(c.x, c.y - 3f), Offset(c.x, c.y + 3f), stroke, StrokeCap.Round)
                drawLine(color, Offset(c.x - 3f, c.y), Offset(c.x + 3f, c.y), stroke, StrokeCap.Round)
            }
        }
    }
    // The tick: drawn in like a pen stroke, un-drawn on release.
    val draw = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
    if (draw > 0f) {
        val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (draw >= 1f) drawPath(Tick.path, color, style = style)
        else {
            Tick.seg.reset()
            Tick.measure.getSegment(0f, Tick.measure.length * draw, Tick.seg, true)
            drawPath(Tick.seg, color, style = style)
        }
    }
}

/** The tick, built once (lists draw many queue buttons; nothing is allocated per frame). */
private object Tick {
    val path = Path().apply { moveTo(14.5f, 16.8f); lineTo(16.7f, 19f); lineTo(21f, 14.4f) }
    val measure = PathMeasure().apply { setPath(path, false) }
    val seg = Path()
}
