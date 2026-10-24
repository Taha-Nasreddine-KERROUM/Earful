package app.earful.ui.player

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.earful.data.Library
import app.earful.playback.AudioCache
import app.earful.playback.NowPlaying
import app.earful.playback.PlaybackEvents
import app.earful.playback.PlayerConnection
import app.earful.ui.components.EdgeTide
import app.earful.ui.components.Haptics
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.RemoteImage
import app.earful.ui.components.SaveButton
import app.earful.ui.components.SkipIcon
import app.earful.ui.components.Text
import app.earful.ui.components.TideShape
import app.earful.ui.components.Water
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** 0 = mini player docked at the bottom, 1 = full Now Playing. */
@Stable
class SheetState {
    val progress = Animatable(0f)
    /** px the mini player is pulled down, or sinking, below its place. */
    val dismiss = Animatable(0f)
    /** 0..1 — the "Up next" panel over Now Playing. */
    val queue = Animatable(0f)
    /** The water the player moves through: heavy contents, the landing dip, the tide on its edge. */
    val water = SheetWater()
    /** Up next's rows floating up when it opens, sinking when it closes. */
    val queueRows = QueueRowsMotion()
    val isExpanded get() = progress.targetValue > 0.5f
    val isQueueOpen get() = queue.targetValue > 0.5f

    /** Rises like a calm tide. Opened from the mini player, the picture, title and controls float up after it. */
    suspend fun expand(velocity: Float = 0f) {
        water.opening(fromRest = progress.value < 0.05f && !progress.isRunning)
        progress.animateTo(1f, Motion.tide(), velocity)
    }

    /** Comes down like heavy water, and dips a little into the tab bar where it lands. */
    suspend fun collapse(velocity: Float = 0f) {
        queue.snapTo(0f)
        queueRows.reset()
        water.closing(progress.value)
        progress.animateTo(0f, Motion.heavy(), velocity)
    }

    /** Up next rises like a calm tide; opened with a tap, its rows float up one after another. */
    suspend fun openQueue(velocity: Float = 0f, tapped: Boolean = false) = coroutineScope {
        queueRows.reset()
        if (tapped && queue.value < 0.05f) {
            queueRows.enter()
            launch { delay(700); queueRows.entered() }
        }
        queue.animateTo(1f, Motion.tide(), velocity)
    }

    /** Up next drains back down; closed with a tap, its rows sink first. */
    suspend fun closeQueue(velocity: Float = 0f, tapped: Boolean = false) {
        coroutineScope {
            if (tapped && queue.value > 0.5f) {
                launch { queueRows.sink() }
                delay(60)
            }
            queue.animateTo(0f, Motion.drain(), velocity)
        }
        queueRows.reset()
    }
}

/**
 * The water the player moves through. Everything is in px and runs only while something moves.
 *
 *  · Heavy water: while you drag the player or it minimises, the picture, title and controls hang
 *    a moment behind it (a slow spring that chases the sheet), but never outside it: the smaller it
 *    gets, the less they may trail.
 *  · The landing: when the mini player touches the tab bar it doesn't stop dead. It keeps going a
 *    little way down into the water with everything in it, then floats back up.
 *  · The tide: while it rises, the water climbs its side edges and ripples along its top.
 *  · Opened from the mini player, the picture, title and controls float up one after another.
 */
@Stable
class SheetWater {
    var unit = 1f
    /** px between the mini player and fully open. */
    var range = 1f
    /** Where the sheet's top edge is for a progress (without the dip). */
    var topOf: (Float) -> Float = { 0f }
    /** The mini player has just touched the tab bar; the argument is how hard (dp/s). */
    var onLand: (Float) -> Unit = {}

    /** A finger (or the back gesture) is moving the sheet. */
    var dragging by mutableStateOf(false)
        private set
    /** Changes on every frame the water moves: read it to redraw with the water. */
    var frame by mutableLongStateOf(0L)
        private set
    private var wake by mutableIntStateOf(0)

    // heavy water: the slow spring the contents hang from
    private var heavy = false
    private var synced = false
    private var fy = 0f
    private var fv = 0f

    // the landing: the bar's dip into the tab bar, and the contents settling inside it
    var dip = 0f
        private set
    private var dipV = 0f
    private var dipOn = false
    private var pulseT = -1f
    private var pulseA = 0f
    private var co = 0f
    private var coV = 0f
    private var coOn = false
    private var armed = false

    /** The tide on the sheet's top edge. */
    val edge = EdgeTide()

    // picture, title and controls floating up (px below their place)
    private val flX = FloatArray(3)
    private val flV = FloatArray(3)
    private val flD = FloatArray(3)
    private val flOn = BooleanArray(3)

    /** 0 = picture, 1 = title, 2 = controls. */
    fun float(i: Int) = flX[i]

