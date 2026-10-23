package app.earful.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.earful.MainActivity
import app.earful.data.Library
import app.earful.data.Video
import app.earful.data.YouTube
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import app.earful.data.ResolvedAudio
import app.earful.data.AdSegment
import app.earful.data.SponsorBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Out-of-band signals the UI can't get from the player itself. */
object PlaybackEvents {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    internal fun report(msg: String?) { _error.value = msg }
    fun clearError() { _error.value = null }

    /** Short one-off messages for a toast, optionally with an action ("Undo"). */
    class Notice(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    internal fun notify(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _notice.value = Notice(msg, action, onAction)
    }

    /** Known sponsor sections per video id, drawn on the progress bar. */
    private val _ads = MutableStateFlow<Map<String, List<AdSegment>>>(emptyMap())
    val ads: StateFlow<Map<String, List<AdSegment>>> = _ads.asStateFlow()
    internal fun putAds(id: String, segs: List<AdSegment>) { _ads.value = _ads.value + (id to segs) }
}

/**
 * Keeps sound going when you leave the app or lock the phone, and owns the lock-screen /
 * notification controls (artwork, −10 s, play/pause, +10 s, and a draggable progress bar).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // Only an audio renderer: no video decoders are ever created or loaded.
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener))
        }
        // Only the two containers YouTube uses for audio, so nothing else is probed.
        val extractors = ExtractorsFactory { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor()) }

        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(CachedAudioDataSource.Factory(), extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true) // headphones out → pause
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keeps downloading with the screen off
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            // Reading from a local file is cheap, so read in big, rare batches: the CPU sleeps longer.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(60_000, 180_000, 750, 1_500)
                    .build()
            )
            .build()

        val player = NoReplayPlayer(exo)
        exoRef = exo

        // The queue is the player's playlist. Whenever the current or next track changes we
        // keep exactly those two on the phone and delete the audio of anything before.
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateWindow(); recordListen(); saveSession() }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) { updateWindow(); saveSession() }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = skipIfUnplayable()
            override fun onIsPlayingChanged(isPlaying: Boolean) = saveSession()
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) = saveSession()
        })
        // While playing, note the position every few seconds (a tiny file write), so even if
        // Android kills the app you come back within a few seconds of where you were.
        scope.launch {
            while (true) {
                delay(5_000)
                if (exoRef?.isPlaying == true) saveSession()
            }
        }
        AudioCache.onResolved = ::onResolved

        val back10 = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val fwd10 = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Forward 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(listOf(back10, fwd10))
            // Artwork is decoded small (512 px) — sharp in the notification, light on memory.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(this, 512)))
            .setCallback(Callback())
            .build()

        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        // Left a video open last time? Bring back the queue and the exact spot, paused.
        Session.load()?.let { saved ->
            exo.setMediaItems(saved.items.map(::itemFor), saved.index, saved.positionMs)
            exo.prepare()
        }
    }

    private fun itemFor(v: Video): MediaItem = MediaItem.Builder()
        .setMediaId(v.id)
        .setUri(CachedAudioDataSource.uriFor(v.id))
        .setMediaMetadata(metadataFor(v))
        .build()

    /** Queue + current video + position → session.json. An empty player means "closed": forget it. */
    private fun saveSession() {
        val p = exoRef ?: return
        if (p.mediaItemCount == 0) { Session.clear(); return }
        val items = (0 until p.mediaItemCount).map { i ->
            val item = p.getMediaItemAt(i)
            item.mediaMetadata.extras?.let { videoFromExtras(item.mediaId, it) }
                ?: Video(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty(), Video.fallbackThumb(item.mediaId), 0)
        }
        Session.save(items, p.currentMediaItemIndex, p.currentPosition)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        saveSession()
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        exoRef = null
        // A video still open → keep its audio for next time. Closed → delete everything.
        if (Session.load() != null) AudioCache.suspendAll() else AudioCache.releaseAll()
        super.onDestroy()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // No previous/next: this is a single-track player. That leaves room for ±10 s.
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                ).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        /** "Resume" from headphones / the system media controls after a restart. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = Session.load()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(saved.items.map(::itemFor), saved.index, saved.positionMs)
            )
        }

        /**
         * The app hands us video ids. Nothing is fetched here: each track is looked up and
         * downloaded only when it becomes the current or the next one.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                MediaItem.Builder()
                    .setMediaId(item.mediaId)
                    .setUri(CachedAudioDataSource.uriFor(item.mediaId))
                    .setMediaMetadata(item.mediaMetadata)
                    .build()
            }.toMutableList()
        )
    }

    private val related = HashMap<String, List<Video>>()
    private val resolved = HashMap<String, Video>()
    private val recorded = HashSet<String>()

    private fun updateWindow() {
        val p = session?.player ?: return
        if (p.mediaItemCount == 0) { scheduleAds(); return }
        val cur = p.currentMediaItem?.mediaId ?: return
        val next = if (p.hasNextMediaItem()) p.getMediaItemAt(p.nextMediaItemIndex).mediaId else null
        AudioCache.retain(listOfNotNull(cur, next))
        next?.let { loadAds(it) }
        scheduleAds()
    }

    // ---- sponsor skipping ----------------------------------------------------------------------
    // Each sponsor section start gets a player "message" at that exact position: ExoPlayer wakes
    // us precisely when playback reaches it. No polling, no timers, no extra battery.

    private var exoRef: ExoPlayer? = null
    private val adMessages = ArrayList<androidx.media3.exoplayer.PlayerMessage>()
    private val undone = HashSet<String>() // sections the user chose to hear (tapped Undo)

    private fun loadAds(id: String) {
        if (PlaybackEvents.ads.value.containsKey(id)) return
        scope.launch {
            val segs = SponsorBlock.segments(id)
            PlaybackEvents.putAds(id, segs)
            if (exoRef?.currentMediaItem?.mediaId == id) scheduleAds()
        }
    }

    private fun scheduleAds() {
        adMessages.forEach { it.cancel() }
        adMessages.clear()
        val exo = exoRef ?: return
        val id = exo.currentMediaItem?.mediaId ?: return
        val segs = PlaybackEvents.ads.value[id] ?: run { loadAds(id); return }
        val index = exo.currentMediaItemIndex
        segs.forEach { seg ->
            adMessages += exo.createMessage { _, _ -> onAdReached(id, seg) }
                .setLooper(android.os.Looper.getMainLooper())
                .setPosition(index, seg.startMs)
                .setDeleteAfterDelivery(false) // fires again if you go back before it
                .send()
        }
        // Already inside one (e.g. a sponsor read in the first seconds)? Skip it now.
        val pos = exo.currentPosition
        segs.firstOrNull { pos >= it.startMs && pos < it.endMs - 1000 }?.let { onAdReached(id, it) }
    }

    private fun onAdReached(id: String, seg: AdSegment) {
        val exo = exoRef ?: return
        if (exo.currentMediaItem?.mediaId != id) return
        val key = "$id:${seg.startMs}"
        if (key in undone) return
        val pos = exo.currentPosition
        if (pos < seg.startMs - 1500 || pos >= seg.endMs - 500) return
        exo.seekTo(seg.endMs)
        val secs = ((seg.endMs - seg.startMs) / 1000).coerceAtLeast(1)
        val what = if (seg.category == "selfpromo") "self‑promo" else "sponsor"
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val first = !prefs.getBoolean("hint_sponsor", false)
        if (first) prefs.edit().putBoolean("hint_sponsor", true).apply()
        PlaybackEvents.notify(
            if (first) "Skipped a $what (${secs} s) · marked by SponsorBlock users" else "Skipped $what · ${secs} s",
            "Undo",
        ) {
            undone += key
            if (exo.currentMediaItem?.mediaId == id) exo.seekTo(seg.startMs)
        }
    }

    /** A track was looked up: fill in anything the list didn't know (shared links, duration). */
    private fun onResolved(audio: ResolvedAudio) {
        val p = session?.player ?: return
        val v = audio.video
        resolved[v.id] = v
        related[v.id] = audio.related
        for (i in 0 until p.mediaItemCount) {
            val item = p.getMediaItemAt(i)
            if (item.mediaId != v.id) continue
            val known = item.mediaMetadata.extras?.let { videoFromExtras(v.id, it) }
            val incomplete = known == null || known.title.isBlank() || known.title == "Loading…" || known.durationSec <= 0
            if (!incomplete) continue
            val merged = if (known == null || known.title.isBlank() || known.title == "Loading…") v
                else known.copy(durationSec = v.durationSec)
            p.replaceMediaItem(i, item.buildUpon().setMediaMetadata(metadataFor(merged)).build())
        }
        recordListen()
    }

