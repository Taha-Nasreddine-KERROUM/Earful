package app.earful.ui.channel

import app.earful.ui.hole.eatable
import app.earful.ui.hole.HoleLayer
import app.earful.ui.hole.LocalHoleLayer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.Channels
import app.earful.data.Library
import app.earful.data.Paged
import app.earful.data.Video
import app.earful.ui.components.ChannelLogo
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.VideoCard
import app.earful.ui.components.VideoCardSkeleton
import app.earful.ui.components.blockTouches
import app.earful.ui.components.pressable
import app.earful.ui.home.Message
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Which channel to show: what's known about it from the video it was opened from. */
@Stable
class ChannelRef(val url: String?, val name: String, val avatar: String?, val fromVideo: Video)

enum class ChannelSort(val label: String) { New("New"), Popular("Popular") }

/** One sort's list: what's loaded, and how to get more. */
private class ListState {
    var items by mutableStateOf<List<Video>>(emptyList())
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var next: (suspend () -> Paged<Video>)? = null
    var loadingMore = false
    var job: Job? = null
}

@Stable
private class ChannelModel(private val ref: ChannelRef, private val scope: CoroutineScope) {
    var info by mutableStateOf<Channels.Info?>(null)
    var infoFailed by mutableStateOf(false)
    var url by mutableStateOf(ref.url)
    val lists = mutableStateMapOf<ChannelSort, ListState>()

    fun start(sort: ChannelSort) {
        infoFailed = false
        scope.launch {
            val u = url ?: app.earful.data.YouTube.channelUrlOf(ref.fromVideo.youtubeId).also { url = it }
            if (u == null) { infoFailed = true; lists.values.forEach { it.loading = false; it.failed = true }; return@launch }
            runCatching { Channels.info(u) }
                .onSuccess { info = it; load(sort) }
                .onFailure { infoFailed = true; list(sort).apply { loading = false; failed = true } }
        }
    }

    fun list(sort: ChannelSort): ListState = lists.getOrPut(sort) { ListState() }

    fun load(sort: ChannelSort) {
        val ch = info ?: return // (asked for again once the channel is known)
        val l = list(sort)
        if (l.items.isNotEmpty() || l.job?.isActive == true) return
        l.loading = true; l.failed = false
        l.job = scope.launch {
            runCatching { if (sort == ChannelSort.New) Channels.newest(ch) else Channels.popular(ch) }
                .onSuccess { l.items = it.items; l.next = it.next }
                .onFailure { l.failed = true }
            l.loading = false
        }
    }

    fun more(sort: ChannelSort) {
        val l = list(sort)
        val n = l.next ?: return
        if (l.loadingMore) return
        l.loadingMore = true
        scope.launch {
            runCatching { n() }.onSuccess { page ->
                l.items = (l.items + page.items).distinctBy { it.id }; l.next = page.next
            }
            l.loadingMore = false
        }
    }
}

