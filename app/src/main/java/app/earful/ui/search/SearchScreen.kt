package app.earful.ui.search

import app.earful.ui.hole.eatable
import app.earful.ui.hole.HoleLayer
import app.earful.ui.hole.LocalHoleLayer
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.isImeVisible
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.earful.data.Library
import app.earful.data.Paged
import app.earful.data.Video
import app.earful.data.YouTube
import app.earful.ui.components.Icon
import app.earful.ui.components.Icons
import app.earful.ui.components.Text
import app.earful.ui.components.VideoCard
import app.earful.ui.components.VideoCardSkeleton
import app.earful.ui.components.pressable
import app.earful.ui.components.blockTouches
import app.earful.ui.home.Message
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Motion
import app.earful.ui.theme.Type
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SearchModel : ViewModel() {
    var field by mutableStateOf(TextFieldValue(""))
    var suggestions by mutableStateOf<List<String>>(emptyList())
    var submitted by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Video>>(emptyList())
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private var next: (suspend () -> Paged<Video>)? = null
    private var loadingMore = false
    private var job: Job? = null

    fun submit(q: String) {
        val query = q.trim(); if (query.isEmpty()) return
        Library.addSearch(query)
        field = TextFieldValue(query, TextRange(query.length))
        submitted = query; loading = true; failed = false; results = emptyList(); next = null
        job?.cancel()
        job = viewModelScope.launch {
            runCatching { YouTube.search(query) }
                .onSuccess { results = it.items; next = it.next }
                .onFailure { failed = true }
            loading = false
        }
    }

    fun more() {
        val n = next ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            runCatching { n() }.onSuccess { page ->
                results = (results + page.items).distinctBy { it.id }; next = page.next
            }
            loadingMore = false
        }
    }

    @OptIn(FlowPreview::class)
    suspend fun watchSuggestions() {
        snapshotFlow { field.text }
            .distinctUntilChanged()
            .debounce(180)
            .collectLatest { t ->
                if (t == submitted) return@collectLatest
                submitted = null
                suggestions = if (t.isBlank() || Video.idFromUrl(t) != null) emptyList() else YouTube.suggestions(t)
            }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    bottomPadding: Dp,
    motion: SearchMotion,
    onPlay: (Video) -> Unit,
    onQueue: (Video) -> Unit,
    onClose: () -> Unit,
    /** Bumped to put the cursor back in the field (and the keyboard up). */
    refocus: Int = 0,
    model: SearchModel = viewModel(),
) {
    val p = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val recent by Library.recentSearches.collectAsStateWithLifecycle()
    val saved by Library.saved.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.id } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // The keyboard comes up once the field has nearly grown out of the magnifier, not while it's
    // growing: a keyboard rising resizes the whole window, and doing both at once made the
    // field's growth stutter and skip on slower phones.
    LaunchedEffect(Unit) {
        if (model.submitted == null) {
            snapshotFlow { motion.morph.value >= 0.6f || motion.field.value >= 1f }.first { it }
            focus.requestFocus()
        }
    }
    LaunchedEffect(Unit) { model.watchSuggestions() }

    // Back from the player to search you were typing in (see AppRoot): carry on typing.
    LaunchedEffect(refocus) {
        if (refocus > 0) { focus.requestFocus(); keyboard?.show() }
    }

    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= list.layoutInfo.totalItemsCount - 5 }
            .collect { if (model.submitted != null) model.more() }
    }

    fun go(q: String) { keyboard?.hide(); model.submit(q) }

    // Clearing every recent search: one after another from the top, each slips under the water.
    val clearing = remember { Animatable(0f) } // seconds since Clear was tapped
    var clearingOn by remember { mutableStateOf(false) }
    val clearPop = remember { Animatable(1f) }
    fun clearUnder(i: Int): Float =
        if (!clearingOn) 0f else Motion.inCubic.transform(((clearing.value - i * 0.035f) / 0.3f).coerceIn(0f, 1f))
    // Once they're all gone (and only then, so none flashes back), the next ones start fresh.
    LaunchedEffect(recent.isEmpty()) {
        if (recent.isEmpty() && clearingOn) { clearingOn = false; clearing.snapTo(0f) }
    }

    // Search covers Home: touches anywhere on it stay here, never reaching the videos behind.
    Box(Modifier.fillMaxSize().eatable(page = true, fill = p.bg, layer = HoleLayer.Search).blockTouches()) {
    CompositionLocalProvider(LocalHoleLayer provides HoleLayer.Search) {
        // Search's own background rises over Home, which goes under.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.bg.value }.background(p.bg))
        Column(Modifier.fillMaxSize().padding(top = statusTop)) {
            // ----- field -----------------------------------------------------------------------
            Row(Modifier.fillMaxWidth().eatable().height(56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp)
                        .graphicsLayer {
                            alpha = motion.back.value
                            translationX = (1f - motion.back.value) * 10.dp.toPx()
                        }
                        .pressable(scaleTo = 0.85f, label = "Back", onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Back, p.label, size = 22.dp, contentDescription = "Back")
                }
                Row(
                    Modifier.weight(1f).height(40.dp)
                        .onPlaced { motion.target = it }
                        .graphicsLayer { alpha = motion.field.value }
                        .clip(RoundedCornerShape(12.dp)).background(p.fill).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Search, p.secondary, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (model.field.text.isEmpty()) Text(
                            "Search YouTube", Type.body, color = p.secondary, maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = motion.hint.value },
                        )
                        BasicTextField(
                            value = model.field,
                            onValueChange = { model.field = it },
                            singleLine = true,
                            textStyle = Type.body.copy(color = p.label),
                            cursorBrush = SolidColor(p.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { go(model.field.text) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (model.field.text.isNotEmpty()) {
                        Box(
                            Modifier.size(28.dp).pressable(scaleTo = 0.8f, label = "Clear") {
                                model.field = TextFieldValue(""); model.submitted = null; focus.requestFocus()
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(p.tertiary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Close, p.bg, size = 12.dp)
                            }
                        }
                    }
                }
            }

            // ----- body ------------------------------------------------------------------------
            val submitted = model.submitted
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
                modifier = Modifier.fillMaxSize().imePadding(),
            ) {
                if (submitted == null) {
                    val pasted = Video.idFromUrl(model.field.text)
                    if (pasted != null) item {
                        SuggestionRow(Icons.Search, "Listen to this link", onFill = null, modifier = Modifier.floatIn(motion, 0)) {
                            keyboard?.hide()
                            onPlay(Video(pasted, "Loading…", "", Video.fallbackThumb(pasted), 0))
                        }
                    }
                    if (model.field.text.isBlank()) {
                        if (recent.isNotEmpty()) item(key = "recent") {
                            Row(
                                Modifier.fillMaxWidth().floatIn(motion, 0).goUnder { clearUnder(0) }
                                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Recent", Type.headline, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier.graphicsLayer { scaleX = clearPop.value; scaleY = clearPop.value }
                                        .pressable {
                                            if (!clearingOn) {
                                                scope.launch { clearPop.snapTo(0.86f); clearPop.animateTo(1f, Motion.tapPop()) }
                                                scope.launch {
                                                    clearingOn = true
                                                    val total = 0.3f + (recent.size + 1) * 0.035f
                                                    clearing.snapTo(0f)
                                                    clearing.animateTo(total, tween((total * 1000).roundToInt(), easing = LinearEasing))
                                                    Library.clearSearches()
                                                }
                                            }
                                        }.padding(8.dp)
                                ) {
                                    Text("Clear", Type.subhead, color = p.accent)
                                }
                            }
                        }
                        itemsIndexed(recent, key = { _, q -> "r$q" }) { i, q ->
                            // Removing one: it slips under the water, and the ones below glide up.
                            val under = remember { Animatable(0f) }
                            val gone = remember { Animatable(0f) }
                            SuggestionRow(
                                Icons.Clock, q, onFill = null,
                                onRemove = {
                                    if (under.value == 0f && !clearingOn) scope.launch {
                                        launch { under.animateTo(1f, tween(260, easing = Motion.inCubic)) }
                                        delay(120)
                                        gone.animateTo(1f, Motion.glide())
                                        Library.removeSearch(q)
                                    }
                                },
                                modifier = Modifier.closeUp { gone.value }.floatIn(motion, i + 1)
                                    .goUnder { maxOf(under.value, clearUnder(i + 1)) },
                            ) { go(q) }
                        }
                    } else {
                        itemsIndexed(model.suggestions, key = { _, s -> "s$s" }) { i, s ->
                            SuggestionRow(Icons.Search, s, onFill = {
                                model.field = TextFieldValue("$s ", TextRange(s.length + 1))
                            }, modifier = Modifier.floatIn(motion, i)) { go(s) }
                        }
                    }
                } else when {
                    model.loading -> items(3) { VideoCardSkeleton() }
                    model.failed -> item { Message("Search didn't go through", "Check your connection.", "Try again") { model.submit(submitted) } }
                    model.results.isEmpty() -> item { Message("No results", "Try different words.", null) {} }
                    else -> itemsIndexed(model.results, key = { _, v -> v.id }) { i, v ->
                        VideoCard(
                            v, saved = v.id in savedIds, onOpen = { onPlay(v) }, onToggleSave = { Library.toggleSaved(v) }, onQueue = { onQueue(v) },
                            modifier = Modifier.animateItem().floatIn(motion, i),
                        )
                    }
                }
            }
        }
    }
    }
}

