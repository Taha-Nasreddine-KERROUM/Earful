package app.earful.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/** Everything the UI needs to show one video, small enough to keep thousands in memory. */
@Immutable
data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnail: String,
    val durationSec: Long,
    val views: Long = -1,
    val uploaded: String? = null,
    val channelAvatar: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel)
        .put("thumb", thumbnail).put("dur", durationSec).put("views", views)
        .putOpt("up", uploaded).putOpt("av", channelAvatar)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            thumbnail = o.optString("thumb"),
            durationSec = o.optLong("dur", 0),
            views = o.optLong("views", -1),
            uploaded = o.optString("up").ifEmpty { null },
            channelAvatar = o.optString("av").ifEmpty { null },
        )

        private val ID_REGEX = Regex("""(?:v=|/shorts/|youtu\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})""")

        fun idFromUrl(url: String): String? = ID_REGEX.find(url)?.groupValues?.get(1)

        /** Stable, letterbox-free 16:9 thumbnail. `mqdefault` always exists (320×180). */
        fun fallbackThumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

        /**
         * Converts a search/home item into a [Video], or returns null for anything that
         * isn't a normal, finished video: Shorts, live streams and premieres are dropped here,
         * so they can never appear anywhere in the app.
         */
        fun from(item: StreamInfoItem): Video? {
            if (item.isShortFormContent) return null
            if ("/shorts/" in item.url) return null
            when (item.streamType) {
                StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM -> return null
                else -> Unit
            }
            val id = idFromUrl(item.url) ?: return null
            return Video(
                id = id,
                title = item.name.orEmpty(),
                channel = item.uploaderName.orEmpty(),
                thumbnail = bestImage(item.thumbnails, maxWidth = 720) ?: fallbackThumb(id),
                durationSec = item.duration,
                views = item.viewCount,
                uploaded = item.textualUploadDate,
                channelAvatar = bestImage(item.uploaderAvatars, maxWidth = 96),
            )
        }

        /** Largest image not wider than [maxWidth] (saves data on every scroll). */
        fun bestImage(images: List<Image>?, maxWidth: Int): String? {
            if (images.isNullOrEmpty()) return null
            val known = images.filter { it.width > 0 }
            val pick = known.filter { it.width <= maxWidth }.maxByOrNull { it.width }
                ?: known.minByOrNull { it.width }
                ?: images.first()
            return pick.url
        }
    }
}