    /** How far the contents sit from their place in the sheet (px, + = lower). */
    fun contentOffset(q: Float): Float {
        val lim = (6f + 40f * q) * unit
        var o = if (heavy && synced) lim * tanh((fy - (topOf(q) + dip)) / lim) else 0f
        if (coOn) o = (o + co).coerceIn(-lim - 3f * unit, lim + 3f * unit)
        return o
    }

    internal fun opening(fromRest: Boolean) {
        armed = false
        if (fromRest) {
            val top = topOf(0f) + dip
            if (!heavy || (abs(fy - top) < unit && abs(fv) < 5f * unit)) heavy = false
            val delays = floatArrayOf(0.1f, 0.16f, 0.22f)
            for (k in 0..2) { flX[k] = 30f * unit; flV[k] = 0f; flD[k] = delays[k]; flOn[k] = true }
        } else settleFloats()
        wake++
    }

    internal fun closing(from: Float) {
        if (!heavy) { heavy = true; synced = false }
        armed = from > 0f
        wake++
    }

    fun dragStart() {
        dragging = true
        if (!heavy) { heavy = true; synced = false }
        armed = false
        settleFloats()
        wake++
    }

    fun dragEnd() { dragging = false; wake++ }

    private fun settleFloats() {
        for (k in 0..2) if (!flOn[k] && abs(flX[k]) > 0.1f * unit) { flOn[k] = true; flD[k] = 0f }
    }

    private fun calm(progress: Animatable<Float, AnimationVector1D>): Boolean {
        if (progress.isRunning || dragging || dipOn || coOn || pulseT >= 0f) return false
        if (flOn.any { it } || edge.curl != 0f || edge.rip != 0f) return false
        if (heavy && synced) {
            val top = topOf(progress.value.coerceIn(0f, 1f)) + dip
            if (abs(fy - top) > 0.3f * unit || abs(fv) > 0.5f * unit) return false
        }
        return true
    }

    suspend fun run(progress: Animatable<Float, AnimationVector1D>) {
        while (true) {
            if (calm(progress)) snapshotFlow { Triple(progress.value, progress.isRunning, dragging) to wake }.first { !calm(progress) }
            var last = 0L
            var lastP = progress.value
            while (!calm(progress)) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    last = now
                    val p = progress.value
                    val pv = if (dt > 0f) (p - lastP) / dt else 0f
                    lastP = p
                    step(dt, p, pv, progress.isRunning, progress.targetValue)
                    frame = now
                }
            }
            frame += 1
        }
    }

    private fun step(dt: Float, p: Float, pv: Float, running: Boolean, target: Float) {
        val q = p.coerceIn(0f, 1f)
        val n = max(1, ceil(dt / 0.004f).toInt())
        val h = dt / n

        // the first touch on the tab bar
        if (armed && target == 0f && p <= 0.015f && pv <= 0.05f) { armed = false; touch(q) }

        // the dip, and the contents settling inside the bar
        if (dipOn || coOn) {
            for (i in 0 until n) {
                var acc = 0f
                if (dipOn) {
                    var push = 0f
                    if (pulseT >= 0f) {
                        val u = pulseT / PULSE
                        if (u < 1f) { val s = sin(PI.toFloat() * u); push = pulseA * s * s }
                        pulseT += h
                        if (pulseT >= PULSE) pulseT = -1f
                    }
                    acc = -DIP_K * dip - 2f * DIP_Z * sqrt(DIP_K) * dipV + push
                    dipV += acc * h
                    dip += dipV * h
                }
                if (coOn) {
                    coV += (-CO_K * co - 2f * CO_Z * sqrt(CO_K) * coV - CO_INERTIA * acc) * h
                    co += coV * h
                }
            }
            if (dipOn && pulseT < 0f && abs(dip) < 0.05f * unit && abs(dipV) < unit) { dip = 0f; dipV = 0f; dipOn = false }
            if (coOn && abs(co) < 0.05f * unit && abs(coV) < unit) { co = 0f; coV = 0f; coOn = false }
        }

        // heavy water
        val top = topOf(q) + dip
        if (heavy) {
            if (!synced) { fy = top; fv = 0f; synced = true }
            for (i in 0 until n) {
                fv += (-110f * (fy - top) - 2f * 0.5f * sqrt(110f) * fv) * h
                fy += fv * h
            }
            if (!running && !dragging && abs(fy - top) < 0.3f * unit && abs(fv) < 0.5f * unit) { fy = top; fv = 0f }
        } else { fy = top; fv = 0f; synced = true }

        // floating up into place
        for (k in 0..2) {
            if (!flOn[k]) continue
            if (flD[k] > 0f) { flD[k] -= dt; continue }
            for (i in 0 until n) {
                flV[k] += (-160f * flX[k] - 2f * 0.55f * sqrt(160f) * flV[k]) * h
                flX[k] += flV[k] * h
            }
            if (abs(flX[k]) < 0.1f * unit && abs(flV[k]) < 0.4f * unit) { flX[k] = 0f; flV[k] = 0f; flOn[k] = false }
        }

        // the tide: only while it rises
        val rising = if (dragging) pv > 0.25f else (running && target == 1f)
        val lift = if (rising) (4f + (abs(pv) * range / unit * 0.006f).coerceAtMost(5f)) * unit else 0f
        val stir = if (rising) 1.5f * unit * min(1f, abs(pv) * 0.8f) else 0f
        edge.step(dt, lift, stir, unit)
    }

    private fun touch(q: Float) {
        val top = topOf(q) + dip
        val lim = (6f + 40f * q) * unit
        val o = if (heavy && synced) lim * tanh((fy - top) / lim) else 0f
        // how hard the contents push it in depends on how fast they arrive
        val push = (70f + 0.09f * max(0f, fv / unit)).coerceIn(70f, 240f)
        pulseT = 0f
        pulseA = 2f * push * unit / PULSE
        dipOn = true
        // the contents stop hanging from the follower and settle into the bar from where they are
        co = o + if (coOn) co else 0f
        coV = 0f
        coOn = true
        heavy = false
        onLand(push)
    }

    private companion object {
        const val PULSE = 0.08f
        const val DIP_K = 150f
        const val DIP_Z = 0.5f
        const val CO_K = 170f
        const val CO_Z = 0.5f
        const val CO_INERTIA = 0.02f
    }
}

