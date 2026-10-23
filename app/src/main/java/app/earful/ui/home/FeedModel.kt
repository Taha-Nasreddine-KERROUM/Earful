package app.earful.ui.home

import androidx.compose.runtime.getValue
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

/**
 * Keeps "For you" moving: pull down for fresh picks based on what you listened to last,
 * scroll down and it keeps branching out from older listens and from the feed itself.
 */
class FeedModel : ViewModel() {
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    private val usedSeeds = HashSet<String>()

    fun refresh(onFail: (String) -> Unit) {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val seeds = Library.recentListened(3)
            if (seeds.isNotEmpty()) {
                val lists = seeds.map { id -> async { runCatching { YouTube.related(id) }.getOrNull() } }.awaitAll()
                if (lists.all { it == null }) onFail("Couldn't refresh. Check your connection.")
                else {
                    Library.refreshForYou(interleave(lists.map { it.orEmpty().take(12) }))
                    usedSeeds.clear()
                }
            }
            // Keep the spinner up long enough to read as "done", never a flicker.
            val left = 650 - (System.currentTimeMillis() - started)
            if (left > 0) delay(left)
            refreshing = false
        }
    }

    fun loadMore() {
        if (loadingMore || refreshing) return
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

    /** Round-robin so the top of the feed mixes all your recent listens instead of one. */
    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = ArrayList<Video>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }
}