/** Rows float up one after another when search opens, and drop away as it closes. */
@Composable
private fun Modifier.floatIn(motion: SearchMotion, index: Int): Modifier {
    val rise = remember { Animatable(if (motion.entering) 0f else 1f) }
    val show = remember { Animatable(if (motion.entering) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (rise.value < 1f) {
            delay(140L + index.coerceIn(0, 12) * 35L)
            launch { show.animateTo(1f, tween(220, easing = Motion.outCubic)) }
            rise.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 170f))
        }
    }
    return this.graphicsLayer {
        translationY = (1f - rise.value) * 18.dp.toPx() + motion.leave.value * 8.dp.toPx()
        alpha = show.value * (1f - motion.leave.value)
    }
}

/** Going under the water: a little smaller, softer and dimmer until it's gone. */
private fun Modifier.goUnder(amount: () -> Float): Modifier = graphicsLayer {
    val u = amount()
    if (u > 0f) {
        val s = 1f - 0.06f * u
        scaleX = s; scaleY = s
        alpha = 1f - u
        if (Build.VERSION.SDK_INT >= 31) {
            val r = 2.5.dp.toPx() * u
            renderEffect = if (r > 0.3f) BlurEffect(r, r, TileMode.Decal) else null
        }
    } else {
        renderEffect = null
    }
}

