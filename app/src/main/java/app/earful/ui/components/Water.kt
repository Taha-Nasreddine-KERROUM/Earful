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

    private val calm get() = waves.isEmpty() && drops.isEmpty() && !surfaceOn

    // ----- F2 · a stick in water ---------------------------------------------------------------
    // The bar's surface as water in a cup (a 1-D wave equation whose ends reflect like the cup's
    // walls). The gap between the two pages, while it slides across, is a stick dragged through it:
    // water piles up in front, dips behind and leaves ripples that bounce off the sides and settle.
    private var unit = 1f
    private var barWidth = 0f
    private var n = 0
    private var dx = 1f
    private var h = FloatArray(0)
    private var v = FloatArray(0)
    private var lap = FloatArray(0)
    private var surfaceOn = false
    private var stickX = -1e6f
    private var stickPx = Float.NaN
    private var stickV = 0f
    private var stickC = 0f
    private var stickIn = false

    /** The bar's width and px per dp; the surface is laid out from these. */
    fun size(width: Float, unit: Float) {
        if (width == barWidth && unit == this.unit) return
        barWidth = width; this.unit = unit
        n = ((width / unit) / 3f).toInt().coerceAtLeast(8) + 1
        dx = width / (n - 1)
        h = FloatArray(n); v = FloatArray(n); lap = FloatArray(n)
        surfaceOn = false
    }

    /**
     * Where the stick (the gap between the pages) is, in px from the bar's left edge. Called on
     * every frame the pages move; outside the bar it simply isn't in the water.
     */
    fun stick(x: Float) {
        if (n == 0) return
        stickX = x
        val c = (min(x + 4f * unit, barWidth + 4f * unit - x) / (10f * unit)).coerceIn(0f, 1f)
        if (c > 0f && !stickIn) {
            // a small dip as it enters the water
            stickIn = true
            impulse(x.coerceIn(0f, barWidth), 10f * unit, 160f * unit * STICK_K)
        }
        if (c <= 0f) stickIn = false
        stickC = c
        if (c > 0f && !surfaceOn) { surfaceOn = true; stickPx = Float.NaN; stickV = 0f; wake++ }
    }

    private fun impulse(x0: Float, w: Float, amt: Float) {
        for (i in 0 until n) { val g = (i * dx - x0) / w; v[i] += amt * exp(-g * g) }
        if (!surfaceOn) { surfaceOn = true; stickPx = Float.NaN; stickV = 0f; wake++ }
    }

    private fun stepSurface(dt: Float) {
        if (dt > 0f) {
            if (!stickPx.isNaN()) stickV = stickV * 0.5f + (stickX - stickPx) / dt * 0.5f
            stickPx = stickX
        }
        val c2 = (200f * unit) * (200f * unit) / (dx * dx)
        val nu = 250f * unit * unit / (dx * dx)
        val sub = kotlin.math.max(1, kotlin.math.ceil(dt / 0.0015f).toInt())
        val hh = dt / sub
        val s = 13f * unit
        val kPush = 0.07f * STICK_K
        val kDrag = 0.01f * STICK_K / unit
        val rate = 1f - exp(-hh * 60f)
        repeat(sub) {
            for (i in 0 until n) {
                val l = if (i > 0) v[i - 1] else v[i]
                val r = if (i < n - 1) v[i + 1] else v[i]
                lap[i] = l + r - 2f * v[i]
            }
            for (i in 0 until n) {
                val l = if (i > 0) h[i - 1] else h[i]
                val r = if (i < n - 1) h[i + 1] else h[i]
                v[i] += (c2 * (l + r - 2f * h[i]) - 1.8f * v[i] - 20f * h[i] + nu * lap[i]) * hh
            }
            for (i in 0 until n) h[i] += v[i] * hh
            if (stickC > 0f) {
                val lo = kotlin.math.max(0, ((stickX - 4f * s) / dx).toInt())
                val hi = kotlin.math.min(n - 1, ((stickX + 4f * s) / dx).toInt() + 1)
                for (i in lo..hi) {
                    val u = (i * dx - stickX) / s
                    val g = exp(-u * u)
                    val target = ((-kPush * stickV * u * g).coerceIn(-14f * unit, 14f * unit) - 1.5f * unit * g) * stickC
                    h[i] += (target - h[i]) * rate * g * 0.6f
                    v[i] += -kDrag * stickV * abs(stickV) * u * g * hh * stickC
                }
            }
        }
        // Still again (and the stick out of the water): flat, and the water sleeps.
        if (stickC <= 0f) {
            var m = 0f
            for (i in 0 until n) m = kotlin.math.max(m, kotlin.math.max(abs(h[i]), abs(v[i]) * 0.05f))
            // (below ~0.4 dp nobody can see it move, so stop drawing frames for it)
            if (m < 0.4f * unit) { h.fill(0f); v.fill(0f); surfaceOn = false; stickPx = Float.NaN; stickV = 0f }
        }
    }

    private fun surfaceAt(x: Float): Float {
        if (!surfaceOn || n == 0) return 0f
        val f = (x / dx).coerceIn(0f, (n - 1).toFloat())
        val i = f.toInt().coerceAtMost(n - 2)
        val t = f - i
        return h[i] + (h[i + 1] - h[i]) * t
    }

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
        var y = surfaceAt(x)
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
        if (surfaceOn) stepSurface(dt)
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

    private companion object {
        /** Wave size: medium. */
        const val STICK_K = 1f
    }

    private val edge = Path()
    private val body = Path()

    /**
     * Draws the bar's background up to the surface, the surface line, and any drops in the air.
     * The surface sits at y = 0 of [scope]; ripples rise above it, outside the bar.
     */
    fun draw(scope: DrawScope, fill: Color, line: Color, lineWidth: Float, drop: Color) = with(scope) {
        if (waves.isEmpty() && !surfaceOn) {
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
