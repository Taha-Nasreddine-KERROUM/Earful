package app.earful.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.earful.data.Library
import app.earful.data.Video
import app.earful.data.YouTube
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps "For you" moving: every visit (opening the app, or coming back to it after a while) and
 * every pull brings a page of picks you haven't seen yet; scroll down and it keeps branching out
 * from older listens and from the feed itself.
 */
class FeedModel : ViewModel() {
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    /** A fresh page is on its way for this visit: what was there last time isn't shown meanwhile. */
    var arriving by mutableStateOf(false); private set
    /** Bumped when a fresh page has landed, so the list goes back to its top. */
    var landed by mutableIntStateOf(0); private set
    private val usedSeeds = HashSet<String>()
    private var lastFresh = 0L

    init { visit(force = true) }

    /**
     * Opening the app (or returning after [AWAY_MS]): a fresh page. Until it's here, placeholders
     * stand in rather than last visit's picks; offline, last visit's picks come back.
     */
    fun visit(force: Boolean = false) {
        if (refreshing || arriving) return
        if (!force && System.currentTimeMillis() - lastFresh < AWAY_MS) return
        if (Library.recentListened(1).isEmpty() && Library.forYou.value.isEmpty()) return
        arriving = true
        viewModelScope.launch {
            val ok = withTimeoutOrNull(9_000) { freshPage() } ?: false
            arriving = false
            if (ok) landed++
        }
    }

    fun refresh(onFail: (String) -> Unit) {
        if (refreshing || arriving) return
        refreshing = true
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val ok = freshPage()
            if (!ok) onFail("Couldn't refresh. Check your connection.")
            // Keep the spinner up long enough to read as "done", never a flicker.
            val left = 650 - (System.currentTimeMillis() - started)
            if (left > 0) delay(left)
            refreshing = false
            if (ok) landed++
        }
    }

    /**
     * A page of picks you haven't seen: what YouTube suggests next to your latest listens and to
     * a few older ones picked at random (so it's never the same few seeds twice), mixed together,
     * leaving out everything that was already on the page and everything you've played.
     */
    private suspend fun freshPage(): Boolean {
        val listened = Library.recentListened(50)
        val seeds = (listened.take(3) + listened.drop(3).shuffled().take(3))
            .ifEmpty { Library.forYou.value.shuffled().take(4).map { it.id } }
        if (seeds.isEmpty()) return false
        val lists = seeds.map { id ->
            viewModelScope.async { runCatching { YouTube.related(id, maxAgeMs = 0) }.getOrNull() }
        }.awaitAll()
        if (lists.all { it == null }) return false
        val seen = Library.forYou.value.take(40).mapTo(HashSet()) { it.id }
        val fresh = interleave(lists.map { l -> l.orEmpty().filter { it.id !in seen }.take(16).shuffled() })
        Library.freshForYou(fresh)
        usedSeeds.clear()
        lastFresh = System.currentTimeMillis()
        return true
    }

    fun loadMore() {
        if (loadingMore || refreshing || arriving) return
        loadingMore = true
        viewModelScope.launch {
            // Try a few seeds so a seed whose suggestions we already have doesn't stall the feed.
            repeat(3) {
                val seed = Library.nextSeed(usedSeeds) ?: return@repeat
                usedSeeds += seed
                val more = runCatching { YouTube.related(seed) }.getOrNull() ?: return@repeat
                if (Library.appendForYou(more) > 0) { loadingMore = false; return@launch }
            }
            loadingMore = false
        }
    }

    /** Round-robin so the top of the feed mixes all the seeds instead of one. */
    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = ArrayList<Video>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }

    companion object {
        /** Away this long, coming back counts as a new visit. */
        const val AWAY_MS = 20 * 60_000L
    }
}
