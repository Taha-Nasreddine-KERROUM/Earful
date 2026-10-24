package app.earful.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * The tab bar's top edge is the surface of the water. Whatever touches it sets off ripples: a tab
 * being chosen, the mini player landing on it, a closed video sinking under it. Once in a while a
 * sinking video throws up a crown of drops. Everything here is in px.
 */
@Stable
class Water {
    private class Wave(val cx: Float, val amp: Float, val width: Float, val wavelength: Float, val duration: Float) {
        var t = 0f
    }

    private class Drop(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val stretch: Float) {
        var life = 0f
    }

    private val waves = ArrayList<Wave>()
    private val drops = ArrayList<Drop>()
    private var gravity = 0f
    private var floor = 0f
    private var wake by mutableIntStateOf(0)

    /** Changes on every frame the water moves: reading it while drawing redraws with the water. */
    var frame by mutableLongStateOf(0L)
        private set

    private val calm get() = waves.isEmpty() && drops.isEmpty()

    /**
     * A ring of ripples centred on [cx] (px from the bar's left edge). [amp] is how high the first
     * ring rises, [width] how far the rings spread, [wavelength] how tightly they're packed.
     */
    fun ripple(cx: Float, amp: Float, width: Float, wavelength: Float, duration: Float = 1.1f) {
        waves += Wave(cx, amp, width, wavelength, duration)
        wake++
    }

    /**
     * A crown of drops thrown up from the surface between [x0] and [x1] (the stretch the sinking
     * bar covered). [unit] is px per dp. The further from the middle a drop starts, the further
     * out it flies.
     */
    fun splash(x0: Float, x1: Float, unit: Float, count: Int = 12) {
        val c = (x0 + x1) / 2
        val half = ((x1 - x0) / 2).coerceAtLeast(1f)
        val span = (x1 - x0 - 40 * unit).coerceAtLeast(1f)
        repeat(count) {
            val x = x0 + 20 * unit + Random.nextFloat() * span
            drops += Drop(
                x = x, y = 0f,
                vx = (x - c) / half * 140f * unit + (Random.nextFloat() - 0.5f) * 50f * unit,
                vy = -(170f + Random.nextFloat() * 170f) * unit,
                r = (1.6f + Random.nextFloat() * 1.8f) * unit,
                stretch = 900f * unit,
            )
        }
        gravity = 900f * unit
        floor = 2f * unit
        wake++
    }

    /** How far the surface is lowered (positive) or raised (negative) at [x]. */
    fun height(x: Float): Float {
        var y = 0f
        for (w in waves) {
            val t = w.t
            val dx = x - w.cx
            val g = dx / w.width
            y += -w.amp * exp(-2.6f * t) * cos(abs(dx) / w.wavelength - t * 16f) *
                exp(-g * g / (1f + t * 1.6f)) * min(1f, t * 14f)
        }
        return y
    }

    /** Runs the water while it moves; sleeps (no frames at all) while it's calm. */
    suspend fun run() {
        while (true) {
            if (calm) snapshotFlow { wake }.first { !calm }
            var last = 0L
            while (!calm) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    last = now
                    step(dt)
                    frame = now
                }
            }
            frame += 1 // one last draw with the surface flat again
        }
    }

    private fun step(dt: Float) {
        for (w in waves) w.t += dt
        waves.removeAll { it.t > it.duration }
        for (d in drops) {
            d.vy += gravity * dt
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.life += dt
        }
        drops.removeAll { (it.vy > 0 && it.y > floor) || it.life > 3f }
    }

    private val edge = Path()
    private val body = Path()

    /**
     * Draws the bar's background up to the surface, the surface line, and any drops in the air.
     * The surface sits at y = 0 of [scope]; ripples rise above it, outside the bar.
     */
    fun draw(scope: DrawScope, fill: Color, line: Color, lineWidth: Float, drop: Color) = with(scope) {
        if (waves.isEmpty()) {
            drawRect(fill)
            drawLine(line, Offset(0f, lineWidth / 2), Offset(size.width, lineWidth / 2), lineWidth)
        } else {
            val step = 3f * density
            edge.reset()
            edge.moveTo(0f, height(0f))
            var x = 0f
            while (x < size.width) {
                x = min(size.width, x + step)
                edge.lineTo(x, height(x))
            }
            body.reset()
            body.addPath(edge)
            body.lineTo(size.width, size.height)
            body.lineTo(0f, size.height)
            body.close()
            drawPath(body, fill)
            drawPath(edge, line, style = Stroke(lineWidth))
        }
        for (d in drops) {
            val speed = hypot(d.vx, d.vy)
            val angle = (atan2(d.vy, d.vx) * 180f / PI).toFloat()
            val sx = 1f + (speed / d.stretch).coerceIn(0f, 0.7f)
            rotate(angle, pivot = Offset(d.x, d.y)) {
                drawOval(drop, topLeft = Offset(d.x - d.r * sx, d.y - d.r), size = Size(2 * d.r * sx, 2 * d.r))
                // a glint, so it reads as a drop of water rather than a dot
                drawCircle(Color.White.copy(alpha = 0.45f), radius = d.r * 0.32f, center = Offset(d.x + d.r * sx * 0.25f, d.y - d.r * 0.35f))
            }
        }
    }
}
