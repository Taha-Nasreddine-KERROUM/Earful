package app.earful.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import java.util.Locale

/** A slice of an endless list plus the key to fetch the next slice. */
class Paged<T>(val items: List<T>, val next: (suspend () -> Paged<T>)?)

/** The playable result of opening a video. */
class ResolvedAudio(
    val video: Video,
    val streamUrl: String,
    val contentLength: Long,
    val mime: String,
    val bitrateKbps: Int,
    val related: List<Video>,
)

/**
 * Reads YouTube directly (the same public pages a browser loads) through NewPipeExtractor.
 * Nothing goes through Google's ad or tracking endpoints, so there is simply no ad to block.
 */
object YouTube {
    private val service get() = ServiceList.YouTube

    fun init() {
        val locale = Locale.getDefault()
        NewPipe.init(
            YouTubeHttp,
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }

    // ---- search ---------------------------------------------------------------------------

    suspend fun search(query: String): Paged<Video> = io {
        val handler = service.searchQHFactory
            .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        val info = SearchInfo.getInfo(service, handler)
        Paged(info.relatedItems.toVideos(), info.nextPage?.let { searchMore(handler, it) })
    }

    private fun searchMore(handler: SearchQueryHandler, page: Page): suspend () -> Paged<Video> = {
        io {
            val res = SearchInfo.getMoreItems(service, handler, page)
            Paged(res.items.toVideos(), res.nextPage?.takeIf { Page.isValid(it) }?.let { searchMore(handler, it) })
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
            .take(8)
    }

    // ---- "For you" ----------------------------------------------------------------------

    /** Videos YouTube itself suggests next to [videoId] (Shorts and live already removed). */
    suspend fun related(videoId: String): List<Video> = io {
        val ex = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        ex.fetchPage()
        ex.relatedItems?.items.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull(Video::from)
    }

    // ---- playback -------------------------------------------------------------------------

    suspend fun resolve(videoId: String, fallback: Video?): ResolvedAudio = io {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val audio = pickAudio(info.audioStreams)
            ?: error("This video has no audio we can play")
        // its real shape, for the full player's picture (already part of this answer: no extra data)
        (info.videoOnlyStreams.orEmpty() + info.videoStreams.orEmpty())
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { VideoShapes.put(videoId, it.width, it.height) }
        val video = Video(
            id = videoId,
            title = info.name.orEmpty().ifEmpty { fallback?.title.orEmpty() },
            channel = info.uploaderName.orEmpty().ifEmpty { fallback?.channel.orEmpty() },
            thumbnail = Video.bestImage(info.thumbnails, maxWidth = 1280)
                ?: fallback?.thumbnail ?: Video.fallbackThumb(videoId),
            durationSec = info.duration,
            views = info.viewCount,
            uploaded = info.textualUploadDate,
            channelAvatar = Video.bestImage(info.uploaderAvatars, maxWidth = 96),
        )
        val length = audio.itagItem?.contentLength?.takeIf { it > 0 }
            ?: Regex("[?&]clen=(\\d+)").find(audio.content)?.groupValues?.get(1)?.toLong()
            ?: -1L
        ResolvedAudio(
            video = video,
            streamUrl = audio.content,
            contentLength = length,
            mime = if (audio.format == MediaFormat.WEBMA_OPUS || audio.format == MediaFormat.WEBMA)
                "audio/webm" else "audio/mp4",
            bitrateKbps = kbps(audio),
            related = info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(Video::from),
        )
    }

    /**
     * Picks the smallest stream that still sounds good.
     *
     * Opus is roughly twice as efficient as AAC, so ~70 kbps Opus ≈ 128 kbps AAC to the ear while
     * using about half the data (~0.5 MB per minute). We only accept a direct download (no live,
     * no DASH fragments) so the file can be fetched in a few requests and kept whole.
     */
    internal fun pickAudio(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.itagItem?.isDrc != true &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }.ifEmpty { streams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        if (usable.isEmpty()) return null

        fun perceived(s: AudioStream) = kbps(s) * if (s.format == MediaFormat.WEBMA_OPUS) 1.8 else 1.0
        return usable.filter { perceived(it) >= 110 }.minByOrNull { kbps(it) }
            ?: usable.maxByOrNull { kbps(it) }
    }

    private fun kbps(s: AudioStream): Int {
        val avg = s.averageBitrate
        if (avg > 0) return if (avg > 10_000) avg / 1000 else avg
        val br = s.bitrate
        return if (br > 10_000) br / 1000 else br.coerceAtLeast(1)
    }

    private fun List<*>.toVideos(): List<Video> =
        filterIsInstance<StreamInfoItem>().mapNotNull(Video::from).distinctBy { it.id }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
