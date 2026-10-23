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
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.earful.data.Library
import app.earful.data.Video
import app.earful.playback.PlayerConnection
import app.earful.playback.EnqueueResult
import app.earful.playback.PlaybackEvents
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.pressable
import app.earful.ui.home.HomeScreen
import app.earful.ui.player.PlayerSheet
import app.earful.ui.player.SheetState
import app.earful.ui.saved.SavedScreen
import app.earful.ui.search.SearchScreen
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class Tab { Home, Saved }

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
    val sheet = remember { SheetState() }
    val np by conn.state.collectAsStateWithLifecycle()
    // Home and Saved sit side by side: swipe between them, or tap the tab bar.
    val pager = rememberPagerState { 2 }
    val tab = if (pager.targetPage == 0) Tab.Home else Tab.Saved
    val homeList = rememberLazyListState()
    var homeTopSignal by remember { mutableIntStateOf(0) }
    fun goTo(t: Tab) = scope.launch { pager.animateScrollToPage(t.ordinal, animationSpec = Motion.sheet()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabBarHeight = 52.dp + navBottom
    val bottomBar by animateDpAsState(if (searchOpen) navBottom else tabBarHeight, label = "bar")
    val contentBottom = bottomBar + if (np != null) 72.dp else 0.dp

    fun enqueue(v: Video) {
        when (conn.enqueue(v)) {
            EnqueueResult.Added -> toast = Toast("Added to queue")
            EnqueueResult.AlreadyQueued -> toast = Toast("Already in the queue")
            EnqueueResult.StartedPlaying -> Unit // the mini player appearing says it all
        }
    }

    // One-off messages from playback (e.g. a queued video YouTube refused was skipped).
    val notice by PlaybackEvents.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) { notice?.let { n -> toast = Toast(n.text, n.action, onAction = n.onAction) } }

    fun play(v: Video) {
        keyboard?.hide()
        conn.play(v)
        scope.launch { sheet.dismiss.snapTo(0f); sheet.expand() }
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

    StatusBarFollowsSheet(sheet)

    Box(Modifier.fillMaxSize().background(p.bg)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1, // the other page is already laid out: swipes never stutter
            key = { it },
        ) { page ->
          // Depth while swiping: the page you're leaving settles back and dims a little, like
          // a card sliding under the next one, instead of two flat pages scrolling side by side.
          Box(Modifier.fillMaxSize().graphicsLayer {
              val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
              val a = kotlin.math.abs(off).coerceIn(0f, 1f)
              val s = 1f - 0.05f * a
              scaleX = s; scaleY = s
              alpha = 1f - 0.3f * a
          }) {
            when (page) {
                0 -> HomeScreen(
                    contentBottom, onPlay = ::play, onOpenSearch = { searchOpen = true },
                    listState = homeList, topSignal = homeTopSignal,
                    onMessage = { toast = Toast(it) }, onQueue = ::enqueue,
                )
                else -> SavedScreen(contentBottom, onPlay = ::play, onQueue = ::enqueue, onRemoved = { v ->
                    toast = Toast("Removed from Saved", "Undo", onAction = { Library.toggleSaved(v) })
                })
            }
          }
        }

        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 24 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 24 },
        ) {
            SearchScreen(contentBottom, onPlay = ::play, onQueue = ::enqueue, onClose = { keyboard?.hide(); searchOpen = false })
        }

        if (!searchOpen) {
            TabBar(tab, { pager.currentPage + pager.currentPageOffsetFraction }, Modifier.align(Alignment.BottomCenter)) { t ->
                // Tapping Home while already on Home just glides back to the top (no reload).
                if (t == Tab.Home && tab == Tab.Home) homeTopSignal++ else goTo(t)
            }
        }

        toast?.let { t ->
            ToastPill(t, Modifier.align(Alignment.BottomCenter).padding(bottom = contentBottom + 12.dp)) { toast = null }
        }

        np?.let { state ->
            PlayerSheet(
                np = state, conn = conn, sheet = sheet, bottomBarHeight = bottomBar,
                onClosed = {
                    conn.close()
                    scope.launch { sheet.progress.snapTo(0f); sheet.dismiss.snapTo(0f) }
                },
            )
        }
    }

    BackHandler(enabled = searchOpen && !sheet.isExpanded) { keyboard?.hide(); searchOpen = false }
    BackHandler(enabled = !searchOpen && tab != Tab.Home && !sheet.isExpanded) { goTo(Tab.Home) }
    // Predictive back drags the player down with your thumb, exactly like pulling it.
    PredictiveBackHandler(enabled = np != null && sheet.isExpanded) { events ->
        try {
            events.collect { e -> sheet.progress.snapTo(1f - e.progress * 0.4f) }
            sheet.collapse()
        } catch (e: CancellationException) {
            scope.launch { sheet.expand() } // gesture abandoned: spring back open
            throw e
        }
    }
    // With "Up next" open, Back closes just the queue.
    BackHandler(enabled = np != null && sheet.isQueueOpen) { scope.launch { sheet.closeQueue() } }
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
private fun TabBar(selected: Tab, position: () -> Float, modifier: Modifier, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier.fillMaxWidth().background(p.chrome)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(p.separator))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            TabItem("Home", Icons.Home, Icons.HomeFill, selected == Tab.Home, { 1f - position().coerceIn(0f, 1f) }, Modifier.weight(1f)) { onSelect(Tab.Home) }
            TabItem("Saved", Icons.Bookmark, Icons.BookmarkFill, selected == Tab.Saved, { position().coerceIn(0f, 1f) }, Modifier.weight(1f)) { onSelect(Tab.Saved) }
        }
        Spacer(Modifier.height(navBottom))
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    amount: () -> Float, // 0..1: how "on" this tab is, following your finger during a swipe
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    // When a tab becomes active its icon dips and springs back, like a key being pressed.
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) { first = false; return@LaunchedEffect }
        if (selected) { pop.snapTo(0.78f); pop.animateTo(1f, app.earful.ui.theme.Motion.lively()) }
    }
    Column(
        modifier.fillMaxSize().pressable(scaleTo = 0.9f, role = Role.Tab, label = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; translationY = (1f - pop.value) * 14f }) {
            // Outline and filled icons cross-fade with the swipe, so the tab "fills in" as you go.
            Icon(icon, p.secondary, size = 25.dp, contentDescription = label,
                modifier = Modifier.graphicsLayer { alpha = 1f - amount() })
            Icon(iconSelected, p.accent, size = 25.dp,
                modifier = Modifier.graphicsLayer { alpha = amount() })
        }
        Spacer(Modifier.height(2.dp))
        Box {
            val style = Type.caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp))
            Text(label, style, color = p.secondary, modifier = Modifier.graphicsLayer { alpha = 1f - amount() })
            Text(label, style, color = p.accent, modifier = Modifier.graphicsLayer { alpha = amount() })
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

