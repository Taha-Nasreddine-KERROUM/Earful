package app.earful.ui.player

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * How a video looks while you move it in Up next. Each time Up next opens it's the next one:
 *  · Select — like a layer on a design canvas: a selection box with handles and its size, a red
 *    guide where it will go, an orange line counting the places it has moved.
 *  · Slab — a full-width slab of glass that tips with your finger and magnifies what it passes.
 *  · Group — Up next as one rounded group; the video breaks out as a pill with a scalloped
 *    picture, and a bar afterwards says where it went, with Undo.
 *  · Timeline — a ruler of start times, each video's length as a bar, the held one a clip.
 */
enum class ReorderStyle { Select, Slab, Group, Timeline }

object ReorderStyles {
    /** The next style in turn (remembered, so it carries on from where it was next time). */
    fun next(context: Context): ReorderStyle {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val i = (prefs.getInt("reorder_style", -1) + 1).mod(ReorderStyle.entries.size)
        prefs.edit().putInt("reorder_style", i).apply()
        return ReorderStyle.entries[i]
    }
}

/** Everything the current style needs to remember while a video is moved (and just after). */
@Stable
class ReorderLook(val style: ReorderStyle) {
    /** Where the held video started (its place in the list). */
    var origin by mutableIntStateOf(-1)
    /** The video just set down, for its last flourish. */
    var settled by mutableStateOf<Long?>(null)
    /** Select: the snap flash (1 → 0). Slab: the band of light crossing it (0 → 1). */
    val flourish = Animatable(0f)
    /** Select: the red guide, gliding from place to place. */
    val guideY = Animatable(0f)
    /** Slab: how far it leans (degrees), from the speed you move it. */
    var tilt by mutableFloatStateOf(0f)
    /** Group: the scalloped picture's slow turn. */
    var spin by mutableFloatStateOf(0f)
    /** Group: each row's top and bottom corner (dp), so the group can open and close. */
    val corners = HashMap<Long, Pair<Animatable<Float, AnimationVector1D>, Animatable<Float, AnimationVector1D>>>()
    /** Group: the ink spreading from where a finger touched. */
    var ink by mutableStateOf<Ink?>(null)
    val inkGrow = Animatable(0f)
    val inkFade = Animatable(0f)
    /** Group: the move just made (to put back), and what the bar says. */
    var undo by mutableStateOf<Pair<Int, Int>?>(null)
    var undoText by mutableStateOf("")
    val undoShown = Animatable(0f)
    /** Timeline: when each place starts (seconds from now), recounting as things move. */
    val times = HashMap<Int, Animatable<Float, AnimationVector1D>>()

    fun corner(key: Long, start: Float): Pair<Animatable<Float, AnimationVector1D>, Animatable<Float, AnimationVector1D>> =
        corners.getOrPut(key) { Animatable(start) to Animatable(start) }
}

class Ink(val x: Float, val y: Float, val key: Any?)

/** A picture's outline: a rounded rectangle at [morph] 0, a scalloped "cookie" at 1. */
class CookieShape(private val morph: Float, private val turn: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r0 = min(size.width, size.height) / 2f
        val n = 72
        for (i in 0 until n) {
            val a = i / n.toFloat() * 2f * Math.PI.toFloat()
            val c = cos(a); val s = sin(a)
            // the rounded rectangle (a squircle filling the box)
            val rx = cx * sign(c) * abs(c).pow(2f / 7f)
            val ry = cy * sign(s) * abs(s).pow(2f / 7f)
            // the scallop (nine soft bumps)
            val r = r0 * (0.88f + 0.12f * cos(9f * a + turn))
            val x = cx + rx * (1f - morph) + r * c * morph
            val y = cy + ry * (1f - morph) + r * s * morph
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return Outline.Generic(p)
    }
}

/** A rounded rectangle reaching [out] past the sides (the glass slab's shadow). */
class WideShape(private val out: Float, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(-out, 0f, size.width + out, size.height, CornerRadius(radius)))
}

/** "18m", "1h 48m": when a place starts playing. */
internal fun shortTime(sec: Float): String {
    val m = (sec / 60f).toInt().coerceAtLeast(0)
    return if (m < 60) "${m}m" else "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
}
