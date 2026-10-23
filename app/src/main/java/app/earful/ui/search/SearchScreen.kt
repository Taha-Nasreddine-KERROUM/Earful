package app.earful.ui.search

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import app.earful.ui.home.Message
import app.earful.ui.theme.LocalPalette
import app.earful.ui.theme.Type
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

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

@Composable
fun SearchScreen(bottomPadding: Dp, onPlay: (Video) -> Unit, onQueue: (Video) -> Unit, onClose: () -> Unit, model: SearchModel = viewModel()) {
    val p = LocalPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val recent by Library.recentSearches.collectAsStateWithLifecycle()
    val saved by Library.saved.collectAsStateWithLifecycle()
    val savedIds = remember(saved) { saved.mapTo(HashSet()) { it.id } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(Unit) {
        if (model.submitted == null) focus.requestFocus()
        model.watchSuggestions()
    }

    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= list.layoutInfo.totalItemsCount - 5 }
            .collect { if (model.submitted != null) model.more() }
    }

    fun go(q: String) { keyboard?.hide(); model.submit(q) }

    Column(Modifier.fillMaxSize().background(p.bg).padding(top = statusTop)) {
        // ----- field -----------------------------------------------------------------------
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).pressable(scaleTo = 0.85f, label = "Back", onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Back, p.label, size = 22.dp, contentDescription = "Back")
            }
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(p.fill).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Search, p.secondary, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (model.field.text.isEmpty()) Text("Search YouTube", Type.body, color = p.secondary, maxLines = 1)
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
                    SuggestionRow(Icons.Search, "Listen to this link", onFill = null) {
                        keyboard?.hide()
                        onPlay(Video(pasted, "Loading…", "", Video.fallbackThumb(pasted), 0))
                    }
                }
                if (model.field.text.isBlank()) {
                    if (recent.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent", Type.headline, modifier = Modifier.weight(1f))
                            Box(Modifier.pressable { Library.clearSearches() }.padding(8.dp)) {
                                Text("Clear", Type.subhead, color = p.accent)
                            }
                        }
                    }
                    items(recent, key = { "r$it" }) { q ->
                        SuggestionRow(Icons.Clock, q, onFill = null, onRemove = { Library.removeSearch(q) }) { go(q) }
                    }
                } else {
                    items(model.suggestions, key = { "s$it" }) { s ->
                        SuggestionRow(Icons.Search, s, onFill = {
                            model.field = TextFieldValue("$s ", TextRange(s.length + 1))
                        }) { go(s) }
                    }
                }
            } else when {
                model.loading -> items(3) { VideoCardSkeleton() }
                model.failed -> item { Message("Search didn't go through", "Check your connection.", "Try again") { model.submit(submitted) } }
                model.results.isEmpty() -> item { Message("No results", "Try different words.", null) {} }
                else -> items(model.results, key = { it.id }) { v ->
                    VideoCard(v, saved = v.id in savedIds, onOpen = { onPlay(v) }, onToggleSave = { Library.toggleSaved(v) }, onQueue = { onQueue(v) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onFill: (() -> Unit)?,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.985f, onClick = onClick).padding(start = 16.dp, end = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, p.secondary, size = 18.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, Type.body, maxLines = 1, modifier = Modifier.weight(1f))
        when {
            onFill != null -> Box(Modifier.size(44.dp).pressable(scaleTo = 0.85f, label = "Fill in", onClick = onFill), contentAlignment = Alignment.Center) {
                Icon(Icons.ArrowUpLeft, p.tertiary, size = 18.dp)
            }
            onRemove != null -> Box(Modifier.size(44.dp).pressable(scaleTo = 0.85f, label = "Remove", onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Close, p.tertiary, size = 16.dp)
            }
        }
    }
}
