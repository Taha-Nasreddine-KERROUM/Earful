package app.earful.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.withFrameNanos

/**
 * Moves [ride] (px above the bottom edge) to [target] the way the mini player rides the keyboard:
 * along the keyboard's own curve and timing, and as one physical motion. Going up it rests on
 * the keyboard like a weight on a lift (pushed up with it; when the keyboard brakes it flies on a
 * little by its own momentum and settles back). Going down it rides the keyboard until it's nearly
 * down, then drops the last bit by its own momentum and dips a little below its place before
 * settling. So it never stops before the bounce.
 */
internal suspend fun rideKeyboard(ride: MutableFloatState, target: Float) {
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val from = ride.floatValue
    val dist = target - from
    if (kotlin.math.abs(dist) < 1f) { ride.floatValue = target; return }
    val up = dist > 0f
    val dur = 0.285f // Android's keyboard animation
    fun kb(t: Float) = from + dist * ease.transform((t / dur).coerceIn(0f, 1f))
    val k = 700f
    val c = 2f * 0.45f * kotlin.math.sqrt(k)
    var t = 0f
    var q = 0f; var qv = 0f              // going up: how far it has flown off the keyboard
    var free = false; var x = from; var v = 0f // going down: on its own for the last bit
    var last = 0L
    while (true) {
        val settled = withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            var left = dt
            while (left > 0f) {
                val h = kotlin.math.min(0.001f, left); left -= h
                if (up) {
                    val e = 0.002f
                    val a = (kb(t + 2 * e) - 2 * kb(t + e) + kb(t)) / (e * e)
                    val push = if (a < 0f) -a else 0f // the keyboard braking: it flies on
                    qv += (-k * q - c * qv + push) * h
                    q += qv * h
                    if (q < 0f) { q = 0f; if (qv < 0f) qv = 0f } // never under the keyboard
                } else {
                    if (!free && kb(t) - target <= 0.1f * (from - target)) {
                        free = true; x = kb(t); v = (kb(t + h) - kb(t)) / h
                    }
                    if (free) { v += (-k * (x - target) - c * v) * h; x += v * h }
                }
                t += h
            }
            ride.floatValue = when { up -> kb(t) + q; free -> x; else -> kb(t) }
            if (up) t >= dur && kotlin.math.abs(q) < 0.3f && kotlin.math.abs(qv) < 3f
            else free && kotlin.math.abs(x - target) < 0.3f && kotlin.math.abs(v) < 3f
        }
        if (settled) { ride.floatValue = target; break }
    }
}
