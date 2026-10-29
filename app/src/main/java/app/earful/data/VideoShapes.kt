package app.earful.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * The real shape (width ÷ height) of videos that aren't 16:9, as YouTube reports it when a video
 * is looked up to play. Only the full player uses it; everything else stays 16:9. Remembered on
 * the phone (only the few that differ), so saved videos keep their shape offline.
 */
object VideoShapes {
    const val WIDE = 16f / 9f
    private val _map = MutableStateFlow<Map<String, Float>>(emptyMap())
    val map: StateFlow<Map<String, Float>> = _map.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("video_shapes", Context.MODE_PRIVATE)
        prefs = p
        // (shapes remembered by an older version that now count as 16:9 are dropped)
        _map.value = p.all.mapNotNull { (k, v) -> (v as? Float)?.takeIf { abs(it / WIDE - 1f) >= 0.12f }?.let { k to it } }.toMap()
    }

    /**
     * From the video's size in pixels. Anything near 16:9 (from about 1.56 to 2.0: 16:10, 1.85
     * film, odd encodes) shows at the full 16:9 size; only clearly different shapes (4:3, square,
     * upright, 2.39 cinema) get their own. Extremes are kept sensible.
     */
    internal fun ratioOf(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        val r = (width.toFloat() / height).coerceIn(9f / 16f, 2.4f)
        return if (abs(r / WIDE - 1f) < 0.12f) WIDE else r
    }

    internal fun put(id: String, width: Int, height: Int) {
        val r = ratioOf(width, height) ?: return
        val now = _map.value
        if (r == WIDE) {
            if (id !in now) return
            _map.value = now - id
            prefs?.edit()?.remove(id)?.apply()
        } else {
            if (now[id] == r) return
            _map.value = now + (id to r)
            prefs?.edit()?.putFloat(id, r)?.apply()
        }
    }
}
