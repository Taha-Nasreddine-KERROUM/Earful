package app.earful.ui.hole

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Lets the black hole eat this: a piece (a card, a row, a button), or with [page] a whole
 * screen (photographed at half size with its [fill] behind it). Costs next to nothing until the
 * hole comes (no layer is made, nothing composed, nothing measured): then it's photographed once,
 * just before it falls, and hidden until it's back.
 */
fun Modifier.eatable(page: Boolean = false, fill: Color = Color.Transparent, layer: HoleLayer? = null, owner: Any? = null): Modifier =
    this then EatableElement(page, fill, layer, owner)

private class EatableElement(
    val page: Boolean,
    val fill: Color,
    val layer: HoleLayer?,
    val owner: Any?,
) : ModifierNodeElement<EatableNode>() {
    override fun create() = EatableNode(page, fill, layer, owner)
    override fun update(node: EatableNode) = node.update(page, fill, layer, owner)
    override fun InspectorInfo.inspectableProperties() { name = "eatable" }
    override fun equals(other: Any?) = other is EatableElement &&
        other.page == page && other.fill == fill && other.layer == layer && other.owner == owner
    override fun hashCode(): Int {
        var h = page.hashCode()
        h = 31 * h + fill.hashCode()
        h = 31 * h + (layer?.hashCode() ?: 0)
        return 31 * h + (owner?.hashCode() ?: 0)
    }
}

private class EatableNode(
    private var page: Boolean,
    private var fill: Color,
    private var layer: HoleLayer?,
    private var owner: Any?,
) : Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var piece: HolePiece? = null
    private var graphics: GraphicsContext? = null

    override fun onAttach() = join()
    override fun onDetach() = leave()

    fun update(page: Boolean, fill: Color, layer: HoleLayer?, owner: Any?) {
        this.owner = owner
        piece?.owner = owner
        if (page == this.page && fill == this.fill && layer == this.layer) return
        this.page = page; this.fill = fill; this.layer = layer
        if (isAttached) { leave(); join(); invalidateDraw() }
    }

    private fun join() {
        graphics = requireGraphicsContext()
        piece = HolePiece(layer ?: currentValueOf(LocalHoleLayer), page, fill).also {
            it.owner = owner
            Hole.register(it)
        }
    }

    private fun leave() {
        val p = piece ?: return
        piece = null
        Hole.unregister(p)
        p.layerRef?.let { graphics?.releaseGraphicsLayer(it) }
        p.layerRef = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) { piece?.coords = coordinates }

    override fun ContentDrawScope.draw() {
        val p = piece
        if (p == null) { drawContent(); return }
        val st = p.state
        if (st == 1 && p.shot == 0) {
            val g = graphics
            if (g == null) p.shot = 2 else {
                p.shot = 1
                val photo = p.layerRef ?: g.createGraphicsLayer().also { p.layerRef = it }
                val s = if (p.page) 0.5f else 1f
                val w = ceil(size.width * s).toInt().coerceAtLeast(1)
                val h = ceil(size.height * s).toInt().coerceAtLeast(1)
                photo.record(IntSize(w, h)) {
                    scale(s, s, Offset.Zero) {
                        if (p.fill.alpha > 0f) drawRect(p.fill)
                        this@draw.drawContent()
                    }
                }
                val scope = Hole.scope
                if (scope == null) p.shot = 2
                else scope.launch {
                    p.bitmap = runCatching { photo.toImageBitmap().asAndroidBitmap() }.getOrNull()
                    p.shot = 2
                    // the photograph is all it needs now
                    if (p.layerRef === photo) { g.releaseGraphicsLayer(photo); p.layerRef = null }
                }
            }
        }
        if (st != 3) drawContent()
    }
}