private val MiniHeight = 64.dp
private val MiniMargin = 8.dp
private val MiniArtH = 48.dp

/**
 * One continuous surface that morphs between the mini player and Now Playing, with the
 * artwork physically travelling between the two — the same object, never a new screen.
 * That spatial continuity is why Apple Music feels effortless: your eye never loses its place.
 */
@Composable
fun PlayerSheet(
    np: NowPlaying,
    conn: PlayerConnection,
    sheet: SheetState,
    bottomBarHeight: Dp,
    water: Water,
    tabBarShown: Boolean,
    onClosed: () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val downloads by AudioCache.progress.collectAsStateWithLifecycle()
    val downloaded = downloads[np.video.id] ?: 0f
    val queue by conn.queue.collectAsStateWithLifecycle()
    // The queue only exists (button, swipe-up panel, left/right swipes) once there are 2+ videos.
    val hasQueue = queue.entries.size > 1
    LaunchedEffect(hasQueue) { if (!hasQueue && sheet.queue.value > 0f) sheet.closeQueue() }
    // Horizontal swipe on Now Playing: the artwork follows your finger to the next/previous video.
    val swipe = remember { Animatable(0f) }
    val error by PlaybackEvents.error.collectAsStateWithLifecycle()
    val savedList by Library.saved.collectAsStateWithLifecycle()
    val saved = savedList.any { it.id == np.video.id }

    // Position: polled only while the app is visible (4×/s expanded, 1×/s mini). Zero in background.
    var pos by remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(np.video.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                pos = conn.positionMs()
                delay(if (sheet.progress.value > 0.5f) 250 else 1000)
            }
        }
    }

    // The water runs only while something moves.
    LaunchedEffect(sheet) { sheet.water.run(sheet.progress) }

    // Artwork breathes: full size while playing, settles back when paused.
    val artScale by animateFloatAsState(if (np.playing || np.waiting) 1f else 0.86f, Motion.lively(), label = "art")

    val ctx = LocalContext.current
    var hintPending by remember { mutableStateOf(!ctx.prefs().getBoolean("hint_zones", false)) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val landscape = W > H * 1.1f
        fun Dp.px() = with(density) { toPx() }
        val unit = density.density

        var controlsH by remember { mutableFloatStateOf(340.dp.px()) }
        val miniH = MiniHeight.px()
        val margin = MiniMargin.px()
        val collapsedTop = H - bottomBarHeight.px() - margin - miniH
        val dragRange = collapsedTop.coerceAtLeast(1f)

        val w = sheet.water
        w.unit = unit
        w.range = dragRange
        w.topOf = { q -> lerp(collapsedTop, 0f, q) + sheet.dismiss.value }
        w.onLand = { push ->
            // The tab bar's surface ripples where the mini player lands on it.
            if (tabBarShown) water.ripple(W / 2, (2.5f + push / 60f).coerceIn(3f, 7f) * unit, 70f * unit, 11f * unit, 1.1f)
        }
        @Suppress("UNUSED_VARIABLE") val frame = w.frame // redraw with the water

        val p = sheet.progress.value.coerceIn(0f, 1f)
        val pulled = sheet.dismiss.value

        val sheetTop = lerp(collapsedTop, 0f, p) + pulled + w.dip
        val sheetH = lerp(miniH, H, p)
        val sideMargin = lerp(margin, 0f, p)
        val sheetW = W - sideMargin * 2
        val corner = lerp(14.dp.px(), 0f, p)
        // contents trailing (heavy water) or settling after the landing; picture, title and controls floating up
        val held = w.contentOffset(p)
        // The tide on the top edge, only once it's clearly growing.
        val tideA = min(1f, p * 4f)
        val curl = w.edge.curl * tideA
        val rip = w.edge.rip * tideA
        val sheetShape = if (curl > 0.3f || rip > 0.3f) TideShape(corner, curl, rip, w.edge.phase, unit)
            else RoundedCornerShape(corner)

        // ----- artwork rectangles (relative to the sheet) ---------------------------------------
        val miniArtH = MiniArtH.px()
        val miniArt = Rect(8.dp.px(), (miniH - miniArtH) / 2, miniArtH * 16f / 9f, miniArtH)
        // Portrait: controls are anchored to the bottom (thumb zone); the artwork floats centred
        // in whatever space is left above them, so every screen height looks composed.
        val fullArt = if (!landscape) {
            val regionTop = statusTop.px() + 40.dp.px()
            val regionBottom = H - controlsH - 12.dp.px()
            val region = (regionBottom - regionTop).coerceAtLeast(120.dp.px())
            val aw = minOf(W - 48.dp.px(), region * 16f / 9f)
            val ah = aw * 9f / 16f
            Rect((W - aw) / 2, regionTop + (region - ah) * 0.42f, aw, ah) // optical centre sits a bit high
        } else {
            val ah = minOf(H - statusTop.px() - navBottom.px() - 64.dp.px(), W * 0.46f * 9f / 16f)
            Rect(32.dp.px(), (H - ah) / 2, ah * 16f / 9f, ah)
        }
        val art = miniArt.lerpTo(fullArt, p)

        val queuePanelH = (H * 0.74f).coerceAtLeast(320.dp.px())
        val queueRange = queuePanelH
        val velocity = remember { VelocityTracker() }
        // Where the sheet is actually placed right now. Touch positions arrive relative to the
        // (moving) sheet, so we add this back to get stable screen coordinates while dragging.
        val placedTop = remember { floatArrayOf(0f) }

        // Closing the video: it lifts a little, then sinks under the tab bar, which ripples.
        // One time in twelve, the water throws up a crown of drops.
        fun sink() = scope.launch {
            val start = sheet.dismiss.value
            sheet.dismiss.animateTo(start - 6f * unit, tween(140, easing = Motion.outCubic))
            val lucky = Random.nextInt(12) == 0
            var touched = false
            sheet.dismiss.animateTo(start - 6f * unit + miniH + margin + 20f * unit, tween(500, easing = Motion.inCubic)) {
                if (!touched && value > margin + 6f * unit) {
                    touched = true
                    if (tabBarShown) {
                        water.ripple(W / 2, 9f * unit, 90f * unit, 11f * unit, 1.3f)
                        if (lucky) water.splash(sideMargin, W - sideMargin, unit)
                    }
                }
            }
            onClosed()
        }

        Box(
            Modifier
                .layout { m, _ ->
                    val pl = m.measure(Constraints.fixed(sheetW.roundToInt(), sheetH.roundToInt()))
                    layout(pl.width, pl.height) {
                        placedTop[0] = sheetTop.roundToInt().toFloat()
                        pl.place(sideMargin.roundToInt(), sheetTop.roundToInt())
                    }
                }
                // Sunk below its place it's under the tab bar already; without one, it fades out there.
                .graphicsLayer { alpha = 1f - ((pulled - miniH - margin) / (20f * unit)).coerceIn(0f, 1f) }
                .shadow(lerp(12f, 0f, p).dp, RoundedCornerShape(with(density) { corner.toDp() }), clip = false)
                .graphicsLayer { shape = sheetShape; clip = true }
                .pointerInput(hasQueue, W) {
                    if (!hasQueue) return@pointerInput
                    val tracker = VelocityTracker()
                    val commit = W * 0.28f
                    var crossed = false
                    fun canNext() = conn.queue.value.let { it.currentIndex in 0 until it.entries.size - 1 }
                    fun canPrev() = conn.queue.value.currentIndex > 0
                    // Works on the full page (queue closed) and on the mini player at the bottom.
                    fun active() = sheet.dismiss.value == 0f &&
                        ((sheet.progress.value > 0.99f && sheet.queue.value < 0.01f) || sheet.progress.value < 0.01f)
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); crossed = false },
                        onDragCancel = { scope.launch { swipe.animateTo(0f, Motion.sheet()) } },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x
                            val x = swipe.value
                            // Finger moves left → next (what's on the right slides in), right → previous.
                            val goNext = canNext() && (x < -commit || (v < -900f && x < 0f))
                            val goPrev = canPrev() && (x > commit || (v > 900f && x > 0f))
                            scope.launch {
                                if (goNext || goPrev) {
                                    val idx = conn.queue.value.currentIndex + if (goNext) 1 else -1
                                    swipe.animateTo(if (goNext) -W else W, tween(170))
                                    conn.jumpTo(idx)
                                    swipe.snapTo(if (goNext) W * 0.35f else -W * 0.35f)
                                    swipe.animateTo(0f, Motion.sheet())
                                } else swipe.animateTo(0f, Motion.sheet())
                            }
                        },
                    ) { change, dx ->
                        if (active()) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val raw = swipe.value + dx
                            val allowed = (raw < 0f && canNext()) || (raw > 0f && canPrev())
                            // Nothing that way: it moves a little, then resists (rubber band).
                            val next = if (allowed) raw else swipe.value + dx * 0.25f
                            val past = allowed && abs(next) > commit
                            if (past != crossed) { crossed = past; if (past) Haptics.threshold(view) }
                            scope.launch { swipe.snapTo(next) }
                        }
                    }
                }
                .pointerInput(dragRange, queueRange) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun screenY(c: androidx.compose.ui.input.pointer.PointerInputChange) = c.position.y + placedTop[0]
                        var lastY = screenY(down)
                        velocity.resetTracking()
                        velocity.addPosition(down.uptimeMillis, Offset(0f, lastY))
                        val startP = sheet.progress.value
                        var dismissMode = false
                        var queueMode = false
                        var crossed = false
                        var pull = 0f // how far the finger has pulled the mini player down (px)
                        val startQ = sheet.queue.value
                        val afterSlop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            dismissMode = startP < 0.02f && over > 0
                            // Fully open: swipe up for "Up next"; with it open, swipe down closes it first.
                            // Read the queue live: this gesture handler outlives recompositions,
                            // so a captured value would still think there's only one video.
                            val queueNow = conn.queue.value.entries.size > 1
                            queueMode = startP > 0.98f && (startQ > 0.01f || (over < 0 && queueNow))
                        } ?: return@awaitEachGesture
                        if (!dismissMode && !queueMode) sheet.water.dragStart()
                        val keep = 60.dp.toPx()
                        val closeAt = 26.dp.toPx()
                        fun apply(dy: Float) {
                            if (queueMode) {
                                scope.launch { sheet.queue.snapTo((sheet.queue.value - dy / queueRange).coerceIn(0f, 1f)) }
                            } else if (dismissMode) {
                                // Follows the finger, then gets heavier past 60 dp.
                                pull = (pull + dy).coerceAtLeast(0f)
                                val shown = if (pull <= keep) pull else keep + (pull - keep) * 0.3f
                                if (!crossed && shown > closeAt) { crossed = true; Haptics.threshold(view) }
                                if (crossed && shown < closeAt * 0.8f) crossed = false
                                scope.launch { sheet.dismiss.snapTo(shown) }
                            } else {
                                scope.launch { sheet.progress.snapTo((sheet.progress.value - dy / dragRange).coerceIn(0f, 1f)) }
                            }
                        }
                        lastY = screenY(afterSlop) - afterSlop.positionChange().y
                        fun track(c: androidx.compose.ui.input.pointer.PointerInputChange) {
                            val y = screenY(c)
                            velocity.addPosition(c.uptimeMillis, Offset(0f, y))
                            apply(y - lastY)
                            lastY = y
                        }
                        track(afterSlop)
                        verticalDrag(afterSlop.id) { change ->
                            track(change)
                            change.consume()
                        }
                        val vy = velocity.calculateVelocity().y
                        if (!dismissMode && !queueMode) sheet.water.dragEnd()
                        scope.launch {
                            if (queueMode) {
                                val flick = 220.dp.toPx()
                                val q = sheet.queue.value
                                val open = when {
                                    vy < -flick -> true
                                    vy > flick -> false
                                    startQ > 0.5f -> q > 0.8f
                                    else -> q > 0.2f
                                }
                                if (open) sheet.openQueue(-vy / queueRange) else sheet.closeQueue(-vy / queueRange)
                            } else if (dismissMode) {
                                if (sheet.dismiss.value > closeAt || vy > 450.dp.toPx()) sink()
                                else sheet.dismiss.animateTo(0f, Motion.snapBack())
                            } else {
                                val v = -vy / dragRange
                                // Light flick or a short pull is enough: commit after ~18% of the
                                // travel in the direction you started, instead of half the screen.
                                val flick = 220.dp.toPx()
                                val pp = sheet.progress.value
                                val open = when {
                                    vy > flick -> false
                                    vy < -flick -> true
                                    startP > 0.5f -> pp > 0.82f
                                    else -> pp > 0.18f
                                }
                                if (open) sheet.expand(v) else sheet.collapse(v)
                            }
                        }
                    }
                }
        ) {
            // Layer 1 — mini surface
            if (p < 1f) Box(Modifier.fillMaxSize().background(palette.chrome))
            // Layer 2 — blurred artwork backdrop
            if (p > 0.001f) Backdrop(np.video.thumbnail, Modifier.fillMaxSize().graphicsLayer { alpha = p })

            // Layer 3 — mini player content
            if (p < 0.3f) {
                MiniContent(
                    np = np, conn = conn,
                    textStart = with(density) { (miniArt.x + miniArt.w).toDp() } + 12.dp,
                    progress = if (np.durationMs > 0) pos.toFloat() / np.durationMs else 0f,
                    modifier = Modifier.fillMaxWidth().height(MiniHeight)
                        .graphicsLayer {
                            alpha = (1f - p / 0.25f).coerceIn(0f, 1f)
                            translationY = held * 0.5f
                        },
                    onExpand = { scope.launch { sheet.expand() } },
                    swipeX = { swipe.value },
                )
            }

            // Layers 4 and 5 sink back a little, softened, while Up next is over them.
            val qOpen = sheet.queue.value
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    if (qOpen > 0.001f) {
                        val s = 1f - 0.03f * qOpen
                        scaleX = s; scaleY = s
                        if (Build.VERSION.SDK_INT >= 31) {
                            val r = 4.dp.toPx() * qOpen
                            renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                        }
                    }
                }
            ) {
                // Layer 4 — Now Playing content
                if (p > 0.2f) {
                    val a = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            alpha = a; translationY = (1f - a) * 48.dp.toPx() + held * 0.75f
                        }
                    ) {
                        // Grabber: says "this pulls down" without words.
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = statusTop + 10.dp)
                                .size(56.dp, 24.dp)
                                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Minimize") {
                                    scope.launch { sheet.collapse() }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(38.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                        }
                        val controlsMod = if (!landscape) {
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .onSizeChanged { controlsH = it.height.toFloat() }
                                .padding(bottom = navBottom + 20.dp, start = 28.dp, end = 28.dp)
                        } else {
                            Modifier.fillMaxSize().padding(
                                start = with(density) { (fullArt.x + fullArt.w).toDp() } + 36.dp,
                                end = 32.dp, top = statusTop + 24.dp, bottom = navBottom + 16.dp,
                            ).wrapContentHeight(Alignment.CenterVertically)
                        }
                        NowPlayingControls(
                            np = np, conn = conn, positionMs = pos, downloaded = downloaded,
                            error = error, saved = saved, onToggleSave = { Library.toggleSaved(np.video) },
                            upcoming = queue.upcoming,
                            showUpNext = hasQueue,
                            onOpenQueue = { scope.launch { sheet.openQueue(tapped = true) } },
                            titleShift = { w.float(1) },
                            controlsShift = { w.float(2) },
                            queueOpen = { sheet.queue.value },
                            modifier = controlsMod,
                        )
                    }
                }

                // Layer 5 — the artwork, travelling between both layouts
                val artCorner = with(density) { lerp(6.dp.toPx(), 12.dp.toPx(), p).toDp() }
                val scale = lerp(1f, artScale, p)
                val artFloat = w.float(0) * (p * 2f).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .layout { m, _ ->
                            val pl = m.measure(Constraints.fixed(art.w.roundToInt(), art.h.roundToInt()))
                            layout(pl.width, pl.height) { pl.place(art.x.roundToInt(), art.y.roundToInt()) }
                        }
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = swipe.value
                            translationY = held + artFloat
                            alpha = 1f - (abs(swipe.value) / W * 0.7f).coerceIn(0f, 0.7f)
                            shadowElevation = lerp(0f, 22.dp.toPx(), p) * scale
                            shape = RoundedCornerShape(artCorner)
                            clip = true
                            ambientShadowColor = Color.Black; spotShadowColor = Color.Black
                        }
                        .background(Color(0xFF2C2C2E))
                ) {
                    RemoteImage(np.video.thumbnail, Modifier.fillMaxSize())
                    ArtworkGestures(
                        enabled = p > 0.99f,
                        playing = np.playing,
                        onSeek = { s -> conn.seekBy(s * 1000L) },
                        onToggle = { conn.togglePlay() },
                        modifier = Modifier.fillMaxSize(),
                        showHint = hintPending && p > 0.99f,
                        onHintShown = { hintPending = false; ctx.prefs().edit().putBoolean("hint_zones", true).apply() },
                    )
                }
            }

            // Layer 6 — "Up next", rising over everything like a calm tide
            if (qOpen > 0.001f && p > 0.9f && hasQueue) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f * qOpen))
                        .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Close queue") {
                            scope.launch { sheet.closeQueue(tapped = true) }
                        }
                )
                QueuePanel(
                    queue = queue,
                    open = sheet.queue,
                    rows = sheet.queueRows,
                    panelHeight = queuePanelH,
                    navBottom = navBottom,
                    downloads = downloads,
                    onJump = { conn.jumpTo(it) },
                    onRemove = { conn.remove(it) },
                    onClose = { scope.launch { sheet.closeQueue(tapped = true) } },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun MiniContent(
    np: NowPlaying,
    conn: PlayerConnection,
    textStart: Dp,
    progress: Float,
    modifier: Modifier,
    onExpand: () -> Unit,
    swipeX: () -> Float,
) {
    val p = LocalPalette.current
    Box(modifier.clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Open player", onClick = onExpand)) {
        Row(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    // Title slides with the artwork when swiping to the next/previous video.
                    translationX = swipeX()
                    alpha = 1f - (abs(swipeX()) / size.width * 1.4f).coerceIn(0f, 0.8f)
                }
                .padding(start = textStart, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(np.video.title, Type.subheadStrong, maxLines = 1)
                Text(np.video.channel, Type.footnote, color = p.secondary, maxLines = 1)
            }
            CircleButton(onClick = { conn.togglePlay() }, size = 48.dp, highlight = p.fill, enabled = !np.ended) {
                PlayPauseGlyph(showPause = np.playing || np.waiting, ended = np.ended, tint = p.label, size = 24.dp)
            }
        }
        MiniProgress(progress, p.label, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp))
    }
}

