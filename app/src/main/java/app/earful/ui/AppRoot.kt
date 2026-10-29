package app.earful.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import app.earful.ui.theme.Motion
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import app.earful.ui.hole.Hole
import app.earful.ui.hole.HoleLayer
import app.earful.ui.hole.HoleLens
import app.earful.ui.hole.HoleOverlay
import app.earful.ui.hole.StarField
import app.earful.ui.hole.eatable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.Library
import app.earful.data.Video
import app.earful.playback.PlayerConnection
import app.earful.playback.EnqueueResult
import app.earful.playback.PlaybackEvents
import app.earful.ui.components.Text
import app.earful.ui.components.Water
import app.earful.ui.components.pressable
import app.earful.ui.home.HomeScreen
import app.earful.ui.player.PlayerSheet
import app.earful.ui.player.SheetState
import app.earful.ui.saved.SavedScreen
import app.earful.ui.search.SearchMorph
import app.earful.ui.search.SearchMotion
import app.earful.ui.search.SearchScreen
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Asked once per launch at most (see AppRoot). */
private var askedBatteryThisLaunch = false

class Toast(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

/** Things outside Compose (share sheet, notification tap) that the UI should react to. */
class AppSignals {
    var playRequest by mutableStateOf<Video?>(null)
    var openPlayer by mutableStateOf(0)
}

@Composable
fun AppRoot(conn: PlayerConnection, signals: AppSignals) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheet = remember { SheetState() }
    val np by conn.state.collectAsStateWithLifecycle()
    // Home and Saved sit side by side: swipe between them, or tap the tab bar.
    val pager = rememberPagerState { 2 }
    val tab = if (pager.targetPage == 0) Tab.Home else Tab.Saved
    val homeList = rememberLazyListState()
    var homeTopSignal by remember { mutableIntStateOf(0) }
    fun goTo(t: Tab) = scope.launch { pager.animateScrollToPage(t.ordinal, animationSpec = Motion.sheet()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val search = remember { SearchMotion() }
    // The tab bar's surface: ripples from taps, landings and things sinking under it.
    val water = remember { Water() }
    LaunchedEffect(water) { water.run() }
    var toast by remember { mutableStateOf<Toast?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val imeInsets = WindowInsets.ime
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeTarget = WindowInsets.imeAnimationTarget
    // The mini player rides the keyboard, as one physical motion from the moment the keyboard
    // starts to move (Android says where it will end up, so nothing waits for the keyboard's
    // position to be reported frame by frame, which is late on some phones). It follows the
    // keyboard's own curve and timing:
    //  · going up it rests on the keyboard like a weight on a lift: pushed up with it, and when
    //    the keyboard brakes it carries on a little by its own momentum and settles back on it;
    //  · going down it rides the keyboard until it's nearly down, then drops the last bit by its
    //    own momentum and dips a little below its place before settling, like landing after a
    //    minimise.
    // So it never stops before the bounce: the bounce is the end of the same movement, and its
    // size comes from how far and fast it moved.
    val keyboardRide = remember { mutableFloatStateOf(0f) } // px above the bottom edge
    LaunchedEffect(imeTarget) {
        snapshotFlow { imeTarget.getBottom(density).toFloat() }.collectLatest { target -> rideKeyboard(keyboardRide, target) }
    }
    val tabBarHeight = 52.dp + navBottom
    val bottomBar by animateDpAsState(if (searchOpen) navBottom else tabBarHeight, label = "bar")
    val contentBottom = bottomBar + if (np != null) 72.dp else 0.dp
    // How far the tab bar sinks while search is open (dp): all the way under.
    val barSink = tabBarHeight.value + 18f

    // Restored with search open (the screen rotated): show it straight away.
    LaunchedEffect(Unit) { if (searchOpen && !search.open) search.snapOpen(barSink) }

    fun openSearch() {
        if (searchOpen) return
        searchOpen = true
        scope.launch { search.open(barSink) }
    }

    fun closeSearch() {
        keyboard?.hide()
        if (!searchOpen) return
        searchOpen = false
        scope.launch { search.close() }
    }

    // The queue button is a toggle: on = waiting in the queue, off = not.
    val queueState by conn.queue.collectAsStateWithLifecycle()
    val queuedIds = remember(queueState) { queueState.entries.filter { !it.passed }.mapTo(HashSet()) { it.video.id } }

    fun enqueue(v: Video) {
        // Read the queue as it is right now: the lists can hold on to this function from an
        // earlier moment, and an old copy of the queue would never see the video as queued.
        val q = conn.queue.value
        val current = q.entries.firstOrNull { it.current }?.video?.id
        val waiting = q.entries.any { !it.passed && !it.current && it.video.id == v.id }
        when {
            v.id == current -> toast = Toast("Playing now")
            waiting -> { conn.dequeue(v.id); toast = Toast("Removed from queue") }
            else -> when (conn.enqueue(v)) {
                EnqueueResult.Added -> toast = Toast("Added to queue")
                EnqueueResult.AlreadyQueued -> { conn.dequeue(v.id); toast = Toast("Removed from queue") }
                EnqueueResult.StartedPlaying -> Unit // the mini player appearing says it all
            }
        }
    }

    // One-off messages from playback (e.g. a queued video YouTube refused was skipped).
    val notice by PlaybackEvents.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { notice?.let { n -> toast = Toast(n.text, n.action, onAction = n.onAction) } }

    var playing by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun play(v: Video) {
        keyboard?.hide()
        // A different video while the mini player is showing: it sinks away under the tab bar
        // first (still showing what was playing), then the new one's page rises.
        val makeWay = np != null && np?.video?.id != v.id && sheet.miniShowing
        playing?.cancel()
        playing = scope.launch {
            if (makeWay) sheet.makeWay()
            conn.play(v)
            // Straight to the full page (the mini player only shows once you minimise it).
            sheet.openDirect()
        }
    }

    // A channel's page, over Home or search.
    var channel by remember { mutableStateOf<app.earful.ui.channel.ChannelRef?>(null) }
    val channelMotion = remember { app.earful.ui.channel.ChannelMotion() }
    fun openChannel(v: Video) {
        keyboard?.hide()
        val ref = app.earful.ui.channel.ChannelRef(v.channelUrl, v.channel, v.channelAvatar, v)
        scope.launch {
            if (sheet.isExpanded) sheet.collapse()
            if (channel != null) { channelMotion.close() }
            channel = ref
            channelMotion.shown.snapTo(0f)
            channelMotion.open()
        }
    }
    fun closeChannel() {
        if (channel == null) return
        scope.launch { channelMotion.close(); channel = null }
    }
    val channelActions = remember {
        app.earful.ui.components.ChannelActions(open = { openChannel(it) }, listenLive = { play(it) })
    }

    LaunchedEffect(signals.playRequest) { signals.playRequest?.let { play(it); signals.playRequest = null } }
    LaunchedEffect(signals.openPlayer, np != null) {
        if (signals.openPlayer > 0 && np != null) { signals.openPlayer = 0; sheet.expand() }
    }

    // First time the player is minimised, teach the one hidden gesture: swipe down to close.
    LaunchedEffect(Unit) {
        snapshotFlow { sheet.progress.value < 0.01f && sheet.progress.targetValue == 0f && conn.state.value != null }
            .distinctUntilChanged()
            .collect { docked ->
                val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                if (docked && !prefs.getBoolean("hint_close", false)) {
                    delay(500)
                    toast = Toast("Tip: swipe the mini player down to close it")
                    prefs.edit().putBoolean("hint_close", true).apply()
                }
            }
    }

    // Some phones stop "background" apps after a while, music or not. Once something has played
    // and the player is tucked away, ask (at most once per launch, three times in all) to be
    // left alone. Android shows its own yes/no; nothing changes without it.
    LaunchedEffect(Unit) {
        snapshotFlow { conn.state.value?.playing == true && sheet.progress.value < 0.01f }
            .distinctUntilChanged()
            .collect { docked ->
                if (!docked) return@collect
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return@collect
                val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                val asked = prefs.getInt("asked_battery", 0)
                if (asked >= 3 || askedBatteryThisLaunch) return@collect
                delay(1500)
                if (conn.state.value?.playing != true) return@collect
                askedBatteryThisLaunch = true
                prefs.edit().putInt("asked_battery", asked + 1).apply()
                toast = Toast("Keep playing with the screen off?", "Allow", onAction = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                        )
                    }.onFailure {
                        runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                })
            }
    }

    StatusBarFollowsSheet(sheet)

    val playerUp by remember { derivedStateOf { conn.state.value != null && (sheet.progress.value > 0.3f || sheet.direct) } }

    // Leaving search with the keyboard up, straight back to Home: the mini player rides the
    // keyboard down and lands on the tab bar the way it does when you minimise it, with a dip and
    // a ripple on the water where it touches.
    val view = LocalView.current
    LaunchedEffect(Unit) {
        var prev = keyboardRide.floatValue
        var prevT = 0L
        snapshotFlow { keyboardRide.floatValue }.collect { r ->
            val now = System.nanoTime()
            val dock = with(density) { bottomBar.toPx() }
            if (prev > dock + 1f && r <= dock + 1f && !searchOpen && conn.state.value != null &&
                sheet.progress.value < 0.01f && sheet.dismiss.value == 0f
            ) {
                val unit = density.density
                val dt = ((now - prevT) / 1e9f).coerceIn(0.004f, 0.05f)
                val speed = (prev - r) / dt / unit // dp/s
                water.ripple(view.width / 2f, (3f + speed / 400f).coerceIn(3.5f, 7f) * unit, 70f * unit, 11f * unit, 1.1f)
            }
            prev = r; prevT = now
        }
    }

    // How far the player is open (0 while there's none).
    fun playerOpen() = if (np != null) sheet.progress.value.coerceIn(0f, 1f) else 0f

    CompositionLocalProvider(
        app.earful.ui.components.LocalQueuedIds provides queuedIds,
        app.earful.ui.components.LocalChannelActions provides channelActions,
    ) {
    // The black hole (±10 s past +2000 s) shakes all of it, bends the light of all of it, and
    // eats it layer by layer (see ui/hole). None of this costs anything until then.
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = Hole.shakeX; translationY = Hole.shakeY }) {
    Box(Modifier.fillMaxSize().graphicsLayer {
        Hole.frame
        renderEffect = if (Hole.isActive) HoleLens.effect(size.width, size.height) else null
    }) {
    StarField()
    // (the app's own background goes with the bottom page, leaving the stars)
    Box(Modifier.fillMaxSize().drawBehind { if (!Hole.gone(HoleLayer.Page)) drawRect(p.bg) }) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                // Behind the player (rising like the tide) and behind search (Home going under),
                // the pages sink back a little and dim. (No blur: re-blurring a whole screen on
                // every frame is what made it stutter.)
                val q = playerOpen()
                val hu = search.homeUnder.value
                val s = (1f - 0.06f * hu) * (1f - 0.03f * q)
                scaleX = s; scaleY = s
                // Fully covered (by the player, or by search's own background): not drawn at all.
                // (Dimming is a veil drawn over it, never alpha: alpha on a whole page means
                // painting it off-screen first, every frame — the stutter on cheaper phones.)
                alpha = if (!Hole.isActive && ((q > 0.999f && !sheet.water.dragging) || search.bg.value > 0.999f || channelMotion.shown.value > 0.999f)) 0f else 1f
            }.drawWithContent {
                drawContent()
                if (Hole.isActive) return@drawWithContent
                val hu = search.homeUnder.value
                if (hu > 0f) drawRect(p.bg, alpha = 0.5f * hu)
                val ch = channelMotion.shown.value
                if (ch > 0f && !searchOpen) drawRect(Color.Black, alpha = 0.25f * ch.coerceIn(0f, 1f))
            },
            beyondViewportPageCount = 1, // the other page is already laid out: swipes never stutter
            key = { it },
        ) { page ->
          // Depth while swiping: the page you're leaving settles back and dims a little, like
          // a card sliding under the next one, instead of two flat pages scrolling side by side.
          Box(Modifier.fillMaxSize().eatable(page = true, fill = p.bg).graphicsLayer {
              val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
              val a = kotlin.math.abs(off).coerceIn(0f, 1f)
              val s = 1f - 0.05f * a
              scaleX = s; scaleY = s
          }.drawWithContent {
              drawContent()
              val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
              val a = kotlin.math.abs(off).coerceIn(0f, 1f)
              if (a > 0f) drawRect(p.bg, alpha = 0.3f * a)
          }) {
            when (page) {
                0 -> HomeScreen(
                    contentBottom, onPlay = ::play, onOpenSearch = ::openSearch,
                    listState = homeList, topSignal = homeTopSignal,
                    onMessage = { toast = Toast(it) }, onQueue = ::enqueue,
                    search = search,
                )
                else -> SavedScreen(contentBottom, onPlay = ::play, onQueue = ::enqueue, onRemoved = { v ->
                    toast = Toast("Removed from Saved", "Undo", onAction = { Library.toggleSaved(v) })
                })
            }
          }
        }
        // The pages darken a little as the player rises over them.
        if (np != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (Hole.isActive) 0f else 0.18f * playerOpen() }.background(Color.Black))

        if (search.shown) {
            SearchScreen(contentBottom, search, onPlay = ::play, onQueue = ::enqueue, onClose = ::closeSearch, playerUp = playerUp)
        }
        SearchMorph(search)

        channel?.let { ref ->
            app.earful.ui.channel.ChannelScreen(
                ref, contentBottom, onPlay = ::play, onQueue = ::enqueue, onClose = ::closeChannel,
                modifier = Modifier.graphicsLayer {
                    translationX = (1f - channelMotion.shown.value) * size.width
                    shadowElevation = if (channelMotion.shown.value < 1f) 24.dp.toPx() else 0f
                },
            )
        }

        toast?.let { t ->
            ToastPill(t, Modifier.align(Alignment.BottomCenter).padding(bottom = contentBottom + 12.dp)) { toast = null }
        }

        np?.let { state ->
            PlayerSheet(
                np = state, conn = conn, sheet = sheet, bottomBarHeight = bottomBar,
                water = water, tabBarShown = !searchOpen,
                // In search, the mini player rides just above the keyboard, moving with it.
                lift = {
                    val ride = keyboardRide.floatValue
                    // above the dock it rides the keyboard; a dip below 0 is the landing bounce
                    (ride - with(density) { bottomBar.toPx() }).coerceAtLeast(0f) + ride.coerceAtMost(0f)
                },
                onClosed = {
                    conn.close()
                    scope.launch { sheet.progress.snapTo(0f); sheet.dismiss.snapTo(0f) }
                },
            )
        }

        // The tab bar is the water's surface, so it sits over the mini player: whatever dips or
        // sinks goes under it. It slides away as the player rises, and sinks while search is open.
        TabBar(
            tab, { pager.currentPage + pager.currentPageOffsetFraction }, water,
            Modifier.align(Alignment.BottomCenter).graphicsLayer {
                translationY = playerOpen() * (tabBarHeight.toPx() + 8.dp.toPx()) + search.bar.value * density.density
            },
        ) { t ->
            // Tapping Home while already on Home just glides back to the top (no reload).
            if (channel != null) { closeChannel(); goTo(t) }
            else if (t == Tab.Home && tab == Tab.Home) homeTopSignal++ else goTo(t)
        }
    }
    }
    HoleOverlay()
    }
    }

    BackHandler(enabled = searchOpen && !sheet.isExpanded) { closeSearch() }
    BackHandler(enabled = !searchOpen && tab != Tab.Home && !sheet.isExpanded) { goTo(Tab.Home) }
    BackHandler(enabled = channel != null && !sheet.isExpanded) { closeChannel() }
    // Predictive back drags the player down with your thumb, exactly like pulling it.
    PredictiveBackHandler(enabled = np != null && sheet.isExpanded) { events ->
        sheet.water.dragStart()
        try {
            events.collect { e -> sheet.progress.snapTo(1f - e.progress * 0.4f) }
            sheet.water.dragEnd()
            sheet.collapse()
        } catch (e: CancellationException) {
            sheet.water.dragEnd()
            scope.launch { sheet.expand() } // gesture abandoned: spring back open
            throw e
        }
    }
    // With "Up next" open, Back closes just the queue.
    BackHandler(enabled = np != null && sheet.isQueueOpen) { scope.launch { sheet.closeQueue(tapped = true) } }
    // While the black hole is open, Back does nothing (stop tapping and it gives everything back).
    BackHandler(enabled = Hole.isActive) { }
}

@Composable
private fun StatusBarFollowsSheet(sheet: SheetState) {
    val view = LocalView.current
    val dark = LocalPalette.current.isDark
    LaunchedEffect(dark) {
        snapshotFlow { sheet.progress.value > 0.5f }.distinctUntilChanged().collect { overDark ->
            val window = (view.context as? Activity)?.window ?: return@collect
            WindowCompat.getInsetsController(window, view).run {
                isAppearanceLightStatusBars = !dark && !overDark
                isAppearanceLightNavigationBars = !dark && !overDark
            }
        }
    }
}

@Composable
private fun ToastPill(t: Toast, modifier: Modifier, onDone: () -> Unit) {
    var visible by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t.id) {
        visible = true
        delay(if (t.action != null) 3800 else 3000)
        visible = false
        delay(250)
        onDone()
    }
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(200)),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp)
                .shadow(16.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Color(0xF2242426))
                .padding(start = 18.dp, end = if (t.action != null) 6.dp else 18.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.text, Type.subhead, color = Color.White, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            if (t.action != null) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.pressable { t.onAction?.invoke(); visible = false }.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(t.action, Type.subheadStrong, color = Color(0xFFFF6482))
                }
            }
        }
    }
}
