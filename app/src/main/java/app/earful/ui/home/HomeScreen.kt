package app.earful.ui.home

import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.viewmodel.compose.viewModel
import app.earful.ui.components.Haptics
import app.earful.ui.components.VideoCardSkeleton
import app.earful.ui.theme.Motion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.Library
import app.earful.data.Video
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.VideoCard
import app.earful.ui.components.pressable
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type

/**
 * Home is one thing only: "For you" — videos related to what you've listened to.
 * It's built on the phone from your own listening, needs no account, and costs no extra
 * data (the suggestions arrive for free with each video you play).
 */
@Composable
fun HomeScreen(
    bottomPadding: Dp,
    onPlay: (Video) -> Unit,
    onOpenSearch: () -> Unit,
    listState: LazyListState,
    topSignal: Int,
    onMessage: (String) -> Unit,
    onQueue: (Video) -> Unit,
    model: FeedModel = viewModel(),
) {
    val p = LocalPalette.current
    val forYou by Library.forYou.collectAsStateWithLifecycle()
    val saved by Library.saved.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.id } }
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // Top bar slides away while you scroll down and returns the moment you scroll up.
    var barHeightPx by remember { mutableFloatStateOf(0f) }
    var barOffset by remember { mutableFloatStateOf(0f) }

    // ----- pull to refresh (iOS style: the list follows your finger with rising resistance) ---
    val trigger = with(density) { 76.dp.toPx() }
    val hold = with(density) { 58.dp.toPx() }
    val maxPull = with(density) { 150.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }

    suspend fun settle(to: Float) = animate(pull, to, animationSpec = Motion.sheet()) { v, _ -> pull = v }

    fun startRefresh() {
        model.refresh(onMessage)
    }

    val nested = remember(model) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up while pulled: shrink the pull first.
                if (available.y < 0 && pull > 0f && source == NestedScrollSource.UserInput) {
                    val next = (pull + available.y).coerceAtLeast(0f)
                    val used = next - pull
                    pull = next
                    if (armed && pull < trigger) armed = false
                    return Offset(0f, used)
                }
                if (pull == 0f) barOffset = (barOffset + available.y).coerceIn(-barHeightPx, 0f)
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput && !model.refreshing) {
                    val resistance = 0.55f * (1f - pull / maxPull).coerceIn(0.1f, 1f)
                    pull = (pull + available.y * resistance).coerceAtMost(maxPull)
                    if (!armed && pull >= trigger) { armed = true; Haptics.threshold(view) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                if (armed && !model.refreshing) startRefresh()
                armed = false
                settle(if (model.refreshing) hold else 0f)
                return available // the pull eats the fling; the list doesn't jump
            }
        }
    }
    // Spinner retracts as soon as the refresh is done.
    LaunchedEffect(model.refreshing) { if (!model.refreshing && pull > 0f) settle(0f) }

    suspend fun scrollToTop() {
        barOffset = 0f
        // From deep down, jump close first so the glide back is short and smooth.
        if (listState.firstVisibleItemIndex > 6) listState.scrollToItem(5)
        listState.animateScrollToItem(0)
    }

    // Home tab tapped while already home: back to the top, nothing reloaded.
    LaunchedEffect(topSignal) { if (topSignal > 0) scrollToTop() }

    // Infinite scroll: fetch more as you approach the end.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 4 && info.totalItemsCount > 0
        }.distinctUntilChanged().filter { it }.collect { if (Library.forYou.value.isNotEmpty()) model.loadMore() }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = with(density) { barHeightPx.toDp() }

    Box(Modifier.fillMaxSize().nestedScroll(nested)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = barHeight, bottom = bottomPadding + 16.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = pull },
        ) {
            item(key = "update", contentType = "update") { app.earful.ui.components.UpdateCard() }
            if (forYou.isEmpty()) {
                item { EmptyForYou(onOpenSearch) }
            } else {
                items(forYou, key = { it.id }, contentType = { "card" }) { v ->
                    VideoCard(v, saved = v.id in savedIds, onOpen = { onPlay(v) }, onToggleSave = { Library.toggleSaved(v) }, onQueue = { onQueue(v) })
                }
                if (model.loadingMore) item(key = "more", contentType = "skeleton") { VideoCardSkeleton() }
            }
        }

        // ----- refresh spinner, revealed in the gap the pull opens -------------------------------
        if (pull > 0.5f) {
            Box(
                Modifier.fillMaxWidth().padding(top = barHeight).height(with(density) { pull.toDp() }),
                contentAlignment = Alignment.Center,
            ) {
                ActivitySpinner(
                    progress = (pull / trigger).coerceIn(0f, 1f),
                    spinning = model.refreshing,
                    color = p.secondary,
                )
            }
        }

        // ----- top bar ------------------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { barHeightPx = it.height.toFloat() }
                .graphicsLayer { translationY = barOffset }
                .background(p.bg.copy(alpha = 0.96f))
                .padding(top = statusTop)
                .height(56.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Logo: back to the top *and* fresh picks.
            Box(
                Modifier.pressable(scaleTo = 0.94f, label = "Refresh") {
                    scope.launch {
                        scrollToTop()
                        if (Library.recentListened(1).isNotEmpty()) {
                            startRefresh()
                            settle(hold)
                        }
                    }
                }.padding(horizontal = 8.dp, vertical = 6.dp)
            ) { Wordmark() }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).pressable(scaleTo = 0.85f, label = "Search", onClick = onOpenSearch), contentAlignment = Alignment.Center) {
                Icon(Icons.Search, p.label, size = 24.dp, contentDescription = "Search")
            }
        }
    }
}