@Composable
private fun NowPlayingControls(
    np: NowPlaying,
    conn: PlayerConnection,
    positionMs: Long,
    downloaded: Float,
    error: String?,
    saved: Boolean,
    onToggleSave: () -> Unit,
    upcoming: Int,
    showUpNext: Boolean,
    onOpenQueue: () -> Unit,
    titleShift: () -> Float,
    controlsShift: () -> Float,
    queueOpen: () -> Float,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(Modifier.graphicsLayer { translationY = titleShift() }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    np.video.title, Type.title3, color = Color.White, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = 2, initialDelayMillis = 2200, repeatDelayMillis = 2600),
                )
                Spacer(Modifier.height(2.dp))
                Text(np.video.channel, Type.body, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
            Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) {
                SaveButton(saved, onToggleSave, tint = Color.White, size = 20)
            }
        }
        Column(Modifier.graphicsLayer { translationY = controlsShift() }) {
            Spacer(Modifier.height(26.dp))
            val ads by PlaybackEvents.ads.collectAsStateWithLifecycle()
            Scrubber(positionMs, np.durationMs, downloaded, onSeek = conn::seekTo, ads = ads[np.video.id].orEmpty())
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(forward = false) { conn.seekBy(-10_000) }
                CircleButton(onClick = { conn.togglePlay() }, size = 92.dp, enabled = !np.ended,
                    label = if (np.playing) "Pause" else "Play") {
                    PlayPauseGlyph(showPause = np.playing || (np.waiting && error == null), ended = np.ended, tint = Color.White, size = 56.dp)
                }
                SkipButton(forward = true) { conn.seekBy(10_000) }
            }
            Spacer(Modifier.height(20.dp))
            StatusLine(np, downloaded, error, onRetry = { conn.retry(np.video) })
            Spacer(Modifier.height(6.dp))
            // Space is always reserved, so nothing shifts when the button fades in.
            val upNextAlpha by animateFloatAsState(if (showUpNext) 1f else 0f, tween(250), label = "upnext")
            // It fades as Up next rises out of it.
            Box(Modifier.graphicsLayer { alpha = upNextAlpha * (1f - (queueOpen() * 3f).coerceIn(0f, 1f)) }) {
                UpNextButton(upcoming, enabled = showUpNext, onClick = onOpenQueue)
            }
        }
    }
}

