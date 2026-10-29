package app.earful.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.earful.ui.theme.Type
import app.earful.ui.components.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ±10 s. Every tap turns the arrow 60° the way time moves, so six taps (a minute) bring it all
 * the way round. Between taps it slowly turns back, never below a full turn it has already made.
 * Once it has come full circle, the next tap sets it spinning for as long as you keep tapping;
 * when you stop it rolls on to the next full turn and rests. Past +360 s it loses control: it
 * spins faster, blurs, wobbles like a top, the arrowhead slips back along the circle and the "10"
 * gets dizzy. It settles like a coin spun on a table, then everything clicks back into place.
 */
@Composable
internal fun SkipButton(forward: Boolean, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    val spin = remember { SkipSpin(if (forward) 1f else -1f) }
    val float = remember { Animatable(0f) }
    var total by remember { mutableIntStateOf(0) }
    Box(contentAlignment = Alignment.Center) {
        CircleButton(
            onClick = {
                onSkip()
                total = spin.tap()
                spin.start(scope)
                scope.launch { float.snapTo(0f); float.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
            },
            size = 72.dp,
            label = if (forward) "Forward 10 seconds" else "Back 10 seconds",
        ) {
            SpinningSkipIcon(spin, forward, Color.White, 38.dp)
        }
        if (float.value in 0.001f..0.999f) {
            Text(
                (if (forward) "+" else "−") + total + " s",
                Type.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                    spin.frame
                    translationY = -float.value * 22.dp.toPx()
                    translationX = spin.c * 5.dp.toPx() * sin(spin.time * 40f)
                    alpha = if (float.value < 0.2f) float.value / 0.2f else 1f - (float.value - 0.2f) / 0.8f
                },
            )
        }
    }
}

/** The app's skip icon (same drawing as [app.earful.ui.components.SkipIcon]), but with its parts free to move. */
@Composable
private fun SpinningSkipIcon(s: SkipSpin, forward: Boolean, tint: Color, size: Dp) {
    val numberSize = with(LocalDensity.current) { (size * 0.30f).toSp() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            s.frame // redraw on every step of the motion
            val w = this.size.minDimension
            val c = Offset(w / 2, w / 2)
            val stroke = w * 0.075f
            val r = w * 0.40f
            val a = w * 0.13f
            fun DrawScope.arc(alpha: Float) = drawArc(
                color = tint.copy(alpha = tint.alpha * alpha), startAngle = -90f + 8f, sweepAngle = 292f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            fun DrawScope.mirrored(block: DrawScope.() -> Unit) = if (forward) scale(-1f, 1f, pivot = c) { block() } else block()
            withTransform({
                // the wobble of a top losing its balance: squashed along a direction that turns
                if (s.c > 0.001f) {
                    translate(s.jx * w, s.jy * w)
                    rotate(s.phi, c); scale(1f, 1f - s.tilt, c); rotate(-s.phi, c)
                }
            }) {
                // motion blur behind a fast arrow
                if (s.ghost > 0.01f) {
                    rotate(s.pos - s.dir * 28f, c) { mirrored { arc(s.ghost * 0.18f) } }
                    rotate(s.pos - s.dir * 14f, c) { mirrored { arc(s.ghost * 0.4f) } }
                }
                rotate(s.pos, c) {
                    mirrored {
                        arc(1f)
                        // the arrowhead: slips back along the circle when it can't keep up
                        rotate(s.lag, c) {
                            translate(0f, -s.c * 0.04f * w) {
                                val tip = Offset(c.x - a * 0.95f, c.y - r)
                                val head = Path().apply {
                                    moveTo(tip.x, tip.y)
                                    lineTo(c.x + a * 0.25f, c.y - r - a * 0.8f)
                                    lineTo(c.x + a * 0.25f, c.y - r + a * 0.8f)
                                    close()
                                }
                                drawPath(head, tint)
                            }
                        }
                    }
                }
            }
        }
        // the number stays upright; it only sways when it's dizzy
        BasicText(
            text = "10",
            style = TextStyle(
                color = tint, fontSize = numberSize, fontWeight = FontWeight.Bold,
                letterSpacing = (-0.04).em, fontFeatureSettings = "tnum",
            ),
            modifier = Modifier.graphicsLayer {
                s.frame
                rotationZ = s.dizzy
                val k = 1f - abs(s.dizzy) / 160f
                scaleX = k; scaleY = k
            },
        )
    }
}

