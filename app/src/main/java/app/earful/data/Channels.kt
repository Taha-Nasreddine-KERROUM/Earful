package app.earful.data

import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import java.nio.charset.StandardCharsets

/**
 * Channels that are broadcasting live right now, as far as the app has seen: a live broadcast
 * turning up in search or suggestions puts a red ring on its channel's logo (nowhere else).
 * Each sighting is trusted for 20 minutes; opening the channel checks properly.
 */
object LiveNow {
    private class Seen(val video: Video, val at: Long)
    private val _map = MutableStateFlow<Map<String, Seen>>(emptyMap())
    private const val FRESH_MS = 20 * 60_000L

    /** Bumped whenever the set changes, for the screen to redraw. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun key(channelUrl: String) = channelUrl.trimEnd('/').substringAfterLast('/').lowercase()

    internal fun seen(live: Video) {
        val url = live.channelUrl ?: return
        _map.value = _map.value + (key(url) to Seen(live, System.currentTimeMillis()))
        _version.value++
    }

    internal fun ended(channelUrl: String) {
        val k = key(channelUrl)
        if (k !in _map.value) return
        _map.value = _map.value - k
        _version.value++
    }

    /** The broadcast [channelUrl] is live with, if it's (recently known to be) live. */
    fun of(channelUrl: String?): Video? {
        channelUrl ?: return null
        val s = _map.value[key(channelUrl)] ?: return null
        return s.video.takeIf { System.currentTimeMillis() - s.at < FRESH_MS }
    }
}

/** A channel's own page: who it is, whether it's live, and its videos (newest or most popular). */
object Channels {
    class Info(
        val url: String,
        val id: String,
        val name: String,
        val avatar: String?,
        val subscribers: Long,
        val videosTab: ListLinkHandler?,
        val live: Video?,
    )

    private val service get() = ServiceList.YouTube

    suspend fun info(url: String): Info = coroutineScope {
        val info = YouTube.io { ChannelInfo.getInfo(service, url) }
        val tabs = info.tabs.orEmpty()
        val videos = tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        val streams = tabs.firstOrNull { ChannelTabs.LIVESTREAMS in it.contentFilters }
        // Is it live right now? The newest item on its Live tab says so.
        val live = streams?.let { tab ->
            async {
                runCatching {
                    YouTube.io {
                        ChannelTabInfo.getInfo(service, tab).relatedItems
                            .filterIsInstance<StreamInfoItem>().take(3)
                            .firstNotNullOfOrNull { Video.liveFrom(it) }
                    }
                }.getOrNull()
            }
        }?.await()?.let { it.copy(channel = it.channel.ifBlank { info.name.orEmpty() }, channelUrl = url, channelAvatar = it.channelAvatar ?: Video.bestImage(info.avatars, 96)) }
        if (live != null) LiveNow.seen(live) else LiveNow.ended(url)
        Info(
            url = url,
            id = info.id.orEmpty().substringAfterLast('/').takeIf { it.startsWith("UC") }
                ?: Regex("UC[A-Za-z0-9_-]{22}").find(info.url.orEmpty() + " " + url)?.value.orEmpty(),
            name = info.name.orEmpty(),
            avatar = Video.bestImage(info.avatars, maxWidth = 240),
            subscribers = info.subscriberCount,
            videosTab = videos,
            live = live,
        )
    }

    /** Newest first, the way the channel's Videos tab lists them. */
    suspend fun newest(ch: Info): Paged<Video> {
        val tab = ch.videosTab ?: return Paged(emptyList(), null)
        return YouTube.io {
            val res = ChannelTabInfo.getInfo(service, tab)
            Paged(res.relatedItems.own(ch), res.nextPage?.takeIf { Page.isValid(it) }?.let { newestMore(ch, tab, it) })
        }
    }

    private fun newestMore(ch: Info, tab: ListLinkHandler, page: Page): suspend () -> Paged<Video> = {
        YouTube.io {
            val res = ChannelTabInfo.getMoreItems(service, tab, page)
            Paged(res.items.own(ch), res.nextPage?.takeIf { Page.isValid(it) }?.let { newestMore(ch, tab, it) })
        }
    }

    /**
     * Most watched first: the "Popular" choice on the channel's Videos tab. YouTube serves it as a
     * different page of the same tab, which the reader doesn't know about, so it's asked for
     * directly. If YouTube ever answers in a shape this doesn't follow, the newest ~90 videos
     * are sorted by views instead, so the list is never empty.
     */
    suspend fun popular(ch: Info): Paged<Video> = YouTube.io {
        runCatching { popularDirect(ch) }.getOrNull()?.takeIf { it.items.isNotEmpty() } ?: popularFallback(ch)
    }

    private suspend fun popularFallback(ch: Info): Paged<Video> {
        val all = ArrayList<Video>()
        var page: Paged<Video>? = newest(ch)
        var n = 0
        while (page != null && n < 3) {
            all += page.items
            page = runCatching { page.next?.invoke() }.getOrNull()
            n++
        }
        return Paged(all.distinctBy { it.id }.sortedByDescending { it.views }, null)
    }

    private fun popularDirect(ch: Info): Paged<Video> {
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        val body = JsonWriter.string(
            YoutubeParsingHelper.prepareDesktopJsonBuilder(loc, country)
                .value("browseId", ch.id)
                .value("params", "EgZ2aWRlb3PyBgQKAjoA") // the Videos tab
                .done()
        ).toByteArray(StandardCharsets.UTF_8)
        val page = YoutubeParsingHelper.getJsonPostResponse("browse", body, loc)
        val grid = videosGrid(page) ?: error("no videos grid")
        val token = popularChip(grid.getObject("header")) ?: error("no popular chip")
        return continuation(ch, token)
    }

