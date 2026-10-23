package app.earful.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.earful.ui.components.Haptics
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.SkipIcon
import app.earful.ui.components.Text
import app.earful.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** Where on the picture a double-tap landed. */
private enum class Zone(val seconds: Int) {
    LeftTop(-10), LeftBottom(-5), Center(0), RightTop(10), RightBottom(5);
    val isLeft get() = this == LeftTop || this == LeftBottom
    val isRight get() = this == RightTop || this == RightBottom
}

private fun zoneOf(p: Offset, size: IntSize): Zone {
    val x = p.x / size.width
    val top = p.y < size.height / 2f
    return when {
        x < 0.36f -> if (top) Zone.LeftTop else Zone.LeftBottom
        x > 0.64f -> if (top) Zone.RightTop else Zone.RightBottom
        else -> Zone.Center
    }
}

private class Burst(val zone: Zone, val total: Int, val at: Offset, val id: Int)

/**
 * The picture is the remote:
 *  • double-tap top-left −10 s, bottom-left −5 s, top-right +10 s, bottom-right +5 s
 *  • double-tap the middle to play/pause
 *  • after a double-tap, keep tapping the same side to keep skipping (no need to double again)
 * Every skip gets a ripple, a running total and a light haptic tick, so you never wonder
 * whether it registered.
 */
@Composable
fun ArtworkGestures(
    enabled: Boolean,
    playing: Boolean,
    onSeek: (seconds: Int) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = false,
    onHintShown: () -> Unit = {},
) {
    val view = LocalView.current
    var burst by remember { mutableStateOf<Burst?>(null) }
    var toggleId by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(0) }
    var streakZone by remember { mutableStateOf<Zone?>(null) }
    var streakTime by remember { mutableStateOf(0L) }

    fun fire(zone: Zone, at: Offset, now: Long) {
        Haptics.tick(view)
        if (zone == Zone.Center) {
            onToggle(); toggleId++; streakZone = null; return
        }
        onSeek(zone.seconds)
        val prev = burst
        val sameSide = prev != null && streakZone != null && now - streakTime < 900 &&
            ((prev.zone.isLeft && zone.isLeft) || (prev.zone.isRight && zone.isRight))
        burst = Burst(zone, if (sameSide) prev!!.total + zone.seconds else zone.seconds, at, ++nextId)
        streakZone = zone; streakTime = now
    }

    // When disabled there is no pointer handler at all, so touches fall through to what's below.
    val gestures = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                val zone = zoneOf(up.position, size)
                val sz = streakZone
                if (sz != null && zone != Zone.Center && up.uptimeMillis - streakTime < 650 &&
                    ((sz.isLeft && zone.isLeft) || (sz.isRight && zone.isRight))
                ) {
                    fire(zone, up.position, up.uptimeMillis) // YouTube-style continued tapping
                    return@awaitEachGesture
                }
                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    ?: return@awaitEachGesture
                if (hypot(second.position.x - up.position.x, second.position.y - up.position.y) > 120.dp.toPx()) {
                    return@awaitEachGesture
                }
                val up2 = waitForUpOrCancellation() ?: return@awaitEachGesture
                fire(zoneOf(up2.position, size), up2.position, up2.uptimeMillis)
            }
        }
    BoxWithConstraints(modifier.then(gestures)) {
        // --- skip feedback: ripple from the finger + running total on that side ----------------
        burst?.let { b -> SkipFeedback(b) { if (burst?.id == b.id) burst = null } }

        // --- play/pause feedback in the middle -----------------------------------------------
        if (toggleId > 0) CenterFeedback(toggleId, playing)

        if (showHint) GestureHint(onHintShown)
    }
}

@Composable
private fun SkipFeedback(b: Burst, onDone: () -> Unit) {
    val ripple = remember(b.id) { Animatable(0f) }
    val label = remember(b.zone.isLeft) { Animatable(0f) }
    LaunchedEffect(b.id) {
        launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        label.animateTo(1f, tween(120))
        kotlinx.coroutines.delay(650)
        label.animateTo(0f, tween(260))
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.maxDimension * 0.55f * ripple.value
            drawCircle(Color.White.copy(alpha = 0.28f * (1f - ripple.value)), radius = r, center = b.at)
        }
        val top = b.zone == Zone.LeftTop || b.zone == Zone.RightTop
        Box(
            Modifier.fillMaxHeight(0.5f).fillMaxWidth(0.5f)
                .align(
                    when {
                        b.zone.isLeft && top -> Alignment.TopStart
                        b.zone.isLeft -> Alignment.BottomStart
                        top -> Alignment.TopEnd
                        else -> Alignment.BottomEnd
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .graphicsLayer { alpha = label.value; scaleX = 0.9f + 0.1f * label.value; scaleY = scaleX }
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkipIcon(abs(b.zone.seconds), forward = b.total > 0, tint = Color.White, size = 20.dp)
                Text(
                    (if (b.total > 0) "+" else "−") + abs(b.total) + " s",
                    Type.subheadStrong.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CenterFeedback(id: Int, playing: Boolean) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(id) {
        a.snapTo(1f)
        kotlinx.coroutines.delay(260)
        a.animateTo(0f, tween(380))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .graphicsLayer { alpha = a.value; scaleX = 1.15f - 0.15f * a.value; scaleY = scaleX }
                .size(64.dp).clip(CircleShape).background(Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Play else Icons.Pause, Color.White, size = 30.dp)
        }
    }
}

/** Shown once, the first time the player opens: quietly teaches the five zones, then fades. */
@Composable
private fun GestureHint(onShown: () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        a.animateTo(1f, tween(400))
        kotlinx.coroutines.delay(2600)
        a.animateTo(0f, tween(500))
        onShown()
    }
    Column(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(Color(0x8C000000))) {
        listOf(-10 to 10, -5 to 5).forEach { (l, r) ->
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HintCell(l, Modifier.weight(1f))
                Box(Modifier.weight(0.8f))
                HintCell(r, Modifier.weight(1f))
            }
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Pause, Color.White, size = 22.dp)
            Text("Double‑tap", Type.caption, color = Color(0xCCFFFFFF), modifier = Modifier.offset(y = 4.dp))
        }
    }
}

@Composable
private fun HintCell(seconds: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SkipIcon(abs(seconds), forward = seconds > 0, tint = Color.White, size = 22.dp)
        }
    }
}