/** One line that answers "can I lose signal now?" — the anxiety this app exists to remove. */
@Composable
private fun StatusLine(np: NowPlaying, downloaded: Float, error: String?, onRetry: () -> Unit) {
    val dim = Color.White.copy(alpha = 0.55f)
    // "Available offline" is reassurance, not decoration: say it, then get out of the way.
    val complete = downloaded >= 1f
    var offlineShown by remember(np.video.id) { mutableStateOf(true) }
    LaunchedEffect(np.video.id, complete) {
        if (complete) { offlineShown = true; delay(3500); offlineShown = false }
    }
    val offlineAlpha by animateFloatAsState(if (offlineShown) 1f else 0f, tween(600), label = "offline")
    Row(
        Modifier.fillMaxWidth().height(28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            error != null -> {
                Icon(Icons.Wifi, dim, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(error, Type.footnote, color = dim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 5.dp)
                ) { Text("Try again", Type.captionStrong, color = Color.White) }
            }
            complete -> Row(Modifier.graphicsLayer { alpha = offlineAlpha }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Check, dim, size = 15.dp)
                Spacer(Modifier.width(6.dp))
                Text("Available offline", Type.footnote, color = dim)
            }
            downloaded > 0f -> Text("Saving for offline · ${(downloaded * 100).toInt()}%", Type.footnote.copy(fontFeatureSettings = "tnum"), color = dim)
            np.waiting -> Text("Loading…", Type.footnote, color = dim)
            np.ended -> Text("Finished · double‑tap left to go back", Type.footnote, color = dim)
        }
    }
}

