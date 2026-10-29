package app.earful.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.earful.data.Video
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Immutable
data class NowPlaying(
    val video: Video,
    val playing: Boolean,
    /** True while the play intent is on but sound isn't flowing yet (loading). */
    val waiting: Boolean,
    val ended: Boolean,
    val durationMs: Long,
)

/** One line of the queue as the UI shows it. [index] is its place in the playlist. */
@Immutable
data class QueueEntry(val video: Video, val index: Int, val passed: Boolean, val current: Boolean, val uid: Long)

@Immutable
data class QueueState(val entries: List<QueueEntry> = emptyList(), val currentIndex: Int = -1) {
    val upcoming get() = entries.count { !it.passed && !it.current }
}

/** What happened when a video was added to the queue — the UI turns this into a toast. */
enum class EnqueueResult { Added, AlreadyQueued, StartedPlaying }

/**
 * The UI's handle on the playback service. It's connected only while the app is on screen,
 * so nothing in the UI keeps the phone awake once you leave.
 */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()

    /** Item the user just tapped, shown instantly before the controller has even connected. */
    private var optimistic: Video? = null

    /** Stable ids for queue rows (same video can be queued twice; rows must stay distinct). */
    private var uidCounter = 0L
    private val uids = HashMap<String, Long>() // key: playlist identity → uid

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
        override fun onPlayerError(error: PlaybackException) {
            val c = controller ?: return
            val id = c.currentMediaItem?.mediaId
            val reason = id?.let { AudioCache.failures[it] }
            if (reason != null && c.hasNextMediaItem()) return // the service skips it
            PlaybackEvents.report(reason ?: "Playback stopped. Check your connection and try again.")
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            val todo = pending.toList(); pending.clear()
            todo.forEach { it(c) }
            publish()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null; controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run { pending += block; connect() }
    }

    private fun itemFor(video: Video) =
        MediaItem.Builder().setMediaId(video.id).setMediaMetadata(PlaybackService.metadataFor(video)).build()

    /**
     * Plays [video] now. Coming from outside the queue, this starts a fresh queue
     * (the old one is dropped, along with its downloads).
     */
    fun play(video: Video) {
        val c0 = controller
        if (c0 != null && c0.currentMediaItem?.mediaId == video.id && c0.playbackState != Player.STATE_IDLE &&
            PlaybackEvents.error.value == null
        ) {
            c0.play(); return // already the one playing: just resume, keep the queue
        }
        PlaybackEvents.clearError()
        AudioCache.forget(video.id)
        optimistic = video
        _state.value = NowPlaying(video, playing = false, waiting = true, ended = false, durationMs = video.durationSec * 1000)
        withController { c ->
            c.setMediaItem(itemFor(video))
            c.prepare()
            c.play()
        }
    }

    /** Adds [video] to the end of the queue. With nothing playing, it simply starts playing. */
    fun enqueue(video: Video): EnqueueResult {
        val c = controller
        val q = _queue.value
        if (_state.value == null || c == null || c.mediaItemCount == 0) {
            play(video); return EnqueueResult.StartedPlaying
        }
        if (q.entries.any { !it.passed && it.video.id == video.id }) return EnqueueResult.AlreadyQueued
        c.addMediaItem(itemFor(video))
        return EnqueueResult.Added
    }

    /** Jumps to a queue row (passed or upcoming). The queue stays as it is. */
    fun jumpTo(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount) return@withController
        PlaybackEvents.clearError()
        c.getMediaItemAt(index).mediaId.let { id -> if (AudioCache.failures.containsKey(id)) AudioCache.forget(id) }
        c.seekTo(index, 0)
        c.prepare()
        c.play()
    }

    /** Takes [videoId] back out of the upcoming part of the queue. */
    fun dequeue(videoId: String) {
        val q = _queue.value
        val e = q.entries.firstOrNull { it.video.id == videoId && !it.passed && !it.current } ?: return
        remove(e.index)
    }

    /** Removes a row from the queue (never the one playing). */
    fun remove(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount || index == c.currentMediaItemIndex) return@withController
        c.removeMediaItem(index)
    }

    /** "Try again" after an error: same track, same queue. */
    fun retry(video: Video) {
        val c = controller
        if (c == null || c.currentMediaItem == null) { play(video); return }
        PlaybackEvents.clearError()
        AudioCache.forget(video.id)
        c.prepare()
        c.play()
    }

    fun togglePlay() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) return@withController // the replay button does that
        if (c.playWhenReady) c.pause() else c.play()
    }

    /** The last video ended: play it again from the start. */
    fun replay() = withController { c ->
        PlaybackEvents.clearError()
        c.seekTo(0)
        c.play()
    }

    fun seekBy(deltaMs: Long) = withController { c ->
        val dur = c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, dur))
        if (deltaMs < 0 && c.playbackState == Player.STATE_ENDED) c.play()
    }

    fun seekTo(ms: Long) = withController { c -> c.seekTo(ms.coerceAtLeast(0)) }

    fun positionMs(): Long = controller?.currentPosition ?: 0L

    /** Closing the player: stop, forget the queue, delete every downloaded file. */
    fun close() {
        optimistic = null
        _state.value = null
        _queue.value = QueueState()
        PlaybackEvents.clearError()
        controller?.run { stop(); clearMediaItems() }
        AudioCache.releaseAll()
    }

    private fun videoOf(item: MediaItem): Video {
        val md = item.mediaMetadata
        return md.extras?.let { PlaybackService.videoFromExtras(item.mediaId, it) }
            ?: Video(item.mediaId, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
                md.artworkUri?.toString() ?: Video.fallbackThumb(item.mediaId), 0)
    }

    private fun publish() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null) {
            if (optimistic == null) { _state.value = null; _queue.value = QueueState() }
            return
        }
        optimistic = null
        val video = videoOf(item)
        val ended = c.playbackState == Player.STATE_ENDED
        _state.value = NowPlaying(
            video = video,
            playing = c.isPlaying,
            waiting = c.playWhenReady && !c.isPlaying && !ended &&
                (c.playbackState == Player.STATE_BUFFERING || c.playbackState == Player.STATE_IDLE),
            ended = ended,
            durationMs = c.duration.takeIf { it > 0 } ?: (video.durationSec * 1000),
        )

        // Queue rows. A row keeps its uid while it stays at the same place relative to its
        // neighbours, so removing one row animates just that row.
        val cur = c.currentMediaItemIndex
        val seen = HashMap<String, Int>()
        val entries = (0 until c.mediaItemCount).map { i ->
            val it = c.getMediaItemAt(i)
            val n = seen.merge(it.mediaId, 1, Int::plus)!!
            val key = "${it.mediaId}#$n"
            val uid = uids.getOrPut(key) { ++uidCounter }
            QueueEntry(videoOf(it), i, passed = i < cur, current = i == cur, uid = uid)
        }
        _queue.value = QueueState(entries, cur)
    }
}
