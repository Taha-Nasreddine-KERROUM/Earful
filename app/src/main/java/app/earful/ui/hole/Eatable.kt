package app.earful.ui.hole

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Lets the black hole eat this: a piece (a card, a row, a button), or with [page] a whole
 * screen (photographed at half size with its [fill] behind it). Costs next to nothing until the
 * hole comes (no layer is made, nothing is measured): then it's photographed once, just before
 * it falls, and hidden until it's back.
 */
fun Modifier.eatable(page: Boolean = false, fill: Color = Color.Transparent, layer: HoleLayer? = null, owner: Any? = null): Modifier = composed {
    val l = layer ?: LocalHoleLayer.current
    val graphics = LocalGraphicsContext.current
    val piece = remember(l, page) { HolePiece(l, page, fill) }
    piece.owner = owner
    DisposableEffect(piece) {
        Hole.register(piece)
        onDispose {
            Hole.unregister(piece)
            piece.layerRef?.let { graphics.releaseGraphicsLayer(it) }
            piece.layerRef = null
        }
    }
    this
        .onPlaced { piece.coords = it }
        .drawWithContent {
            val st = piece.state
            if (st == 1 && piece.shot == 0) {
                piece.shot = 1
                val photo = piece.layerRef ?: graphics.createGraphicsLayer().also { piece.layerRef = it }
                val s = if (page) 0.5f else 1f
                val w = ceil(size.width * s).toInt().coerceAtLeast(1)
                val h = ceil(size.height * s).toInt().coerceAtLeast(1)
                photo.record(IntSize(w, h)) {
                    scale(s, s, Offset.Zero) {
                        if (piece.fill.alpha > 0f) drawRect(piece.fill)
                        this@drawWithContent.drawContent()
                    }
                }
                val scope = Hole.scope
                if (scope == null) piece.shot = 2
                else scope.launch {
                    piece.bitmap = runCatching { photo.toImageBitmap().asAndroidBitmap() }.getOrNull()
                    piece.shot = 2
                    // the photograph is all it needs now
                    if (piece.layerRef === photo) { graphics.releaseGraphicsLayer(photo); piece.layerRef = null }
                }
            }
            if (st != 3) drawContent()
        }
}