/** Opens the queue. Also the hint that swiping up does the same. */
@Composable
private fun UpNextButton(upcoming: Int, enabled: Boolean, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    // The chevron hops upward when tapped: it points where the list is about to come from.
    val hop = remember { Animatable(0f) }
    // The count bumps when a video joins the queue, so you see it land.
    val bump = remember { Animatable(1f) }
    var lastCount by remember { mutableStateOf(upcoming) }
    LaunchedEffect(upcoming) {
        if (upcoming > lastCount) { bump.snapTo(1.45f); bump.animateTo(1f, Motion.lively()) }
        lastCount = upcoming
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .clickable(enabled = enabled, onClickLabel = "Up next") {
                    scope.launch { hop.animateTo(1f, tween(110)); hop.animateTo(0f, Motion.lively()) }
                    onClick()
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.ChevronUp, Color.White.copy(alpha = 0.6f), size = 16.dp,
                modifier = Modifier.graphicsLayer { translationY = -hop.value * 5.dp.toPx() })
            Spacer(Modifier.width(6.dp))
            Text("Up next", Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f))
            if (upcoming > 0) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.graphicsLayer { scaleX = bump.value; scaleY = bump.value }
                        .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) { Text("$upcoming", Type.captionStrong.copy(fontFeatureSettings = "tnum"), color = Color.White) }
            }
        }
    }
}

