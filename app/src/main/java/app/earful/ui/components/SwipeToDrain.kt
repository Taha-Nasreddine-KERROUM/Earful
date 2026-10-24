package app.earful.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.earful.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val Red = Color(0xFFFF3B30)

/**
 * Swipe a row to the left to remove it, the same way as in Up next ("Drain"): red liquid follows
 * the finger in from the right with a rippling edge; let go past the line (or flick) and the red
 * floods the row, then drains down out of sight while the rows below close the gap.
 *
 * [background] must be opaque: the row slides over the red.
 */
@Composable
fun SwipeToDrain(
    onRemove: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val unit = LocalDensity.current.density
    val remove by rememberUpdatedState(onRemove)
    val dx = remember { Animatable(0f) }
    val drain = remember { Animatable(0f) }   // 0..1 — the red going down the drain, the row closing
    var fingerV by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var stirring by remember { mutableStateOf(false) }
    val rowW = remember { floatArrayOf(1080f) }

    // The red's edge ripples while it moves (frames only then).
    LaunchedEffect(stirring) {
        if (!stirring) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) phase += (now - last) / 1e9f * 7f
            last = now
        }
    }

    suspend fun drainAway() {
        dx.animateTo(-(rowW[0] + 6f * unit) / 0.86f - 2f * unit, tween(260, easing = Motion.inOutCubic))
        drain.animateTo(1f, tween(450, easing = Motion.inOutCubic))
        stirring = false
        remove()
        // Still here a moment later (nothing removed it): bring it back.
        delay(1500)
        drain.snapTo(0f)
        dx.animateTo(0f, Motion.rowBack())
    }

    Box(
        modifier.fillMaxWidth()
            // As the red drains, the row closes and the rows below slide up over it.
            .layout { m, c ->
                val pl = m.measure(c)
                rowW[0] = pl.width.toFloat()
                layout(pl.width, (pl.height * (1f - drain.value)).roundToInt()) { pl.place(0, 0) }
            }
            // (on the row's still frame, not on the moving row, so its own movement doesn't
            // cancel out the finger's)
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var crossed = false
                var finger = 0f
                val give = 200.dp.toPx()
                val commit = 80.dp.toPx()
                // Follows the finger, then gets heavy past 200 dp.
                fun shown(f: Float) = if (f < -give) -give + (f + give) * 0.3f else f
                // Only a swipe to the left is ours; a swipe to the right is left to the pages
                // (back to Home).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var over0 = 0f
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                        if (over < 0f || dx.value < -0.5f) { change.consume(); over0 = over }
                    } ?: return@awaitEachGesture
                    tracker.resetTracking()
                    val d = dx.value
                    finger = if (d < -give) -give + (d + give) / 0.3f else d
                    stirring = true
                    fun follow(change: androidx.compose.ui.input.pointer.PointerInputChange, amount: Float) {
                        tracker.addPosition(change.uptimeMillis, change.position)
                        fingerV = fingerV * 0.6f + tracker.calculateVelocity().x * 0.4f
                        finger = (finger + amount).coerceAtMost(0f)
                        val next = shown(finger)
                        val past = next < -commit
                        if (past != crossed) { crossed = past; if (past) Haptics.threshold(view) }
                        scope.launch { dx.snapTo(next) }
                    }
                    follow(start, over0)
                    val ended = horizontalDrag(start.id) { change ->
                        val amount = change.positionChange().x // (read before it's marked as used)
                        change.consume()
                        follow(change, amount)
                    }
                    val v = tracker.calculateVelocity().x
                    fingerV = 0f
                    crossed = false
                    scope.launch {
                        if (ended && (dx.value < -commit || v < -650.dp.toPx())) drainAway()
                        else { dx.animateTo(0f, Motion.rowBack(), if (ended) v else 0f); stirring = false }
                    }
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val d = drain.value
            if (dx.value > -0.5f && d == 0f) return@Canvas
            val red = Path()
            if (d == 0f) {
                val v = if (stirring && fingerV != 0f) fingerV else dx.velocity
                val amp = (2.2f + (abs(v) / unit * 0.006f).coerceAtMost(7f)) * unit
                val edge = w + 6f * unit + dx.value * 0.86f
                red.moveTo(w, 0f)
                var y = 0f
                while (y <= h) {
                    red.lineTo(edge + amp * sin(y / h * 2f * PI.toFloat() + phase), y)
                    y += 4f * unit
                }
                red.lineTo(w, h)
                red.close()
            } else {
                // draining: the surface goes down, rippling less and less
                red.moveTo(-4f, h + 4f)
                var x = -4f
                while (x <= w + 4f) {
                    red.lineTo(x, h * d + 3f * unit * (1f - d) * sin(x / (26f * unit) + d * 12f))
                    x += 8f * unit
                }
                red.lineTo(w + 4f, h + 4f)
                red.close()
            }
            drawPath(red, Red)
        }
        Box(Modifier.matchParentSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Trash, Color.White, size = 22.dp, contentDescription = "Remove",
                modifier = Modifier.graphicsLayer {
                    val k = (-dx.value / 70.dp.toPx()).coerceIn(0f, 1f)
                    alpha = k * (1f - drain.value * 1.6f).coerceIn(0f, 1f)
                    val s = 0.5f + 0.5f * k
                    scaleX = s; scaleY = s
                })
        }
        Box(
            Modifier
                .graphicsLayer { translationX = dx.value; alpha = if (drain.value > 0f) 0f else 1f }
                .background(background)
        ) { content() }
    }
}

/**
 * Makes this surface catch every touch that lands on it (its own buttons still work), so a tap on
 * an empty part of a bar or a screen never reaches whatever is behind it.
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}