    /** Feeds "For you" once per track, when it's actually the one playing. */
    private fun recordListen() {
        val id = session?.player?.currentMediaItem?.mediaId ?: return
        if (id in recorded) return
        val rel = related[id] ?: return
        val video = resolved[id] ?: return
        recorded += id
        Library.onListened(video, rel)
    }

    /** A queued track YouTube refuses (age-restricted, removed…) is skipped, not a dead end. */
    private fun skipIfUnplayable() {
        val p = session?.player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        val reason = AudioCache.failures[id] ?: return
        if (!p.hasNextMediaItem()) return
        PlaybackEvents.notify("Skipped · $reason")
        p.seekTo(p.nextMediaItemIndex, 0)
        p.prepare()
        p.play()
    }

    companion object {
        fun metadataFor(v: Video): MediaMetadata = MediaMetadata.Builder()
            .setTitle(v.title)
            .setArtist(v.channel)
            .setArtworkUri(v.thumbnail.toUri())
            .setDurationMs(if (v.durationSec > 0) v.durationSec * 1000 else null)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply {
                putString("ch", v.channel); putString("th", v.thumbnail)
                putLong("dur", v.durationSec); putString("t", v.title)
            })
            .build()

        fun videoFromExtras(id: String, b: Bundle) = Video(
            id = id,
            title = b.getString("t").orEmpty(),
            channel = b.getString("ch").orEmpty(),
            thumbnail = b.getString("th") ?: Video.fallbackThumb(id),
            durationSec = b.getLong("dur"),
        )
    }
}

/**
 * Once a track finishes there is no "replay": play does nothing and the track stays at its end,
 * but seeking (double-tap back, dragging the bar) still works and resumes from there.
 */
@UnstableApi
private class NoReplayPlayer(player: Player) : ForwardingPlayer(player) {
    override fun seekToDefaultPosition() {
        if (playbackState != Player.STATE_ENDED) super.seekToDefaultPosition()
    }
    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (playbackState != Player.STATE_ENDED) super.seekToDefaultPosition(mediaItemIndex)
    }
}