/**
 * ±10 s. The arrow turns the way time moves, like nudging a dial, and the amount floats up off the
 * button. Tapping again quickly adds up ("+20 s"), so a few taps read as one move.
 */
@Composable
private fun SkipButton(forward: Boolean, onSkip: () -> Unit) {
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(0f) }
    val float = remember { Animatable(0f) }
    var total by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    Box(contentAlignment = Alignment.Center) {
        CircleButton(
            onClick = {
                onSkip()
                val now = System.currentTimeMillis()
                total = if (now - lastTap < 900) total + 10 else 10
                lastTap = now
                scope.launch { turn.snapTo(0f); turn.animateTo(1f, tween(120)); turn.animateTo(0f, Motion.lively()) }
                scope.launch { float.snapTo(0f); float.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
            },
            size = 72.dp,
            label = if (forward) "Forward 10 seconds" else "Back 10 seconds",
        ) {
            SkipIcon(10, forward = forward, tint = Color.White, size = 38.dp,
                modifier = Modifier.graphicsLayer { rotationZ = turn.value * if (forward) 38f else -38f })
        }
        if (float.value in 0.001f..0.999f) {
            Text(
                (if (forward) "+" else "−") + total + " s",
                Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                    translationY = -float.value * 22.dp.toPx()
                    alpha = if (float.value < 0.2f) float.value / 0.2f else 1f - (float.value - 0.2f) / 0.8f
                },
            )
        }
    }
}

