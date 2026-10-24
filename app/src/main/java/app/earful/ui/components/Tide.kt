package app.earful.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The top edge of a surface rising like a calm tide: while it moves, the water climbs a little
 * at the two side edges (the way it does in a glass) and a soft ripple runs along it, fading out
 * once it arrives. All values are px.
 */
@Stable
class EdgeTide {
    /** How high the water climbs at the side edges. */
    var curl = 0f; private set
    /** How tall the ripple running along the edge is. */
    var rip = 0f; private set
    var phase = 0f; private set

    /**
     * [lift] is the curl wanted right now; [stir] how strongly the ripple is being driven (it
     * keeps going and fades by itself when that drops). Returns true while anything still moves.
     */
    fun step(dt: Float, lift: Float, stir: Float, unit: Float): Boolean {
        curl += (lift - curl) * (1f - exp(-dt * 14f))
        val curling = abs(curl - lift) > 0.05f * unit
        if (!curling) curl = lift
        phase += dt * 7f
        rip = max(stir, rip * exp(-dt * 4f))
        if (rip < 0.02f * unit) rip = 0f
        return curling || rip > 0f
    }

    /** A last soft ripple when the surface arrives. */
    fun settle(amount: Float) { rip = max(rip, amount) }

    fun reset(curlTo: Float = 0f) { curl = curlTo; rip = 0f }
}

private const val K = 0.5523f // cubic Bézier handle length for a quarter circle

/**
 * Adds a rectangle with rounded corners whose top edge carries the tide: [curl] raises it at the
 * sides (within ~14 dp of each side), [rip] ripples it. With [inset], the edge is lowered by the
 * same amount instead, so the shape never leaves its bounds (used where the surface can't draw
 * outside itself). With both at zero this is exactly a rounded rectangle.
 */
fun Path.addTideRect(
    left: Float, top: Float, right: Float, bottom: Float,
    topCorner: Float, bottomCorner: Float,
    curl: Float, rip: Float, phase: Float, unit: Float,
    inset: Boolean, shrinkCorners: Boolean,
) {
    val w = right - left
    val fall = 14f * unit
    val wave = 38f * unit
    val base = if (inset) top + curl + rip else top
    fun yt(x: Float) = base - curl * (exp(-(x - left) / fall) + exp(-(right - x) / fall)) + rip * sin((x - left) / wave + phase)
    val rc = (if (shrinkCorners) topCorner * (1f - 0.6f * (curl / (4f * unit)).coerceIn(0f, 1f)) else topCorner)
        .coerceAtMost(w / 2).coerceAtMost((bottom - top) / 2)
    val rb = bottomCorner.coerceAtMost(w / 2).coerceAtMost((bottom - top) / 2)

    val y0 = yt(left); val y1 = yt(left + rc)
    moveTo(left, y0 + rc)
    cubicTo(left, y0 + rc - K * rc, left + rc - K * rc, y1 + K * (y0 - y1), left + rc, y1)
    val step = 6f * unit
    var x = left + rc + step
    while (x < right - rc) { lineTo(x, yt(x)); x += step }
    val y2 = yt(right - rc); val y3 = yt(right)
    lineTo(right - rc, y2)
    cubicTo(right - rc + K * rc, y2 + K * (y3 - y2), right, y3 + rc - K * rc, right, y3 + rc)
    lineTo(right, bottom - rb)
    cubicTo(right, bottom - rb + K * rb, right - rb + K * rb, bottom, right - rb, bottom)
    lineTo(left + rb, bottom)
    cubicTo(left + rb - K * rb, bottom, left, bottom - rb + K * rb, left, bottom - rb)
    close()
}

/** The player sheet's outline while it rises: rounded, with the tide on its top edge (kept inside). */
class TideShape(
    private val corner: Float,
    private val curl: Float,
    private val rip: Float,
    private val phase: Float,
    private val unit: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().apply {
            addTideRect(0f, 0f, size.width, size.height, corner, corner, curl, rip, phase, unit, inset = true, shrinkCorners = true)
        })
}
