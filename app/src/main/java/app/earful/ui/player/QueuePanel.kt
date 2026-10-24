package app.earful.ui.player

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.earful.playback.QueueEntry
import app.earful.playback.QueueState
import app.earful.ui.components.EdgeTide
import app.earful.ui.components.Format
import app.earful.ui.components.Haptics
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.RemoteImage
import app.earful.ui.components.Text
import app.earful.ui.components.addTideRect
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Up next's rows: floating up one after another when it's opened, sinking away when it's closed. */
@Stable
class QueueRowsMotion {
    /** Rows that appear while this is on float up into place, one after another. */
    var entering by mutableStateOf(false)
        private set
    /** 0 = rows in place, 1 = sunk away (faded out, 20 dp lower). */
    val out = Animatable(0f)

    fun enter() { entering = true }
    fun entered() { entering = false }
    suspend fun sink() = out.animateTo(1f, tween(200, easing = Motion.inCubic))
    suspend fun reset() { entering = false; out.snapTo(0f) }
}

private val PanelBg = Color(0xF0161618)
private val RowBg = Color(0xFF161618)
private val Red = Color(0xFFFF3B30)

/**
 * "Up next": rises over Now Playing like water calmly filling a glass. Water climbs its edges right
 * at the sides and stays a hair raised there; the surface gives one soft ripple and settles.
 * Played videos stay listed (dimmed) so the queue reads like a timeline; their audio is already
 * deleted. Swipe a row left to remove it: the red floods the row and drains away.
 *
 * [open] is 0..1 and is driven both by this panel's own drags and by the sheet.
 */