/** The motion of one skip button (plain numbers, stepped once per frame; [frame] tells the drawing). */
private class SkipSpin(val dir: Float) {
    var frame by mutableIntStateOf(0)

    var pos = 0f; private var vel = 0f
    private var target = 0f; private var anchor = 0f
    private var tapAt = -10f; private var combo = 0
    private var spinning = false
    // losing control (0..1) and how it shows
    var c = 0f; private var cPeak = 0f; private var settling = false
    var phi = 0f; var tilt = 0f; var jx = 0f; var jy = 0f
    var lag = 0f; private var lagV = 0f
    var dizzy = 0f; private var dizzyV = 0f
    var ghost = 0f
    var time = 0f
    private var job: Job? = null

    // timed on the frames themselves, so taps and motion share one clock
    private fun since() = time - tapAt

    /** Returns the combo's running total in seconds. */
    fun tap(): Int {
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
        return combo * 10
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var last = -1L
            while (true) {
                val alive = withFrameNanos { n ->
                    val dt = if (last < 0) 0f else ((n - last) / 1e9f).coerceAtMost(0.05f)
                    last = n
                    step(dt).also { frame++ }
                }
                if (!alive) break
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
            }
            since > 0.12f -> target = if (dir > 0) max(anchor, target - 80f * dt) else min(anchor, target + 80f * dt)
        }
        c = if (spinning && combo * 10 >= 360) min(1f, c + dt * 0.9f) else max(0f, c - dt * (if (settling) 0.75f else 2f))
        if (c <= 0.001f) settling = false

        // the arrow follows its target on a lively spring
        val n = max(1, ceil(dt / 0.004f).toInt()); val h = dt / n
        val k = 260f; val damp = 2f * 0.62f * sqrt(k)
        repeat(n) { vel += (-k * (pos - target) - damp * vel) * h; pos += vel * h }

        // a top wobbles slowly; a settling coin wobbles faster and faster as it flattens
        val wr = if (settling) 9f + 30f * (1f - c / max(cPeak, 0.01f)) else 5f + 4f * c
        phi = (phi + wr * dt * 360f) % 360f
        tilt = 0.42f * c * (if (settling) 1f else 0.75f + 0.25f * sin(time * 7f))
        jx = c * 0.06f * sin(time * 31f); jy = c * 0.06f * cos(time * 27f)

        // the head lags behind, then clicks back; the number sways
        val lagT = c * (55f + 25f * sin(time * 5f))
        val dzT = c * sin(time * 9f) * 16f
        repeat(n) {
            lagV += (-420f * (lag - lagT) - 2f * 0.35f * sqrt(420f) * lagV) * h; lag += lagV * h
            dizzyV += (-300f * (dizzy - dzT) - 2f * 0.25f * sqrt(300f) * dizzyV) * h; dizzy += dizzyV * h
        }
        ghost = c * (abs(vel) / 900f).coerceIn(0f, 1f)

        // at rest: a full turn looks the same as none, so the next combo starts from the beginning
        val rest = !spinning && since > 1f && abs(pos - target) < 0.2f && abs(vel) < 0.5f && target == anchor &&
            c <= 0.001f && abs(lag) < 0.05f && abs(dizzy) < 0.05f && abs(lagV) < 0.5f && abs(dizzyV) < 0.5f
        if (rest) {
            pos = 0f; vel = 0f; target = 0f; anchor = 0f; tapAt = time - 10f
            lag = 0f; lagV = 0f; dizzy = 0f; dizzyV = 0f; tilt = 0f; ghost = 0f; jx = 0f; jy = 0f
            return false
        }
        return true
    }
}