/**
 * A channel's page: its logo (ringed in red while it's live: tap it to tune in), its name, and
 * its videos, newest or most popular, shown just like Home and search. Nothing else: no
 * subscribing, no tabs of shorts and posts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    ref: ChannelRef,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPlay: (Video) -> Unit,
    onQueue: (Video) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val model = remember(ref) { ChannelModel(ref, scope) }
    var sort by remember(ref) { mutableStateOf(ChannelSort.New) }
    val saved by Library.saved.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.id } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LaunchedEffect(ref) { model.list(ChannelSort.New); model.start(ChannelSort.New) }
    LaunchedEffect(sort, model.info) { model.load(sort) }

    // Each sort keeps its own place in the list.
    val states = remember(ref) { ChannelSort.entries.associateWith { LazyListState() } }
    val list = states.getValue(sort)
    LaunchedEffect(list, sort) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= list.layoutInfo.totalItemsCount - 5 }
            .collect { model.more(sort) }
    }
    val name = model.info?.name?.ifBlank { null } ?: ref.name
    // The name moves up into the bar once the big one has scrolled away.
    val titleIn by remember(list) { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 140 } }
    val titleA by animateFloatAsState(if (titleIn) 1f else 0f, tween(180), label = "title")

    Box(modifier.fillMaxSize().eatable(page = true, fill = p.bg, layer = HoleLayer.Channel).blockTouches().background(p.bg)) {
    CompositionLocalProvider(LocalHoleLayer provides HoleLayer.Channel) {
        val l = model.list(sort)
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = statusTop + 56.dp, bottom = bottomPadding + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val logoVideo = remember(model.info, ref) {
                        ref.fromVideo.copy(channelUrl = model.info?.url ?: model.url ?: ref.url, channelAvatar = model.info?.avatar ?: ref.avatar)
                    }
                    ChannelLogo(logoVideo, 88.dp, sizePx = 264, onOpen = {})
                    Spacer(Modifier.height(12.dp))
                    Text(name, Type.title2, maxLines = 2, modifier = Modifier.padding(horizontal = 32.dp))
                    val subs = model.info?.subscribers?.takeIf { it >= 0 }?.let(::subscribers)
                    Text(subs ?: " ", Type.footnote, color = p.secondary, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
            stickyHeader(key = "sort") {
                SortBar(sort, onPick = { sort = it })
            }
            when {
                l.loading && l.items.isEmpty() -> items(3) { VideoCardSkeleton() }
                l.failed && l.items.isEmpty() -> item {
                    Message("Couldn't load this channel", "Check your connection.", "Try again") {
                        if (model.info == null) model.start(sort) else model.load(sort)
                    }
                }
                l.items.isEmpty() -> item { Message("No videos", "This channel hasn't posted any yet.", null) {} }
                else -> itemsIndexed(l.items, key = { _, v -> v.id }) { _, v ->
                    VideoCard(
                        v, saved = v.id in savedIds, onOpen = { onPlay(v) },
                        onToggleSave = { Library.toggleSaved(v) }, onQueue = { onQueue(v) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        // ----- bar ------------------------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().background(p.bg).padding(top = statusTop).height(56.dp).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).pressable(scaleTo = 0.85f, label = "Back", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Back, p.label, size = 22.dp, contentDescription = "Back") }
            Text(
                name, Type.headline, maxLines = 1,
                modifier = Modifier.weight(1f).graphicsLayer { alpha = titleA; translationY = (1f - titleA) * 6.dp.toPx() },
            )
        }
    }
    }
}

/** New · Popular, as two pills; the chosen one is filled. */
@Composable
private fun SortBar(sort: ChannelSort, onPick: (ChannelSort) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().background(p.bg).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelSort.entries.forEach { s ->
            val on = s == sort
            val k by animateFloatAsState(if (on) 1f else 0f, Motion.snappy(), label = "pill")
            Box(
                Modifier
                    .pressable(scaleTo = 0.94f, label = s.label) { if (!on) onPick(s) }
                    .clip(RoundedCornerShape(50))
                    .background(androidx.compose.ui.graphics.lerp(p.fill, p.label, k))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, Type.subheadStrong, color = androidx.compose.ui.graphics.lerp(p.label, p.bg, k), maxLines = 1)
            }
        }
    }
}

private fun subscribers(n: Long): String = when {
    n < 1_000 -> "$n subscribers"
    n < 1_000_000 -> trim(n / 1_000.0) + "K subscribers"
    n < 1_000_000_000 -> trim(n / 1_000_000.0) + "M subscribers"
    else -> trim(n / 1_000_000_000.0) + "B subscribers"
}

private fun trim(d: Double) = if (d >= 10) d.toLong().toString() else "%.1f".format(d).removeSuffix(".0")

/** Slides in from the right over what was there, which sinks back a little; back slides it away. */
@Stable
class ChannelMotion {
    val shown = Animatable(0f)
    suspend fun open() { shown.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 420f)) }
    suspend fun close() { shown.animateTo(0f, tween(240, easing = Motion.inCubic)) }
}