/** Closes the gap a row leaves: its height shrinks so the rows below glide up. */
private fun Modifier.closeUp(amount: () -> Float): Modifier = layout { m, c ->
    val pl = m.measure(c)
    val h = (pl.height * (1f - amount())).roundToInt().coerceAtLeast(0)
    layout(pl.width, h) { pl.place(0, 0) }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onFill: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val xPop = remember { Animatable(1f) }
    Row(
        modifier.eatable().fillMaxWidth().pressable(scaleTo = 0.985f, onClick = onClick).padding(start = 16.dp, end = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, p.secondary, size = 18.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, Type.body, maxLines = 1, modifier = Modifier.weight(1f))
        when {
            onFill != null -> Box(Modifier.size(44.dp).pressable(scaleTo = 0.85f, label = "Fill in", onClick = onFill), contentAlignment = Alignment.Center) {
                Icon(Icons.ArrowUpLeft, p.tertiary, size = 18.dp)
            }
            onRemove != null -> Box(
                Modifier.size(44.dp)
                    .graphicsLayer { scaleX = xPop.value; scaleY = xPop.value }
                    .pressable(scaleTo = 0.85f, label = "Remove") {
                        scope.launch { xPop.snapTo(0.78f); xPop.animateTo(1f, Motion.tapPop()) }
                        onRemove()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Close, p.tertiary, size = 16.dp)
            }
        }
    }
}
