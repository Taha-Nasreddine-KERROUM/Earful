package app.earful.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.earful.ui.hole.Hole
import app.earful.ui.hole.HoleSpin
import app.earful.ui.hole.eatable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ±10 s. Every tap turns the arrow 60° the way time moves, so six taps (a minute) bring it all
 * the way round. Between taps it slowly turns back, never below a full turn it has already made.
 * Once it has come full circle, the next tap sets it spinning for as long as you keep tapping;
 * when you stop it rolls on to the next full turn and rests.
 *
 * Past +360 s it loses control: it spins faster, blurs, wobbles like a top, the arrowhead slips
 * back along the circle, and the "10" breaks loose and smashes into the arrow's walls. Keep going
 * and the phone starts to tremble (from +1700 s); at +2000 s the button collapses into a black
 * hole that eats the app layer by layer for as long as you keep tapping, and throws it all back
 * when you stop (see [Hole]).
 */
@Composable
internal fun SkipButton(forward: Boolean, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spin = remember { SkipSpin(if (forward) 1f else -1f) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    spin.measurer = measurer
    fun tap() {
        onSkip()
        spin.tap()
        spin.start(scope)
    }
    spin.onTap = ::tap
    Box(
        Modifier.onGloballyPositioned { spin.center = it.boundsInRoot().center }
            .eatable(layer = app.earful.ui.hole.HoleLayer.Player, owner = spin),
        contentAlignment = Alignment.Center,
    ) {
        CircleButton(
            onClick = ::tap,
            size = 72.dp,
            label = if (forward) "Forward 10 seconds" else "Back 10 seconds",
        ) {
            SpinningSkipIcon(spin, Color.White, 38.dp)
        }
        // "+40 s" floating up from the button (drawn by the hole instead while it's open)
        Canvas(Modifier.size(72.dp).align(Alignment.Center)) {
            spin.frame
            if (!spin.inHole()) spin.drawCounter(this, Offset(size.width / 2, size.height / 2))
        }
    }
}

@Composable
private fun SpinningSkipIcon(s: SkipSpin, tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        s.frame // redraw on every step of the motion
        if (s.inHole()) return@Canvas // the black hole has taken its place
        s.drawIcon(this, center, this.size.minDimension, tint)
    }
}

/** The motion of one skip button (plain numbers, stepped once per frame; [frame] tells the drawing). */
internal class SkipSpin(override val dir: Float) : HoleSpin {
    var frame by mutableIntStateOf(0)
    lateinit var measurer: TextMeasurer
    override var center = Offset.Zero
    override var onTap: () -> Unit = {}

    var pos = 0f; private var vel = 0f
    private var target = 0f; private var anchor = 0f
    private var tapAt = -10f; private var combo = 0
    private var spinning = false
    override val total get() = combo * 10
    // losing control (0..1) and how it shows
    var c = 0f; private var cPeak = 0f; private var settling = false
    var phi = 0f; var tilt = 0f; var jx = 0f; var jy = 0f
    var lag = 0f; private var lagV = 0f
    var dizzy = 0f; private var dizzyV = 0f
    var ghost = 0f
    var time = 0f
    // the "10", loose: where it is in the ring (in icon widths), its turn, its squash on a hit
    private var nx = 0f; private var ny = 0f; private var nvx = 0f; private var nvy = 0f
    private var nrot = 0f; private var nrotV = 0f
    private var nsq = 0f; private var nsqV = 0f; private var nsqA = 0f
    private var kick = 0f
    // the ring recoiling from a hit
    private var rkx = 0f; private var rky = 0f; private var rkvx = 0f; private var rkvy = 0f
    private var holeT = 0f
    // the floating "+40 s" (seconds since the last tap, -1 when not showing)
    private var flT = -1f
    private var job: Job? = null

    // timed on the frames themselves, so taps and motion share one clock
    override fun since() = time - tapAt
    fun inHole() = Hole.isActive && Hole.spin === this

