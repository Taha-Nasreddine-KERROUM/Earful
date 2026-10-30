package app.earful.playback

import android.content.Context
import app.earful.data.Video
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What was open when the app last went away: the queue, which video, and where in it.
 *
 * Kept until you *close* the video (swipe the mini player away) or start a video from outside
 * the queue — then it's deleted along with the downloaded audio. Just leaving the app, swiping
 * it out of recents or a restart keeps it, so you come back exactly where you were.
 */
object Session {
    class Saved(val items: List<Video>, val index: Int, val positionMs: Long)

    private lateinit var file: File

    fun init(context: Context) { file = File(context.filesDir, "session.json") }

    fun save(items: List<Video>, index: Int, positionMs: Long) {
        if (items.isEmpty()) { clear(); return }
        val o = JSONObject()
            .put("items", JSONArray(items.map { it.toJson() }))
            .put("index", index)
            .put("pos", positionMs.coerceAtLeast(0))
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file) // never a half-written session
        }
    }

    fun load(): Saved? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val a = o.getJSONArray("items")
        val items = (0 until a.length()).map { Video.fromJson(a.getJSONObject(it)) }
        if (items.isEmpty()) null
        else Saved(items, o.optInt("index").coerceIn(0, items.size - 1), o.optLong("pos"))
    }.getOrNull()

    /** The tracks whose audio is worth keeping: everything still in the queue. */
    fun keepIds(): Set<String> = load()?.items?.map { it.id }?.toSet().orEmpty()

    fun clear() { if (::file.isInitialized) file.delete() }
}