@Composable
fun QueuePanel(
    queue: QueueState,
    open: Animatable<Float, AnimationVector1D>,
    rows: QueueRowsMotion,
    panelHeight: Float,
    navBottom: Dp,
    downloads: Map<String, Float>,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val unit = density.density
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (queue.currentIndex - 1).coerceAtLeast(0))

    fun settle(velocity: Float = 0f) = scope.launch {
        val target = if (velocity > 900f) 0f else if (velocity < -900f) 1f else if (open.value > 0.7f) 1f else 0f
        open.animateTo(target, if (target == 1f) Motion.tide() else Motion.drain())
    }

    // ----- the tide on the panel's top edge ---------------------------------------------------
    val edge = remember { EdgeTide() }
    var edgeFrame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(open, panelHeight) {
        var last = 0L
        var lastV = open.value
        var wasOpening = false
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                val v = open.value
                val speed = if (dt > 0f) (v - lastV) / dt else 0f // panels per second
                lastV = v
                val moving = open.isRunning || abs(speed) > 0.01f
                val opening = open.isRunning && open.targetValue == 1f
                // Climbs the sides while it moves (more when it's fast) and stays a hair raised once open.
                val lift = when {
                    moving -> (4f + (abs(speed) * panelHeight / unit * 0.006f).coerceAtMost(5f)) * unit
                    v > 0.5f -> 4f * unit
                    else -> 0f
                }
                val stir = if (opening) 1.5f * unit * min(1f, abs(speed) * 0.8f) else 0f
                // It arrives: one soft ripple, then it settles.
                if (wasOpening && !opening && v > 0.98f) edge.settle(1.2f * unit)
                wasOpening = opening
                edge.step(dt, lift, stir, unit)
                edgeFrame = now
            }
            // Asleep (no frames) while nothing moves.
            if (!open.isRunning && edge.rip == 0f && edge.curl == (if (open.value > 0.5f) 4f * unit else 0f)) {
                val at = open.value
                snapshotFlow { open.value to open.isRunning }.first { (v, r) -> r || v != at }
                last = 0L
                lastV = open.value
            }
        }
    }

    // Pull the list down from its top and the whole panel follows, then closes.
    val nested = remember(panelHeight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && open.value < 1f && source == NestedScrollSource.UserInput) {
                    val before = open.value
                    val next = (before - available.y / panelHeight).coerceAtMost(1f)
                    scope.launch { open.snapTo(next) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    scope.launch { open.snapTo((open.value - available.y / panelHeight).coerceAtLeast(0f)) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (open.value < 1f) { settle(available.y); return available }
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(queue.currentIndex) {
        if (queue.currentIndex >= 0 && open.value < 0.05f) list.scrollToItem((queue.currentIndex - 1).coerceAtLeast(0))
    }

    // Room above the panel for the water to climb its sides.
    val lip = 12.dp
    val lipPx = with(density) { lip.toPx() }
    val path = remember { Path() }
    Column(
        modifier
            .fillMaxWidth()
            .height(with(density) { panelHeight.toDp() } + lip)
            .offset { IntOffset(0, ((1f - open.value) * panelHeight).roundToInt()) }
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val f = edgeFrame
                path.reset()
                // The bottom runs on past the panel, so the tide's small overshoot never shows a gap.
                path.addTideRect(
                    0f, lipPx, size.width, size.height + 200.dp.toPx(), 22.dp.toPx(), 0f,
                    edge.curl, edge.rip, edge.phase, unit, inset = false, shrinkCorners = false,
                )
                drawPath(path, PanelBg)
            }
    ) {
        Spacer(Modifier.height(lip))
        // Header: grabber + title. Drag it to move the panel; tap it to close.
        Column(
            Modifier.fillMaxWidth()
                .pointerInput(panelHeight) {
                    detectVerticalDragGestures(
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, dy ->
                        change.consume()
                        scope.launch { open.snapTo((open.value - dy / panelHeight).coerceIn(0f, 1f)) }
                    }
                }
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Close queue", onClick = onClose)
                .padding(top = 8.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(38.dp, 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text("Up next", Type.title3, color = Color.White, modifier = Modifier.weight(1f))
                val upcoming = queue.entries.filter { !it.passed && !it.current }
                if (upcoming.isNotEmpty()) {
                    val mins = upcoming.sumOf { it.video.durationSec.coerceAtLeast(0) } / 60
                    val time = if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
                    Text("${upcoming.size} · $time", Type.footnote, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().nestedScroll(nested),
            contentPadding = PaddingValues(bottom = navBottom + 16.dp),
        ) {
            itemsIndexed(queue.entries, key = { _, e -> e.uid }) { i, e ->
                QueueRow(
                    entry = e,
                    download = if (e.index == queue.currentIndex + 1) downloads[e.video.id] else null,
                    rows = rows,
                    order = { i - list.firstVisibleItemIndex },
                    onTap = { if (!e.current) onJump(e.index) },
                    onRemove = { onRemove(e.index) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (queue.entries.none { !it.passed && !it.current }) item(key = "empty") {
                Text(
                    "Nothing queued yet. Tap the queue icon under the bookmark on any video to add it here.",
                    Type.footnote, color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                )
            }
        }
    }
}

/**
 * A queue row. Swipe it left and the red follows your finger as a liquid with a rippling edge;
 * let go past the line and the red floods the whole row, then drains down out of sight while the
 * rows below slide up over it. The playing one stays put.
 */
@Composable
private fun QueueRow(
    entry: QueueEntry,
    download: Float?,
    rows: QueueRowsMotion,
    order: () -> Int,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val unit = LocalDensity.current.density
    val remove by rememberUpdatedState(onRemove)
    val dx = remember { Animatable(0f) }
    val drain = remember { Animatable(0f) }   // 0..1 — the red going down the drain, the row closing
    var fingerV by remember { mutableFloatStateOf(0f) } // px/s while a finger moves it
    var phase by remember { mutableFloatStateOf(0f) }
    var stirring by remember { mutableStateOf(false) }   // a finger or a spring is moving it
    val dimmed = if (entry.passed) 0.42f else 1f
    val rowW = remember { floatArrayOf(1080f) } // measured width, so the flood knows how far to go

    // Floating up when Up next is opened with a tap.
    val enter = remember { Animatable(if (rows.entering) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) {
            delay((80 + order().coerceIn(0, 10) * 60).toLong())
            enter.animateTo(1f, Motion.floatUp())
        }
    }

    // The red's edge ripples while it moves.
    LaunchedEffect(stirring) {
        if (!stirring) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) phase += (now - last) / 1e9f * 7f
            last = now
        }
    }

    suspend fun drainAway() {
        // flood: the row slides out and the red fills it
        dx.animateTo(-(rowW[0] + 6f * unit) / 0.86f - 2f * unit, tween(260, easing = Motion.inOutCubic))
        // drain: the red goes down out of sight while the rows below slide up over it
        drain.animateTo(1f, tween(450, easing = Motion.inOutCubic))
        stirring = false
        remove()
        // Still here a moment later (the queue refused): bring it back.
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
            .height(64.dp)
            .graphicsLayer {
                // floating in from 30 dp lower; sinking 20 dp and fading when Up next closes
                val e = enter.value
                translationY = (1f - e) * 30.dp.toPx() + rows.out.value * 20.dp.toPx()
                alpha = (e / 0.6f).coerceIn(0f, 1f) * (1f - rows.out.value)
            }
    ) {
        if (!entry.current) {
            // The red, as a liquid.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val d = drain.value
                if (dx.value > -0.5f && d == 0f) return@Canvas
                val red = Path()
                if (d == 0f) {
                    val v = if (stirring && fingerV != 0f) fingerV else dx.velocity
                    val amp = if (dx.value < 0f) (2.2f + (abs(v) / unit * 0.006f).coerceAtMost(7f)) * unit else 0f
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
                    var x = 0f
                    while (x <= w + 4f) {
                        red.lineTo(x, h * d + 3f * unit * (1f - d) * sin(x / (26f * unit) + d * 12f))
                        x += 8f * unit
                    }
                    red.lineTo(w + 4f, h + 4f)
                    red.close()
                }
                drawPath(red, Red)
            }
            Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Trash, Color.White, size = 22.dp, contentDescription = "Remove",
                    modifier = Modifier.graphicsLayer {
                        val k = (-dx.value / 70.dp.toPx()).coerceIn(0f, 1f)
                        alpha = k * (1f - drain.value * 1.6f).coerceIn(0f, 1f)
                        val s = 0.5f + 0.5f * k
                        scaleX = s; scaleY = s
                    })
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dx.value; alpha = if (drain.value > 0f) 0f else 1f }
                .background(RowBg)
                .then(
                    if (entry.current) Modifier else Modifier.pointerInput(entry.uid) {
                        val tracker = VelocityTracker()
                        var crossed = false
                        var finger = 0f
                        val give = 200.dp.toPx()
                        val commit = 80.dp.toPx()
                        // Follows the finger, then gets heavy past 200 dp.
                        fun shown(f: Float) = if (f < -give) -give + (f + give) * 0.3f else f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                val d = dx.value
                                finger = if (d < -give) -give + (d + give) / 0.3f else d
                                stirring = true
                            },
                            onDragEnd = {
                                val v = tracker.calculateVelocity().x
                                fingerV = 0f
                                crossed = false
                                scope.launch {
                                    if (dx.value < -commit || v < -650.dp.toPx()) drainAway()
                                    else {
                                        dx.animateTo(0f, Motion.rowBack(), v)
                                        stirring = false
                                    }
                                }
                            },
                            onDragCancel = { fingerV = 0f; scope.launch { dx.animateTo(0f, Motion.rowBack()); stirring = false } },
                        ) { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            fingerV = fingerV * 0.6f + tracker.calculateVelocity().x * 0.4f
                            finger = (finger + amount).coerceAtMost(0f)
                            val next = shown(finger)
                            val past = next < -commit
                            if (past != crossed) { crossed = past; if (past) Haptics.threshold(view) }
                            scope.launch { dx.snapTo(next) }
                        }
                    }
                )
                .clickable(onClick = onTap)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).graphicsLayer { alpha = dimmed }) {
                RemoteImage(entry.video.thumbnail, Modifier.fillMaxSize(), sizePx = 216)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp).graphicsLayer { alpha = dimmed }) {
                Text(entry.video.title, Type.subheadStrong, color = Color.White, maxLines = 1)
                Text(
                    when {
                        entry.current -> "Now playing"
                        else -> listOfNotNull(entry.video.channel.ifEmpty { null },
                            entry.video.durationSec.takeIf { it > 0 }?.let(Format::duration)).joinToString(" · ")
                    },
                    Type.footnote,
                    color = if (entry.current) Color(0xFFFF6482) else Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            when {
                entry.current -> NowPlayingBars()
                download != null -> DownloadDot(download)
            }
        }
    }
}

/** Three small bars: "this one is playing". Static — no animation burning frames off-screen. */
@Composable
private fun NowPlayingBars() {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(14.dp)) {
        listOf(8, 14, 10).forEach { h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.5.dp)).background(Color(0xFFFF6482)))
        }
    }
}

/** For the next track: a ring while it saves, a tick once it's on the phone. */
@Composable
private fun DownloadDot(fraction: Float) {
    if (fraction >= 1f) { Icon(Icons.Check, Color.White.copy(alpha = 0.5f), size = 16.dp); return }
    Canvas(Modifier.size(16.dp)) {
        val s = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val inset = 1.dp.toPx()
        val sz = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), sz, style = s)
        drawArc(Color.White.copy(alpha = 0.6f), -90f, 360f * fraction, false, Offset(inset, inset), sz, style = s)
    }
}
