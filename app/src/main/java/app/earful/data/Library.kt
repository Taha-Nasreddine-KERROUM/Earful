package app.earful.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the app remembers, in one small JSON file (a few KB):
 * saved-for-later, a short "For you" pool built from what you listen to, and recent searches.
 * No account, nothing leaves the phone.
 */
object Library {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _saved = MutableStateFlow<List<Video>>(emptyList())
    val saved: StateFlow<List<Video>> = _saved.asStateFlow()

    private val _forYou = MutableStateFlow<List<Video>>(emptyList())
    val forYou: StateFlow<List<Video>> = _forYou.asStateFlow()

    private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val listened = ArrayDeque<String>() // ids, newest first

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        runCatching { load() }
        // Coalesce bursts of changes into one write (fewer flash writes, less wake time).
        combine(_saved, _forYou, _recentSearches) { _, _, _ -> }
            .drop(1).debounce(600).onEach { runCatching { persist() } }.launchIn(scope)
    }

    fun isSaved(id: String) = _saved.value.any { it.id == id }

    fun toggleSaved(video: Video): Boolean {
        var nowSaved = false
        _saved.update { list ->
            if (list.any { it.id == video.id }) list.filterNot { it.id == video.id }
            else { nowSaved = true; listOf(video) + list }
        }
        return nowSaved
    }

    fun removeSaved(id: String) = _saved.update { l -> l.filterNot { it.id == id } }

    /** Called when something is played: its related videos seed the "For you" shelf for free. */
    fun onListened(video: Video, related: List<Video>) {
        val played = synchronized(listened) {
            listened.remove(video.id); listened.addFirst(video.id)
            while (listened.size > 50) listened.removeLast()
            listened.toHashSet()
        }
        _forYou.update { old ->
            (related.take(14) + old).distinctBy { it.id }.filterNot { it.id in played }.take(MAX_FEED)
        }
    }

    private const val MAX_FEED = 150

    /** Most recently listened video ids, newest first. */
    fun recentListened(n: Int): List<String> = synchronized(listened) { listened.take(n) }

    /** Pull-to-refresh: fresh suggestions go on top, what was there stays below. */
    fun refreshForYou(fresh: List<Video>) {
        val played = synchronized(listened) { listened.toHashSet() }
        _forYou.update { old -> (fresh + old).distinctBy { it.id }.filterNot { it.id in played }.take(MAX_FEED) }
    }

    /** Infinite scroll: adds to the bottom; returns how many were actually new. */
    fun appendForYou(more: List<Video>): Int {
        val played = synchronized(listened) { listened.toHashSet() }
        var added = 0
        _forYou.update { old ->
            val have = old.mapTo(HashSet()) { it.id }
            val extra = more.filter { it.id !in have && it.id !in played }.distinctBy { it.id }
            added = extra.size
            old + extra // no cap while scrolling; trimmed on the next refresh/listen
        }
        return added
    }

    /** Next video to branch out from: older listens first, then videos already in the feed. */
    fun nextSeed(used: Set<String>): String? =
        synchronized(listened) { listened.drop(3).firstOrNull { it !in used } }
            ?: _forYou.value.firstOrNull { it.id !in used }?.id

    fun addSearch(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        _recentSearches.update { (listOf(t) + it.filterNot { s -> s.equals(t, true) }).take(12) }
    }

    fun removeSearch(q: String) = _recentSearches.update { it - q }

    fun clearSearches() { _recentSearches.value = emptyList() }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        _saved.value = o.optJSONArray("saved").videos()
        _forYou.value = o.optJSONArray("forYou").videos()
        _recentSearches.value = o.optJSONArray("searches")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        o.optJSONArray("listened")?.let { a -> repeat(a.length()) { listened.addLast(a.getString(it)) } }
    }

    private fun persist() {
        val o = JSONObject()
            .put("saved", JSONArray(_saved.value.map { it.toJson() }))
            .put("forYou", JSONArray(_forYou.value.map { it.toJson() }))
            .put("searches", JSONArray(_recentSearches.value))
            .put("listened", JSONArray(synchronized(listened) { listened.toList() }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file) // atomic: never a half-written library
    }

    private fun JSONArray?.videos(): List<Video> =
        if (this == null) emptyList()
        else (0 until length()).mapNotNull { runCatching { Video.fromJson(getJSONObject(it)) }.getOrNull() }
}