    private fun continuation(ch: Info, token: String): Paged<Video> {
        val loc = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        val body = JsonWriter.string(
            YoutubeParsingHelper.prepareDesktopJsonBuilder(loc, country).value("continuation", token).done()
        ).toByteArray(StandardCharsets.UTF_8)
        val res = YoutubeParsingHelper.getJsonPostResponse("browse", body, loc)
        val items = ArrayList<Any?>()
        res.getArray("onResponseReceivedActions").forEach { a ->
            val o = a as? JsonObject ?: return@forEach
            o.getObject("reloadContinuationItemsCommand").getArray("continuationItems").let(items::addAll)
            o.getObject("appendContinuationItemsAction").getArray("continuationItems").let(items::addAll)
        }
        res.getArray("onResponseReceivedEndpoints").forEach { a ->
            val o = a as? JsonObject ?: return@forEach
            o.getObject("reloadContinuationItemsCommand").getArray("continuationItems").let(items::addAll)
            o.getObject("appendContinuationItemsAction").getArray("continuationItems").let(items::addAll)
        }
        val collector = StreamInfoItemsCollector(service.serviceId)
        var next: String? = null
        for (it in items) {
            val item = it as? JsonObject ?: continue
            val content = item.getObject("richItemRenderer").getObject("content")
            when {
                content.has("videoRenderer") -> collector.commit(Owned(YoutubeStreamInfoItemExtractor(content.getObject("videoRenderer"), null), ch))
                content.has("lockupViewModel") -> {
                    val lockup = content.getObject("lockupViewModel")
                    if (lockup.getString("contentType", "LOCKUP_CONTENT_TYPE_VIDEO") == "LOCKUP_CONTENT_TYPE_VIDEO")
                        collector.commit(OwnedLockup(YoutubeStreamInfoItemLockupExtractor(lockup, null), ch))
                }
                item.has("continuationItemRenderer") -> next = item.getObject("continuationItemRenderer")
                    .getObject("continuationEndpoint").getObject("continuationCommand").getString("token")
            }
        }
        val videos = collector.items.own(ch)
        return Paged(videos, next?.takeIf { it.isNotBlank() }?.let { t -> { YouTube.io { continuation(ch, t) } } })
    }

    /** The Videos tab's grid in a channel page. */
    private fun videosGrid(page: JsonObject): JsonObject? =
        page.getObject("contents").getObject("twoColumnBrowseResultsRenderer").getArray("tabs")
            .mapNotNull { (it as? JsonObject)?.getObject("tabRenderer") }
            .firstOrNull { it.getBoolean("selected", false) || it.getObject("content").has("richGridRenderer") }
            ?.getObject("content")?.getObject("richGridRenderer")?.takeIf { it.isNotEmpty() }

    /**
     * The token behind the "Popular" chip. Chips come as Latest, Popular, Oldest (in whatever
     * language): the one labelled Popular if there is one, otherwise the second.
     */
    private fun popularChip(header: JsonObject): String? {
        val chips = ArrayList<Pair<String, String>>() // label, token
        fun walk(node: Any?, label: String?) {
            when (node) {
                is JsonObject -> {
                    val here = node.getObject("text").let { t ->
                        t.getString("simpleText") ?: t.getArray("runs").firstOrNull()?.let { (it as? JsonObject)?.getString("text") }
                    } ?: node.getString("text") ?: label
                    val cmd = node.getObject("continuationCommand")
                    val token = cmd.getString("token")
                    if (token != null) { chips += (here.orEmpty() to token); return }
                    node.values.forEach { walk(it, here) }
                }
                is JsonArray -> node.forEach { walk(it, label) }
            }
        }
        walk(header, null)
        val distinct = chips.distinctBy { it.second }
        return (distinct.firstOrNull { it.first.contains("popular", ignoreCase = true) } ?: distinct.getOrNull(1))?.second
    }

    /** Videos from a channel's own tabs don't carry the channel: fill it in. */
    private fun List<*>.own(ch: Info): List<Video> =
        filterIsInstance<StreamInfoItem>().mapNotNull(Video::from).distinctBy { it.id }.map {
            it.copy(
                channel = it.channel.ifBlank { ch.name },
                channelUrl = it.channelUrl ?: ch.url,
                channelAvatar = it.channelAvatar ?: ch.avatar,
            )
        }

    private class Owned(private val e: YoutubeStreamInfoItemExtractor, private val ch: Info) :
        org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor by e {
        override fun getUploaderName(): String = runCatching { e.uploaderName }.getOrNull()?.ifBlank { null } ?: ch.name
        override fun getUploaderUrl(): String = runCatching { e.uploaderUrl }.getOrNull()?.ifBlank { null } ?: ch.url
        override fun isUploaderVerified(): Boolean = runCatching { e.isUploaderVerified }.getOrDefault(false)
    }

    private class OwnedLockup(private val e: YoutubeStreamInfoItemLockupExtractor, private val ch: Info) :
        org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor by e {
        override fun getUploaderName(): String = runCatching { e.uploaderName }.getOrNull()?.ifBlank { null } ?: ch.name
        override fun getUploaderUrl(): String = runCatching { e.uploaderUrl }.getOrNull()?.ifBlank { null } ?: ch.url
        override fun isUploaderVerified(): Boolean = runCatching { e.isUploaderVerified }.getOrDefault(false)
    }
}