    fun tap() {
        val moving = job?.isActive == true
        combo = if (moving && since() < 0.9f) combo + 1 else 1
        tapAt = time
        if (!spinning) {
            // it spins only once the head has really come all the way round, on the next tap
            if (dir * anchor >= 360f && dir * pos >= dir * anchor - 10f) spinning = true
            else {
                target += dir * 60f
                anchor = if (dir > 0) max(anchor, floor(target / 360f) * 360f) else min(anchor, ceil(target / 360f) * 360f)
            }
        }
        settling = false
        flT = 0f
        if (combo * 10 >= HOLE && spinning) Hole.onTap(this)
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var last = -1L
            try {
                while (true) {
                    val alive = withFrameNanos { n ->
                        val dt = if (last < 0) 0f else ((n - last) / 1e9f).coerceAtMost(0.05f)
                        last = n
                        step(dt).also { frame++ }
                    }
                    if (!alive) break
                }
            } finally {
                Hole.preShake(this@SkipSpin, 0f)
            }
        }
    }

    private fun step(dt: Float): Boolean {
        time += dt
        val since = since()
        val tapping = since < 0.9f
        when {
            spinning && tapping -> target += dir * (360f / 0.7f) * (1f + 1.3f * c) * dt
            spinning -> {
                spinning = false
                target = if (dir > 0) ceil((target + 1f) / 360f) * 360f else floor((target - 1f) / 360f) * 360f
                anchor = target
                if (c > 0.01f) { settling = true; cPeak = c }
                Hole.onRelease(this)
            }
            since > 0.12f -> target = if (dir > 0) max(anchor, target - 80f * dt) else min(anchor, target + 80f * dt)
        }
        c = if (spinning && combo * 10 >= CHAOS) min(1f, c + dt * 0.9f) else max(0f, c - dt * (if (settling) 0.75f else 2f))
        if (c <= 0.001f) settling = false
        // closing in on +2000 s the phone starts to tremble; at +2000 s the hole opens
        Hole.preShake(this, if (spinning) smooth(1700f, HOLE.toFloat(), combo * 10f) else 0f)
        if (spinning && combo * 10 >= HOLE) { if (holeT == 0f) Hole.onBreak(this); holeT += dt } else if (!spinning) holeT = 0f

        // the arrow follows its target on a lively spring
        val n = max(1, ceil(dt / 0.004f).toInt()); val h = dt / n
        val k = 260f; val damp = 2f * 0.62f * sqrt(k)
        repeat(n) { vel += (-k * (pos - target) - damp * vel) * h; pos += vel * h }

        // a top wobbles slowly; a settling coin wobbles faster and faster as it flattens
        val wr = if (settling) 9f + 30f * (1f - c / max(cPeak, 0.01f)) else 5f + 4f * c
        phi = (phi + wr * dt * 360f) % 360f
        tilt = 0.42f * c * (if (settling) 1f else 0.75f + 0.25f * sin(time * 7f))
        jx = c * 0.05f * sin(time * 31f); jy = c * 0.05f * cos(time * 27f)

        // the head lags behind, then clicks back; the number sways
        val lagT = c * (55f + 25f * sin(time * 5f))
        val dzT = c * sin(time * 9f) * 16f
        repeat(n) {
            lagV += (-420f * (lag - lagT) - 2f * 0.35f * sqrt(420f) * lagV) * h; lag += lagV * h
            dizzyV += (-300f * (dizzy - dzT) - 2f * 0.25f * sqrt(300f) * dizzyV) * h; dizzy += dizzyV * h
        }
        ghost = c * (abs(vel) / 900f).coerceIn(0f, 1f)

        // the "10" breaks loose and smashes into the arrow's walls; when you stop it swims back
        // to the middle and rights itself
        repeat(n) {
            if (c > 0.04f && !settling) {
                val sw = 6f * c
                nvx += (-ny * dir * sw + nx * 3f * c) * h
                nvy += (nx * dir * sw + ny * 3f * c) * h
                kick -= h
                if (kick <= 0f) {
                    kick = 0.14f + Random.nextFloat() * 0.22f
                    val a = Random.nextFloat() * TAU
                    val kk = 1.1f * c
                    nvx += cos(a) * kk; nvy += sin(a) * kk
                    nrotV += (Random.nextFloat() - 0.5f) * 900f * c
                }
                nrotV += dir * 240f * c * h
                nrotV *= exp(-0.6f * h)
            } else {
                nvx += (-55f * nx - 2f * 0.62f * sqrt(55f) * nvx) * h
                nvy += (-55f * ny - 2f * 0.62f * sqrt(55f) * nvy) * h
                val up = (nrot / 360f).roundToInt() * 360f
                nrotV += (-90f * (nrot - up) - 2f * 0.42f * sqrt(90f) * nrotV) * h
            }
            nx += nvx * h; ny += nvy * h; nrot += nrotV * h
            val l = hypot(nx, ny)
            if (l > WALL) {
                val ux = nx / l; val uy = ny / l
                val vn = nvx * ux + nvy * uy
                nx = ux * WALL; ny = uy * WALL
                if (vn > 0f) {
                    val vt = -nvx * uy + nvy * ux
                    nvx -= 1.8f * vn * ux; nvy -= 1.8f * vn * uy
                    nrotV += vt * 420f
                    if (vn > 0.2f) {
                        nsq = min(0.4f, 0.12f + vn * 0.14f); nsqA = atan2(uy, ux)
                        rkvx += ux * vn * 0.55f; rkvy += uy * vn * 0.55f
                    }
                }
            }
            nsqV += (-520f * nsq - 2f * 0.28f * sqrt(520f) * nsqV) * h; nsq += nsqV * h
            rkvx += (-500f * rkx - 2f * 0.45f * sqrt(500f) * rkvx) * h; rkx += rkvx * h
            rkvy += (-500f * rky - 2f * 0.45f * sqrt(500f) * rkvy) * h; rky += rkvy * h
        }
        if (flT >= 0f) { flT += dt; if (flT > 0.75f) flT = -1f }

        // at rest: a full turn looks the same as none, so the next combo starts from the beginning
        val rest = !spinning && since > 1f && abs(pos - target) < 0.2f && abs(vel) < 0.5f && target == anchor &&
            c <= 0.001f && abs(lag) < 0.05f && abs(dizzy) < 0.05f && abs(lagV) < 0.5f && abs(dizzyV) < 0.5f &&
            hypot(nx, ny) < 0.003f && abs(nrotV) < 1f && flT < 0f && !inHole()
        if (rest) {
            pos = 0f; vel = 0f; target = 0f; anchor = 0f; tapAt = time - 10f
            lag = 0f; lagV = 0f; dizzy = 0f; dizzyV = 0f; tilt = 0f; ghost = 0f; jx = 0f; jy = 0f
            nx = 0f; ny = 0f; nvx = 0f; nvy = 0f; nrot = 0f; nrotV = 0f; nsq = 0f; nsqV = 0f
            rkx = 0f; rky = 0f; rkvx = 0f; rkvy = 0f
            return false
        }
        return true
    }

    // ---- drawing (shared with the hole, which draws this button while it's the hole) --------------

    private val head = Path()

    private fun DrawScope.ring(cx: Float, cy: Float, w: Float, rot: Float, alpha: Float, withHead: Boolean, tint: Color, lagDeg: Float, lift: Float) {
        val r = w * R
        val a = w * A
        rotate(rot, Offset(cx, cy)) {
            val mirror = dir > 0
            withTransform({ if (mirror) scale(-1f, 1f, Offset(cx, cy)) }) {
                drawArc(
                    color = tint.copy(alpha = tint.alpha * alpha), startAngle = -90f + 8f, sweepAngle = 292f, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = w * LW, cap = StrokeCap.Round),
                )
                if (withHead) rotate(lagDeg, Offset(cx, cy)) {
                    translate(0f, -lift * w) {
                        head.reset()
                        head.moveTo(cx - a * 0.95f, cy - r)
                        head.lineTo(cx + a * 0.25f, cy - r - a * 0.8f)
                        head.lineTo(cx + a * 0.25f, cy - r + a * 0.8f)
                        head.close()
                        drawPath(head, tint.copy(alpha = tint.alpha * alpha))
                    }
                }
            }
        }
    }

    /** The icon as it is right now: the wobbling ring and the loose "10". */
    fun drawIcon(d: DrawScope, at: Offset, w: Float, tint: Color) = with(d) {
        val cx = at.x; val cy = at.y
        withTransform({
            translate(rkx * w, rky * w)
            // the wobble of a top losing its balance: squashed along a direction that turns
            if (c > 0.001f) {
                translate(jx * w, jy * w)
                rotate(phi, at); scale(1f, 1f - tilt, at); rotate(-phi, at)
            }
        }) {
            if (ghost > 0.01f) {
                ring(cx, cy, w, pos - dir * 28f, ghost * 0.18f, false, tint, 0f, 0f)
                ring(cx, cy, w, pos - dir * 14f, ghost * 0.4f, false, tint, 0f, 0f)
            }
            ring(cx, cy, w, pos, 1f, true, tint, lag, c * 0.04f)
        }
        drawTen(this, Offset(cx + nx * w, cy + ny * w), w, tint, rot = nrot + dizzy, squash = nsq, squashAngle = nsqA, dizzyScale = 1f - abs(dizzy) / 160f)
    }

    /** Just the arrow, steady (as it shrinks into the hole and comes back out of it). */
    fun drawPlain(d: DrawScope, at: Offset, w: Float, tint: Color, alpha: Float) = with(d) {
        if (alpha <= 0.003f) return@with
        ring(at.x, at.y, w, pos, alpha, true, tint, 0f, 0f)
    }

    /** The "10": [rot] in degrees; squashed along [squashAngle] (radians) on a hit. */
    fun drawTen(
        d: DrawScope, at: Offset, w: Float, tint: Color,
        rot: Float = 0f, squash: Float = 0f, squashAngle: Float = 0f, dizzyScale: Float = 1f, scale: Float = 1f, alpha: Float = 1f,
        glow: Color? = null, glowR: Float = 0f,
    ) = with(d) {
        val t = measurer.measure(
            "10",
            TextStyle(fontSize = (w * 0.30f).toSp(), fontWeight = FontWeight.Bold, letterSpacing = (-0.04).em, fontFeatureSettings = "tnum"),
        )
        withTransform({
            translate(at.x, at.y)
            if (squash > 0.002f) {
                val deg = squashAngle * 180f / PI.toFloat()
                rotate(deg, Offset.Zero); scale(1f - squash, 1f + squash * 0.6f, Offset.Zero); rotate(-deg, Offset.Zero)
            }
            rotate(rot, Offset.Zero)
            val k = dizzyScale * scale
            scale(k, k, Offset.Zero)
        }) {
            val tl = Offset(-t.size.width / 2f, -t.size.height / 2f + w * 0.01f)
            if (glow != null && glowR > 0.5f) {
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow.copy(alpha = glow.alpha * 0.55f * alpha), Color.Transparent), Offset.Zero, glowR * 2.2f),
                    radius = glowR * 2.2f, center = Offset.Zero,
                )
            }
            drawText(t, color = tint, topLeft = tl, alpha = alpha)
        }
    }

    /** "+40 s", floating up from [at] for 0.75 s after each tap. */
    fun drawCounter(d: DrawScope, at: Offset) = with(d) {
        if (flT < 0f) return@with
        val u = flT / 0.75f
        val a = if (u < 0.2f) u / 0.2f else max(0f, 1f - (u - 0.2f) / 0.8f)
        val t = measurer.measure(
            (if (dir > 0) "+" else "−") + total + " s",
            TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        )
        val x = at.x + c * 5.dp.toPx() * sin(time * 40f) - t.size.width / 2f
        val y = at.y - 36.dp.toPx() - u * 22.dp.toPx() - t.size.height / 2f
        drawText(t, color = Color.White, topLeft = Offset(x, y), alpha = a)
    }

    companion object {
        const val CHAOS = 360
        const val HOLE = 2000
        private const val R = 0.40f   // the ring, in icon widths
        private const val LW = 0.075f // its stroke
        private const val A = 0.13f   // the arrowhead
        private const val WALL = 0.235f // how far the loose "10" can go before it hits the ring
        private const val TAU = (2 * PI).toFloat()
        fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    }
}
