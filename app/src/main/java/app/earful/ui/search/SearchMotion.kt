package app.earful.ui.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.earful.ui.components.Icons
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opening search, "Surface": the magnifier dips as if it has weight, then grows into the search
 * field — it slides across to its place in the field while the field spreads out from it. Behind
 * it, Home goes under the water (smaller, softer, dimmer), the tab bar sinks away as the keyboard
 * comes up, and the recent searches float up one after another. Going back shrinks the field into
 * the magnifier and Home comes back up.
 */
@Stable
class SearchMotion {
    /** 0 = Home at the surface, 1 = under (94%, softened, half dimmed). */
    val homeUnder = Animatable(0f)
    /** Search's own background, fading in over Home. */
    val bg = Animatable(0f)
    /** 0 = the magnifier's circle, 1 = the search field. */
    val morph = Animatable(0f)
    /** The back arrow sliding in. */
    val back = Animatable(0f)
    /** The real field (shown once the morph arrives) and its placeholder. */
    val field = Animatable(0f)
    val hint = Animatable(0f)
    /** The tab bar's offset in dp: lifts a hair, then sinks away. */
    val bar = Animatable(0f)
    /** The magnifier on Home dipping. */
    val dip = Animatable(1f)
    /** 0 = rows in place, 1 = gone (faded, 8 dp lower) as search closes. */
    val leave = Animatable(0f)

    /** Rows that appear while this is on float up one after another. */
    var entering by mutableStateOf(false)
        private set
    /** The magnifier-to-field shape is on screen (the real magnifier and field hide meanwhile). */
    var morphing by mutableStateOf(false)
        private set
    /** Search is open, or opening. */
    var open by mutableStateOf(false)
        private set
    /** Search is on screen: open, or still closing. */
    var shown by mutableStateOf(false)
        private set

    /** The magnifier on Home (where the field grows from), and where the search field is (root px). */
    var anchor: LayoutCoordinates? = null
    var target: LayoutCoordinates? = null
    /** Where the field was last time, for the first frame before it's placed again. */
    internal var lastTarget: Rect? = null

    suspend fun open(barSink: Float) = coroutineScope {
        if (open) return@coroutineScope
        open = true
        leave.snapTo(0f); field.snapTo(0f); hint.snapTo(0f); back.snapTo(0f); morph.snapTo(0f)
        // The magnifier dips as if it has weight…
        dip.animateTo(0.86f, tween(90, easing = Motion.outCubic))
        morphing = true
        dip.snapTo(1f)
        entering = true
        shown = true
        // …Home goes under, search's background rises over it, the tab bar sinks away…
        launch { homeUnder.animateTo(1f, tween(360, easing = Motion.inOutCubic)) }
        launch { delay(80); bg.animateTo(1f, tween(260, easing = Motion.outCubic)) }
        launch { bar.animateTo(-3f, tween(100, easing = Motion.outCubic)); bar.animateTo(barSink, tween(280, easing = Motion.inCubic)) }
        launch { delay(120); back.animateTo(1f, tween(220, easing = Motion.outCubic)) }
        launch { delay(700); entering = false }
        // …and the magnifier grows into the field.
        morph.animateTo(1f, Motion.spread())
        field.snapTo(1f)
        morphing = false
        hint.animateTo(1f, tween(160, easing = Motion.outCubic))
    }

    /** Already open (e.g. restored after the screen rotated): no animation. */
    suspend fun snapOpen(barSink: Float) {
        open = true; shown = true; entering = false; morphing = false
        homeUnder.snapTo(1f); bg.snapTo(1f); morph.snapTo(1f); back.snapTo(1f)
        field.snapTo(1f); hint.snapTo(1f); bar.snapTo(barSink); dip.snapTo(1f); leave.snapTo(0f)
    }

    suspend fun close() = coroutineScope {
        if (!open) return@coroutineScope
        open = false
        entering = false
        launch { leave.animateTo(1f, tween(140, easing = Motion.inCubic)) }
        launch { back.animateTo(0f, tween(120, easing = Motion.inCubic)) }
        launch { hint.animateTo(0f, tween(120, easing = Motion.inCubic)) }
        delay(80)
        field.snapTo(0f)
        morphing = true
        launch { bg.animateTo(0f, tween(240, easing = Motion.outCubic)) }
        launch { homeUnder.animateTo(0f, tween(400, easing = Motion.outCubic)) }
        launch {
            // The tab bar comes back up, with a little bob.
            bar.animateTo(0f, tween(300, easing = Motion.outCubic))
            bar.animateTo(0f, Motion.barBob(), initialVelocity = -70f)
        }
        morph.animateTo(0f, Motion.gather())
        morphing = false
        shown = false
    }
}

/** The magnifier on its way into the field (and back): a pill growing from it, with the glyph riding along. */
@Composable
fun SearchMorph(motion: SearchMotion, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val glyph = rememberVectorPainter(Icons.Search)
    Canvas(modifier.fillMaxSize()) {
        if (!motion.morphing) return@Canvas
        val a = motion.anchor?.takeIf { it.isAttached }?.boundsInRoot() ?: return@Canvas
        val circle = 40.dp.toPx()
        val r0 = Rect(Offset(a.center.x - circle / 2, a.center.y - circle / 2), Size(circle, circle))
        // (The very first frame, before the field has been placed, the circle simply stays put.)
        val b = motion.target?.takeIf { it.isAttached }?.boundsInRoot()?.also { motion.lastTarget = it }
            ?: motion.lastTarget ?: r0
        val e = motion.morph.value
        val ec = e.coerceIn(0f, 1f)
        val left = lerp(r0.left, b.left, e)
        val top = lerp(r0.top, b.top, e)
        val w = lerp(r0.width, b.width, e)
        val h = lerp(r0.height, b.height, e)
        val radius = lerp(circle / 2, 12.dp.toPx(), ec)
        drawRoundRect(p.fill, Offset(left, top), Size(w, h), CornerRadius(radius, radius))
        // The glyph slides to where the field's own magnifier sits, shrinking and dimming.
        val c0 = a.center
        val c1 = Offset(b.left + 19.dp.toPx(), b.center.y)
        val size = lerp(24.dp.toPx(), 18.dp.toPx(), ec)
        val cx = lerp(c0.x, c1.x, e)
        val cy = lerp(c0.y, c1.y, e)
        val tint = lerp(p.label, p.secondary, (ec * 2.5f).coerceIn(0f, 1f))
        translate(cx - size / 2, cy - size / 2) {
            with(glyph) { draw(Size(size, size), colorFilter = ColorFilter.tint(tint)) }
        }
    }
}
