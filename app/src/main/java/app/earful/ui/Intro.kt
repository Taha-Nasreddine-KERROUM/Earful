package app.earful.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.earful.ui.components.Text
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Where the Home logo is, so the opening can land on it, and whether the opening still covers it
 * (Home hides its own logo until the moving one has arrived).
 */
object Intro {
    var covering by mutableStateOf(false)
    /** Top-left of the Home logo (its bars and word), in the window. */
    var anchor: Offset? = null

    /** The logo's five bars: heights in dp (3 dp wide, 2 dp apart), then 8 dp, then "Earful". */
    val BARS = listOf(7, 13, 18, 11, 6)
}

/**
 * The app opening. On Android 12+ the system shows the five bars first (splash_mark, centred and
 * three times the logo's size); this picks up from exactly there. The bars take one slow breath,
 * "Earful" slides out from behind them, and the logo glides up into its place at the top of Home
 * as Home appears around it. A tap skips straight to the glide.
 */
@Composable
fun OpeningLogo(onDone: () -> Unit) {
    val p = LocalPalette.current
    val big = 3f
    val bars = remember { List(5) { Animatable(1f) } }
    val markIn = remember { Animatable(if (Build.VERSION.SDK_INT >= 31) 1f else 0f) }
    val unfold = remember { Animatable(0f) }
    val glide = remember { Animatable(0f) }
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    var markWidth by remember { mutableStateOf(0) }
    var skip by remember { mutableStateOf(false) }
    val easeInOut = remember { CubicBezierEasing(0.65f, 0f, 0.35f, 1f) }

    LaunchedEffect(Unit) { Intro.covering = true }
    LaunchedEffect(skip) {
        if (skip) {
            // straight to the end of the breath and the unfold, then the glide
            bars.forEach { launch { it.animateTo(1f, tween(160)) } }
            markIn.snapTo(1f)
            unfold.animateTo(1f, tween(220, easing = easeInOut))
            glide.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 320f))
            Intro.covering = false
            onDone()
            return@LaunchedEffect
        }
        markIn.animateTo(1f, tween(220))
        // one breath: each bar swells a moment after the one before it, then sinks back
        coroutineScope {
            bars.forEachIndexed { i, b ->
                launch {
                    delay(i * 70L)
                    val peak = 1.55f - abs(i - 2) * 0.06f
                    b.animateTo(peak, tween(420, easing = easeInOut))
                    b.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 150f))
                }
            }
        }
        // the word slides out from behind the bars
        unfold.animateTo(1f, tween(550, easing = easeInOut))
        delay(200)
        // into the Home logo's place; Home appears around it
        glide.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 200f))
        Intro.covering = false
        onDone()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Skip") { skip = true }
    ) {
        val density = LocalDensity.current
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        // the page behind fades in as the logo travels
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - ((glide.value * 1.6f) - 0.3f).coerceIn(0f, 1f) }.background(p.bg))

        // The logo, laid out exactly like Home's, then moved and scaled as a whole.
        Row(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                .onSizeChanged { rowSize = it }
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    val rw = rowSize.width.toFloat(); val rh = rowSize.height.toFloat()
                    val mw = markWidth.toFloat()
                    val u = unfold.value; val g = glide.value
                    // alone: the bars centred; unfolded: bars and word centred together
                    val cx = W / 2f; val cy = H / 2f
                    val home = Intro.anchor
                    // (with very large text the word could run off the screen: then it opens a little smaller)
                    val fit = if (rw > 0f) minOf(big, W * 0.86f / rw) else big
                    val sBig = lerp(big, fit, u)
                    val x0 = lerp(cx - big * mw / 2f, cx - sBig * rw / 2f, u)
                    val y0 = cy - sBig * rh / 2f
                    val s = lerp(sBig, 1f, if (home != null) g else 0f)
                    scaleX = s; scaleY = s
                    translationX = if (home != null) lerp(x0, home.x, g) else x0
                    translationY = if (home != null) lerp(y0, home.y, g) else y0
                    // (no Home logo to land on: just fade away)
                    alpha = if (home == null) 1f - g else 1f
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.onSizeChanged { markWidth = it.width }.graphicsLayer { alpha = markIn.value },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Intro.BARS.forEachIndexed { i, h ->
                    Box(
                        Modifier.width(3.dp).height(h.dp).graphicsLayer { scaleY = bars[i].value }
                            .clip(RoundedCornerShape(2.dp)).background(p.accent)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Earful", Type.title2,
                modifier = Modifier
                    .graphicsLayer { translationX = -(1f - unfold.value) * with(density) { 8.dp.toPx() } }
                    .drawWithContent {
                        val e = unfold.value
                        if (e > 0f) clipRect(right = size.width * e) { this@drawWithContent.drawContent() }
                    },
            )
        }
    }
}