/**
 * Apple's activity indicator: eight rounded spokes. While pulling they appear one by one
 * (so the pull itself shows progress); once refreshing, the bright spoke circles round.
 */
@Composable
private fun ActivitySpinner(progress: Float, spinning: Boolean, color: Color) {
    val step = if (spinning) {
        val t = rememberInfiniteTransition(label = "spin")
        val v by t.animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "step")
        v.toInt()
    } else 0
    Canvas(Modifier.size(26.dp)) {
        val c = center
        val outer = size.minDimension / 2
        val inner = outer * 0.48f
        val w = size.minDimension * 0.105f
        val shown = if (spinning) 8 else (progress * 8).toInt().coerceIn(0, 8)
        for (i in 0 until shown) {
            val angle = Math.toRadians((i * 45.0) - 90.0)
            val age = ((step - i) % 8 + 8) % 8
            val alpha = if (spinning) 1f - age / 9f else 0.85f
            val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
            drawLine(
                color.copy(alpha = alpha * color.alpha),
                Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer),
                strokeWidth = w, cap = StrokeCap.Round,
            )
        }
    }
}

/** First launch: nothing listened to yet, so point straight at the one thing to do. */
@Composable
private fun EmptyForYou(onOpenSearch: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(18, 34, 48, 28, 14).forEach { h ->
                Box(Modifier.width(7.dp).height(h.dp).clip(RoundedCornerShape(4.dp)).background(p.accent))
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Your For you starts here", Type.title3.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(8.dp))
        Text(
            "Search for something you like. Every video you listen to brings more like it here.",
            Type.subhead.copy(textAlign = TextAlign.Center), color = p.secondary,
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier.pressable(onClick = onOpenSearch).clip(CircleShape).background(p.accent)
                .padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Search, androidx.compose.ui.graphics.Color.White, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Search", Type.subheadStrong, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
fun Wordmark() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Five bars, like the app icon: a waveform that reads as "listen" at a glance.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(7, 13, 18, 11, 6).forEach { h ->
                Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("Earful", Type.title2)
    }
}

@Composable
fun Message(title: String, body: String, action: String?, onAction: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, Type.title3.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(6.dp))
        Text(body, Type.subhead.copy(textAlign = TextAlign.Center), color = p.secondary)
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.pressable(onClick = onAction).clip(CircleShape).background(p.fill)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) { Text(action, Type.subheadStrong, color = p.accent) }
        }
    }
}
