package app.earful.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.earful.playback.QueueEntry
import app.earful.playback.QueueState
import app.earful.ui.components.Format
import app.earful.ui.components.Haptics
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.RemoteImage
import app.earful.ui.components.Text
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Up next": slides up over Now Playing. Played videos stay listed (dimmed) so the queue reads
 * like a timeline; their audio is already deleted. Swipe a row left to remove it.
 *
 * [open] is 0..1 and is driven both by this panel's own drags and by the sheet.
 */
@Composable
fun QueuePanel(
    queue: QueueState,
    open: Animatable<Float, *>,
    panelHeight: Float,
    navBottom: Dp,
    downloads: Map<String, Float>,
    onJump: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (queue.currentIndex - 1).coerceAtLeast(0))

    fun settle(velocity: Float = 0f) = scope.launch {
        val target = if (velocity > 900f) 0f else if (velocity < -900f) 1f else if (open.value > 0.7f) 1f else 0f
        open.animateTo(target, Motion.sheet())
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

    Column(
        modifier
            .fillMaxWidth()
            .height(with(androidx.compose.ui.platform.LocalDensity.current) { panelHeight.toDp() })
            .offset { IntOffset(0, ((1f - open.value) * panelHeight).roundToInt()) }
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color(0xF0161618))
    ) {
        // Header: grabber + title. Drag it to move the panel.
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
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Close queue") {
                    scope.launch { open.animateTo(0f, Motion.sheet()) }
                }
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
            items(queue.entries, key = { it.uid }) { e ->
                QueueRow(
                    entry = e,
                    download = if (e.index == queue.currentIndex + 1) downloads[e.video.id] else null,
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

/** A queue row. Swipe left past the line to remove it (the playing one stays put). */
@Composable
private fun QueueRow(
    entry: QueueEntry,
    download: Float?,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val dimmed = if (entry.passed) 0.42f else 1f

    Box(modifier.fillMaxWidth().height(64.dp)) {
        if (!entry.current) {
            // Revealed underneath as the row slides left.
            Row(
                Modifier.fillMaxSize().background(Color(0xFFFF3B30)).padding(end = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Trash, Color.White, size = 22.dp, contentDescription = "Remove",
                    modifier = Modifier.graphicsLayer { alpha = (-dx.value / 120f).coerceIn(0f, 1f) })
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = dx.value }
                .background(Color(0xFF161618))
                .then(
                    if (entry.current) Modifier else Modifier.pointerInput(entry.uid) {
                        var crossed = false
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (dx.value < -size.width * 0.33f) {
                                        dx.animateTo(-size.width.toFloat(), tween(160))
                                        onRemove()
                                    } else dx.animateTo(0f, Motion.sheet())
                                    crossed = false
                                }
                            },
                            onDragCancel = { scope.launch { dx.animateTo(0f, Motion.sheet()) } },
                        ) { change, amount ->
                            change.consume()
                            val next = (dx.value + amount).coerceIn(-size.width.toFloat(), 0f)
                            val past = next < -size.width * 0.33f
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

