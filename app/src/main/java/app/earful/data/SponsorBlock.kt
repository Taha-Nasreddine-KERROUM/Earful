package app.earful.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest

/** A stretch of a video the creator spent advertising something. Times in milliseconds. */
@Immutable
data class AdSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Sponsor reads come from SponsorBlock (sponsor.ajay.app): a community database where viewers
 * mark the exact second a sponsor read starts and ends, covering millions of videos.
 *
 * Privacy: we never send the video id. We send the first 4 characters of its SHA-256 hash, get
 * back the segments for every video sharing that prefix, and pick ours out locally.
 *
 * Data: SponsorBlock, CC BY-NC-SA 4.0 — https://sponsor.ajay.app
 */
object SponsorBlock {
    /** Paid sponsor reads, and unpaid self-promotion (merch, Patreon, "check out my course"). */
    private const val CATEGORIES = """["sponsor","selfpromo"]"""

    private val cache = HashMap<String, List<AdSegment>>()

    suspend fun segments(videoId: String): List<AdSegment> {
        synchronized(cache) { cache[videoId]?.let { return it } }
        val result = withContext(Dispatchers.IO) {
            runCatching { fetch(videoId) }.getOrNull()
        } ?: return emptyList() // offline / server down: just play normally, try again next time
        synchronized(cache) { cache[videoId] = result }
        return result
    }

    private fun fetch(videoId: String): List<AdSegment> {
        val prefix = sha256(videoId).take(4)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("sponsor.ajay.app")
            .addPathSegments("api/skipSegments/$prefix")
            .addQueryParameter("categories", CATEGORIES)
            .addQueryParameter("actionTypes", """["skip"]""")
            .build()
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Earful/1.0 (Android)").build()
        Network.client.newCall(req).execute().use { res ->
            if (res.code == 404) return emptyList() // nobody marked anything with this prefix
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val arr = JSONArray(res.body.string())
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                if (v.optString("videoID") != videoId) continue
                val segs = v.getJSONArray("segments")
                return (0 until segs.length()).mapNotNull { j ->
                    val s = segs.getJSONObject(j)
                    val range = s.getJSONArray("segment")
                    val start = (range.getDouble(0) * 1000).toLong()
                    val end = (range.getDouble(1) * 1000).toLong()
                    if (end - start < 1000) null else AdSegment(start, end, s.optString("category"))
                }.sortedBy { it.startMs }.let(::merge)
            }
            return emptyList()
        }
    }

    /** Back-to-back or overlapping sections become one skip. */
    private fun merge(list: List<AdSegment>): List<AdSegment> {
        val out = ArrayList<AdSegment>()
        for (s in list) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs + 1500) {
                out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, s.endMs))
            } else out += s
        }
        return out
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
