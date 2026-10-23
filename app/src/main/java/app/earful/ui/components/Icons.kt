package app.earful.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.platform.LocalDensity

/**
 * A tiny hand-drawn icon set in the spirit of SF Symbols (rounded joins, optical weights).
 * Twelve vectors instead of a 10 MB icon library.
 */
object Icons {
    private fun icon(name: String, vararg paths: Pair<String, Float?>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { (d, stroke) ->
                if (stroke == null) addPath(addPathNodes(d), fill = SolidColor(Color.Black))
                else addPath(
                    addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Play = icon("play", "M7.5 5.2v13.6c0 1 1.1 1.6 1.9 1l10.4-6.8c.8-.5.8-1.6 0-2.1L9.4 4.1c-.8-.5-1.9.1-1.9 1.1z" to null)
    val Pause = icon(
        "pause",
        "M7.2 4.5h2.6c.7 0 1.2.5 1.2 1.2v12.6c0 .7-.5 1.2-1.2 1.2H7.2c-.7 0-1.2-.5-1.2-1.2V5.7c0-.7.5-1.2 1.2-1.2z" to null,
        "M14.2 4.5h2.6c.7 0 1.2.5 1.2 1.2v12.6c0 .7-.5 1.2-1.2 1.2h-2.6c-.7 0-1.2-.5-1.2-1.2V5.7c0-.7.5-1.2 1.2-1.2z" to null,
    )
    val Search = icon("search", "M10.6 3.8a6.8 6.8 0 1 0 0 13.6 6.8 6.8 0 0 0 0-13.6z" to 2f, "M15.6 15.6l4.6 4.6" to 2.2f)
    val Bookmark = icon("bookmark", "M7.2 3.8h9.6c.7 0 1.2.5 1.2 1.2v15.2l-6-4-6 4V5c0-.7.5-1.2 1.2-1.2z" to 1.8f)
    val BookmarkFill = icon("bookmark.fill", "M7.2 3h9.6C17.6 3 18.6 4 18.6 5v15.6c0 .8-.9 1.2-1.5.8L12 18l-5.1 3.4c-.6.4-1.5 0-1.5-.8V5c0-1.1 1-2 1.8-2z" to null)
    val Home = icon("house", "M3.8 10.4 12 3.8l8.2 6.6V19c0 .7-.5 1.2-1.2 1.2h-4.4v-5.6H9.4v5.6H5c-.7 0-1.2-.5-1.2-1.2z" to 1.8f)
    val HomeFill = icon("house.fill", "M3.2 10.6 11.2 4c.5-.4 1.1-.4 1.6 0l8 6.6c.3.2.5.6.5 1V19.4c0 .9-.7 1.6-1.6 1.6h-4.2v-6h-7v6H4.3c-.9 0-1.6-.7-1.6-1.6v-7.8c0-.4.2-.8.5-1z" to null)
    val Close = icon("xmark", "M6.5 6.5l11 11M17.5 6.5l-11 11" to 2f)
    val ArrowUpLeft = icon("arrow.up.left", "M7 7h8M7 7v8M7 7l10 10" to 1.8f)
    val Clock = icon("clock", "M12 3.8a8.2 8.2 0 1 0 0 16.4 8.2 8.2 0 0 0 0-16.4z" to 1.7f, "M12 7.6V12l3 2" to 1.7f)
    val Back = icon("chevron.left", "M15 4.5 7.5 12l7.5 7.5" to 2.2f)
    val Check = icon("checkmark", "M5 12.5l4.5 4.5L19 7.5" to 2.2f)
    /** "Add to queue": three lines with a plus, like "Play Last". */
    val QueueAdd = icon("text.line.last.and.plus", "M4 6.5h11M4 11.5h11M4 16.5h7" to 1.9f, "M18 13.5v6M15 16.5h6" to 1.9f)
    val QueueAdded = icon("text.line.last.and.check", "M4 6.5h11M4 11.5h11M4 16.5h6" to 1.9f, "M14.5 16.8l2.2 2.2 4.3-4.6" to 1.9f)
    val List = icon("list.bullet", "M9 6.5h11M9 12h11M9 17.5h11" to 1.9f, "M4.6 6.5h.01M4.6 12h.01M4.6 17.5h.01" to 2.6f)
    val Trash = icon(
        "trash",
        "M4.5 6.5h15M9.5 6.5V5c0-.6.4-1 1-1h3c.6 0 1 .4 1 1v1.5" to 1.8f,
        "M6.5 6.5l.9 12.6c.1.8.7 1.4 1.5 1.4h6.2c.8 0 1.4-.6 1.5-1.4l.9-12.6M10.2 10.5v6M13.8 10.5v6" to 1.8f,
    )
    val ChevronUp = icon("chevron.up", "M5.5 14.5 12 8l6.5 6.5" to 2.2f)
    val Wifi = icon("wifi.slash", "M3 3l18 18" to 1.8f, "M2.5 8.8A15 15 0 0 1 9 5.3M21.5 8.8a15 15 0 0 0-8.6-3.7M5.6 12.2a10 10 0 0 1 4.4-2.4M18.4 12.2a10 10 0 0 0-2.2-1.6M8.8 15.6a5.5 5.5 0 0 1 4.3-1.3" to 1.8f, "M12 19.2h.01" to 2.6f)
}

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp, contentDescription: String? = null) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * "gobackward.10" / "goforward.10": an open circular arrow with the number inside.
 * Drawn rather than stored, so it's crisp at every size and any number of seconds.
 */
@Composable
fun SkipIcon(seconds: Int, forward: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    // Sized in dp, not sp: the number must always fit inside the circle.
    val numberSize = with(LocalDensity.current) { (size * 0.30f).toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension
            val stroke = w * 0.075f
            val r = w * 0.40f
            val c = Offset(w / 2, w / 2)
            val draw: () -> Unit = {
                drawArc(
                    color = tint, startAngle = -90f + 8f, sweepAngle = 292f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                val a = w * 0.13f
                val tip = Offset(c.x - a * 0.95f, c.y - r)
                val head = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(c.x + a * 0.25f, c.y - r - a * 0.8f)
                    lineTo(c.x + a * 0.25f, c.y - r + a * 0.8f)
                    close()
                }
                drawPath(head, tint)
            }
            if (forward) scale(-1f, 1f, pivot = c) { draw() } else draw()
        }
        BasicText(
            text = seconds.toString(),
            style = TextStyle(
                color = tint,
                fontSize = numberSize,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.04).em,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}
