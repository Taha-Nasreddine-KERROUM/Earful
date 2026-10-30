package app.earful.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        ex.relatedItems?.items.orEmpty().toVideos()
    }

    // ---- playback -------------------------------------------------------------------------

    suspend fun resolve(videoId: String, fallback: Video?): ResolvedAudio = io {
        val ex = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        ex.fetchPage()
        val info = StreamInfo.getInfo(ex)
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
            channel = collaborators(ex)?.also { list ->
                collabs[videoId] = list
                // (pictures YouTube left out are fetched afterwards, without holding up playback)
                if (list.any { it.avatar == null }) pictureScope.launch { collabs[videoId] = withPictures(list) }
            }?.map { it.name }?.let(::names) ?: info.uploaderName.orEmpty().ifEmpty { fallback?.channel.orEmpty() },
            thumbnail = Video.bestImage(info.thumbnails, maxWidth = 1280)
                ?: fallback?.thumbnail ?: Video.fallbackThumb(videoId),
            durationSec = info.duration,
            views = info.viewCount,
            uploaded = info.textualUploadDate,
            channelAvatar = Video.bestImage(info.uploaderAvatars, maxWidth = 96),
            channelUrl = info.uploaderUrl?.takeIf { it.isNotBlank() } ?: fallback?.channelUrl,
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
            related = info.relatedItems.toVideos(),
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

    /** Normal videos only; any live broadcast among them is noted for its channel's logo. */
    internal fun List<*>.toVideos(): List<Video> {
        val items = filterIsInstance<StreamInfoItem>()
        items.forEach { it -> Video.liveFrom(it)?.let(LiveNow::seen) }
        return items.mapNotNull(Video::from).distinctBy { it.id }
    }

    /** One of the channels a video is by. */
    class Collab(val name: String, val url: String?, val avatar: String?)

    private val pictureScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Videos made by several channels together, as far as the app has looked them up. */
    private val collabs = java.util.concurrent.ConcurrentHashMap<String, List<Collab>>()

    /** The channels [videoId] is by, if it's already known (from playing it). */
    fun knownCollaborators(videoId: String): List<Collab>? = collabs[videoId]

    /** The channels [videoId] is by (one, or several when they made it together). */
    suspend fun collaborators(videoId: String): List<Collab> = collabs[videoId] ?: io {
        val ex = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        ex.fetchPage()
        val list = collaborators(ex) ?: listOf(Collab(ex.uploaderName.orEmpty(), ex.uploaderUrl, Video.bestImage(runCatching { ex.uploaderAvatars }.getOrNull(), 96)))
        val done = withPictures(list)
        collabs[videoId] = done
        done
    }

    /** Any channel whose picture YouTube didn't include gets it from its own page. */
    private suspend fun withPictures(list: List<Collab>): List<Collab> = kotlinx.coroutines.coroutineScope {
        list.map { c ->
            if (c.avatar != null || c.url == null) kotlinx.coroutines.CompletableDeferred(c)
            else async {
                val pic = runCatching {
                    Video.bestImage(org.schabi.newpipe.extractor.channel.ChannelInfo.getInfo(service, c.url).avatars, 176)
                }.getOrNull()
                Collab(c.name, c.url, pic)
            }
        }.map { it.await() }
    }

    /**
     * A video made by several channels together: all of them (YouTube lists them in a little
     * dialog behind the owner's name; the reader only gives the first). Null for a normal video,
     * or if YouTube's answer doesn't have the expected shape.
     */
    private fun collaborators(ex: org.schabi.newpipe.extractor.stream.StreamExtractor): List<Collab>? = runCatching {
        val f = ex.javaClass.getDeclaredField("nextResponse").apply { isAccessible = true }
        val next = f.get(ex) as? com.grack.nanojson.JsonObject ?: return@runCatching null
        val owner = findObject(next, "videoOwnerRenderer") ?: return@runCatching null
        val items = owner.getObject("navigationEndpoint").getObject("showDialogCommand")
            .getObject("panelLoadingStrategy").getObject("inlineContent").getObject("dialogViewModel")
            .getObject("customContent").getObject("listViewModel").getArray("listItems")
        items.mapNotNull { it ->
            val item = (it as? com.grack.nanojson.JsonObject)?.getObject("listItemViewModel") ?: return@mapNotNull null
            val name = item.getObject("title").getString("content")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val id = findString(item, "browseId")?.takeIf { it.startsWith("UC") }
            // the channel's round picture (not the little verified badge, which also has "sources")
            val avatarBox = findObject(item, "avatarViewModel") ?: item.getObject("leadingAccessory")
            val avatar = (findArray(avatarBox, "sources") ?: findArray(item, "thumbnails"))
                ?.mapNotNull { src -> (src as? com.grack.nanojson.JsonObject)?.let { o -> o.getString("url")?.let { u -> u to o.getInt("width", 0) } } }
                ?.let { all -> all.filter { it.second in 1..176 }.maxByOrNull { it.second } ?: all.lastOrNull() }
                ?.first?.let(::fullUrl)
            Collab(name, id?.let { "https://www.youtube.com/channel/$it" }, avatar)
        }.distinctBy { it.name }.takeIf { it.size > 1 }
    }.getOrNull()

    /** Picture addresses sometimes come without "https:" in front. */
    private fun fullUrl(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("http://") -> "https://" + u.removePrefix("http://")
        else -> u
    }

    private fun findString(node: Any?, key: String, depth: Int = 0): String? {
        if (depth > 40) return null
        when (node) {
            is com.grack.nanojson.JsonObject -> {
                (node[key] as? String)?.let { return it }
                for (v in node.values) findString(v, key, depth + 1)?.let { return it }
            }
            is com.grack.nanojson.JsonArray -> for (v in node) findString(v, key, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findArray(node: Any?, key: String, depth: Int = 0): com.grack.nanojson.JsonArray? {
        if (depth > 40) return null
        when (node) {
            is com.grack.nanojson.JsonObject -> {
                (node[key] as? com.grack.nanojson.JsonArray)?.let { return it }
                for (v in node.values) findArray(v, key, depth + 1)?.let { return it }
            }
            is com.grack.nanojson.JsonArray -> for (v in node) findArray(v, key, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findObject(node: Any?, key: String, depth: Int = 0): com.grack.nanojson.JsonObject? {
        if (depth > 40) return null
        when (node) {
            is com.grack.nanojson.JsonObject -> {
                (node[key] as? com.grack.nanojson.JsonObject)?.let { return it }
                for (v in node.values) findObject(v, key, depth + 1)?.let { return it }
            }
            is com.grack.nanojson.JsonArray -> for (v in node) findObject(v, key, depth + 1)?.let { return it }
        }
        return null
    }

    /** "A and B", "A, B and C" — the way YouTube writes several channels. */
    /** Does this channel line name several channels ("A and B", "A & B", "A، B"…)? */
    fun looksShared(channel: String): Boolean =
        Regex("""\s(and|&|et|y|und|e|و|и|ve|と|和)\s|،|, """, RegexOption.IGNORE_CASE).containsMatchIn(channel)

    internal fun names(list: List<String>): String =
        if (list.size <= 1) list.firstOrNull().orEmpty() else list.dropLast(1).joinToString(", ") + " and " + list.last()

    /** What a live broadcast streams right now (HLS), with its details. */
    suspend fun live(videoId: String): Pair<Video, String> = io {
        val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        val url = info.hlsUrl?.takeIf { it.isNotBlank() } ?: error("This broadcast isn't live any more.")
        Video(
            id = Video.LIVE + videoId,
            title = info.name.orEmpty(),
            channel = info.uploaderName.orEmpty(),
            thumbnail = Video.bestImage(info.thumbnails, maxWidth = 1280) ?: Video.fallbackThumb(videoId),
            durationSec = 0,
            channelAvatar = Video.bestImage(info.uploaderAvatars, maxWidth = 96),
            channelUrl = info.uploaderUrl?.takeIf { it.isNotBlank() },
        ) to url
    }

    /** The channel a video is from, for videos that don't know it (saved by an older version). */
    suspend fun channelUrlOf(videoId: String): String? = io {
        runCatching {
            val ex = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            ex.fetchPage()
            ex.uploaderUrl
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    internal suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