@Composable
fun PlayPauseGlyph(showPause: Boolean, ended: Boolean, tint: Color, size: Dp) {
    val t by animateFloatAsState(if (showPause && !ended) 1f else 0f, Motion.snappy(), label = "pp")
    val dim = if (ended) 0.35f else 1f
    Box(contentAlignment = Alignment.Center) {
        // A quarter-turn swap, like flipping a switch: one glyph turns away as the other turns in.
        Icon(Icons.Play, tint, size = size, modifier = Modifier.graphicsLayer {
            alpha = (1f - t) * dim; scaleX = 0.6f + 0.4f * (1f - t); scaleY = scaleX; rotationZ = -45f * t
        })
        Icon(Icons.Pause, tint, size = size, modifier = Modifier.graphicsLayer {
            alpha = t; scaleX = 0.6f + 0.4f * t; scaleY = scaleX; rotationZ = 45f * (1f - t)
        })
    }
}

/** Big round hit target; on press a soft disc appears behind the glyph and it sinks a little. */
@Composable
fun CircleButton(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Color = Color.White.copy(alpha = 0.12f),
    label: String? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.86f else 1f, Motion.snappy(), label = "cb")
    val bg by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 260), label = "cbbg")
    val view = LocalView.current
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(CircleShape)
            .background(highlight.copy(alpha = highlight.alpha * bg))
            .clickable(source, null, enabled = enabled, role = Role.Button, onClickLabel = label) {
                Haptics.tick(view); onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The artwork, blown up and blurred behind everything. Loading a 48 px version and stretching
 * it already looks soft (and costs nothing); on Android 12+ a real blur smooths it fully.
 */
@Composable
private fun Backdrop(url: String, modifier: Modifier) {
    Box(modifier.background(Color(0xFF1C1C1E))) {
        RemoteImage(
            url,
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier),
            contentScale = ContentScale.Crop,
            sizePx = 48,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x47000000), Color(0x73000000), Color(0xA6000000)))
            )
        )
    }
}

private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun lerpTo(o: Rect, t: Float) = Rect(lerp(x, o.x, t), lerp(y, o.y, t), lerp(w, o.w, t), lerp(h, o.h, t))
}

private fun Context.prefs() = getSharedPreferences("ui", Context.MODE_PRIVATE)
