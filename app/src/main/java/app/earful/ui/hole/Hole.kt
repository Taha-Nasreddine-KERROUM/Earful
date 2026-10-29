package app.earful.ui.hole

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The layers of the app, bottom to top. The hole eats the top one first. */
enum class HoleLayer { Page, Search, Channel, Player, Queue }

/** Which layer the eatable things inside a screen belong to. */
val LocalHoleLayer = staticCompositionLocalOf { HoleLayer.Page }

/** What the hole needs from the ±10 s button that turned into it. */
interface HoleSpin {
    val dir: Float
    val center: Offset
    val total: Int
    /** Seconds since the last tap. */
    fun since(): Float
    /** Does what a tap on the button does (skips, spins, counts). */
    val onTap: () -> Unit
}

/**
 * Something on screen the black hole can eat: a piece (a card, a row, a button) or a whole
 * page (a screen's background and whatever is left on it). It's photographed the moment it
 * starts to fall, and from then until it's back in its place the real one isn't drawn.
 */
class HolePiece(val layer: HoleLayer, val page: Boolean, val fill: Color) {
    var bounds = Rect.Zero
    /** Where it's laid out (read only when the hole needs it). */
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    /** The button that became the hole isn't eaten: it *is* the hole. */
    var owner: Any? = null
    var layerRef: GraphicsLayer? = null
    /** 0 in place · 1 about to go (being photographed, still shown) · 3 gone (the hole has it). */
    var state by mutableIntStateOf(0)
    /** The photograph: 0 none · 1 taken, being developed · 2 ready. */
    @Volatile var shot = 0
    var bitmap: Bitmap? = null
    var attached = true

    // the fall
    var start = 0f      // feed time it starts falling
    var fallE = 0f      // seconds into its fall
    var eat = 0f        // 0..1 how far gone
    var tf = 1f         // how long its farthest corner takes
    // thrown back out
    var fly: Fly? = null
    var back: Boolean = false
    var fallBack = -1f

    val hidden get() = state == 3

    class Fly(
        val start: Float, var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f,
        var vrx: Float, var vry: Float, var vrz: Float,
    )
}

object Hole {
    enum class Phase { Idle, Suck, Eject, Fade }

    var phase by mutableStateOf(Phase.Idle)
        private set
    val isActive get() = phase != Phase.Idle
    /** Bumped on every step, for the screen to redraw. */
    var frame by mutableLongStateOf(0L)
        private set

    var spin: HoleSpin? = null
        private set
    /** Pixels per unit of the design (a 270-wide phone). */
    var k = 1f
    var screen = Rect.Zero

    // the hole itself (px)
    var cx = 0f; var cy = 0f; var dir = 1f
    var t = 0f
    var rs = 0f; private var rsBase = 0f; private var rs0 = 0f
    var form = 0f; var formT = 0f
    var feedT = 0f; var ejT = 0f; private var fadeT = 0f
    var flash = -1f
    var flare = 0f; private var bump = 0f; private var bumpV = 0f
    var orb = 0f
    var waveAge = -1f
    var queueOpen = false; private var queueReady = false
    // the "10" thrown out of the hole and reeled back
    var tenX = 0f; var tenY = 0f; private var tenVx = 0f; private var tenVy = 0f; var tenRz = 0f; private var tenVrz = 0f

    /** How far the screen is shaken right now (px). */
    var shakeX by mutableFloatStateOf(0f); private set
    var shakeY by mutableFloatStateOf(0f); private set
    private var kick = 0f
    private var pre = 0f
    private var preOwner: HoleSpin? = null

    // what's eaten, in order
    private val pieces = ArrayList<HolePiece>()
    private class Item(val piece: HolePiece, val start: Float)
    private var order: List<Item> = emptyList()

    /** Hooks into the player: Up next, which is forced open first. */
    var hasQueue: () -> Boolean = { false }
    var openQueue: suspend () -> Unit = {}
    var closeQueue: suspend () -> Unit = {}
    var scope: CoroutineScope? = null
    private var loop: Job? = null

    /** Called by the UI each frame of the hole (from [HoleOverlay]). */
    internal var onStart: () -> Unit = {}

    // ---- registry -------------------------------------------------------------------------------

    fun register(p: HolePiece) { pieces += p }
    fun unregister(p: HolePiece) { p.attached = false; if (!isActive) pieces.remove(p) }

    /** A page that's gone takes the app's own background with it (the stars show through). */
    fun gone(layer: HoleLayer): Boolean = isActive && pieces.any { it.layer == layer && it.page && it.hidden && it.attached }

    // ---- shaking --------------------------------------------------------------------------------

    /** Closing in on +2000 s: a tremble that grows (0..1), from whichever button is spinning. */
    fun preShake(owner: HoleSpin, amount: Float) {
        if (amount > 0f) { pre = amount; preOwner = owner }
        else if (preOwner === owner) { pre = 0f; preOwner = null }
        if (!isActive) applyShake()
    }

    private fun applyShake() {
        val sp = spin
        val feed = if (phase == Phase.Suck) 0.35f + 0.25f * smooth(0f, 20f, feedT) else 0f
        val tapping = sp != null && sp.since() < 0.9f
        val a = (if (isActive) 0f else pre * 3.2f) + kick * 7f + feed * (if (tapping) 1f else 0f)
        if (a > 0.05f) {
            val tt = System.nanoTime() / 1e9f
            shakeX = k * a * (sin(tt * 61f) * 0.6f + (Random.nextFloat() - 0.5f) * 0.8f)
            shakeY = k * a * (cos(tt * 53f) * 0.6f + (Random.nextFloat() - 0.5f) * 0.8f)
        } else if (shakeX != 0f || shakeY != 0f) { shakeX = 0f; shakeY = 0f }
    }

    // ---- what the button tells it ---------------------------------------------------------------

    fun onBreak(sp: HoleSpin) {
        if (isActive) { if (phase != Phase.Suck && sp === spin) rewild(); return }
        if (screen.width <= 0f || scope == null) return
        spin = sp; dir = sp.dir; cx = sp.center.x; cy = sp.center.y
        t = 0f; formT = 0f; form = 0f; rs = 0f; rsBase = 0f; flare = 0f; bump = 0f; bumpV = 0f; feedT = 0f; ejT = 0f
        orb = 0f; flash = -1f; waveAge = 0f; kick = 1f
        queueReady = false; queueOpen = false
        order = emptyList()
        pieces.removeAll { !it.attached }
        pieces.forEach { it.reset() }
        phase = Phase.Suck
        onStart()
        // Up next is forced open first (if there is one), and only then does it start eating.
        scope?.launch {
            if (hasQueue()) { queueOpen = true; runCatching { openQueue() } }
            queueReady = true
        }
    }

    fun onTap(sp: HoleSpin) {
        if (phase != Phase.Suck || sp !== spin) return
        flare = min(1f, flare + 0.3f); bumpV += 1.6f
    }

    fun onRelease(sp: HoleSpin) {
        if (phase != Phase.Suck || sp !== spin) return
        phase = Phase.Eject; ejT = 0f; flash = 0f; rs0 = rs
        for (it in order) {
            val p = it.piece
            if (p.state == 1) { p.reset(); continue } // was about to go: it simply stays
            if (p.page) { p.fallBack = p.fallE; continue }
            if (p.eat > 0.35f && p.bitmap != null) {
                val a0 = atan2(p.bounds.center.y - cy, p.bounds.center.x - cx) + rnd(-0.5f, 0.5f)
                val s = rnd(600f, 950f) * k
                p.fly = HolePiece.Fly(
                    start = 0.35f + rnd(0f, 0.25f), x = cx - p.bounds.center.x, y = cy - p.bounds.center.y,
                    vx = cos(a0) * s, vy = sin(a0) * s,
                    vrx = rnd(-800f, 800f), vry = rnd(-800f, 800f), vrz = rnd(-700f, 700f),
                )
            } else if (p.state == 3) p.back = true
        }
        val a = rnd(-2.4f, -0.7f)
        tenX = 0f; tenY = 0f; tenVx = cos(a) * 600f * k; tenVy = sin(a) * 600f * k; tenRz = 0f; tenVrz = rnd(-900f, 900f)
    }

    /** Tapping again while it's throwing things back: everything in the air falls back in. */
    private fun rewild() {
        phase = Phase.Suck
        for (it in order) { val p = it.piece; if (p.fly != null) { p.fly = null; p.eat = 0.6f } }
    }

    /** Stops at once and puts everything back (the player was closed, the app left…). */
    fun abort() {
        if (!isActive) return
        end()
        scope?.launch { if (queueOpen) runCatching { closeQueue() } }
    }

    private fun end() {
        phase = Phase.Idle
        pieces.forEach { it.reset() }
        pieces.removeAll { !it.attached }
        order = emptyList()
        rs = 0f; form = 0f; spin = null
        shakeX = 0f; shakeY = 0f; kick = 0f
        frame++
    }

    private fun HolePiece.reset() {
        state = 0; fallE = 0f; eat = 0f; fly = null; back = false; fallBack = -1f
        bitmap = null; shot = 0
    }

    // ---- each frame -----------------------------------------------------------------------------

    fun step(dt: Float) {
        if (!isActive) return
        t += dt
        val sp = spin ?: run { end(); return }
        flare = max(0f, flare - dt * 2.2f)
        bumpV += (-60f * bump - 2f * 0.7f * sqrt(60f) * bumpV) * dt; bump += bumpV * dt
        kick = max(0f, kick - dt * 1.6f)
        if (waveAge >= 0f) { waveAge += dt; if (waveAge > 1.2f) waveAge = -1f }
        if (flash >= 0f) { flash += dt; if (flash > 1f) flash = -1f }

        when (phase) {
            Phase.Suck -> {
                formT += dt; form = smooth(0f, 0.9f, formT)
                val ready = queueReady
                if (ready && order.isEmpty()) buildOrder()
                if (ready && sp.since() < 0.9f) feedT += dt
                val rsT = (3.6f + 8.4f * smooth(0f, 22f, feedT)) * form * k
                rsBase += (rsT - rsBase) * (1f - exp(-dt * 1.5f))
                rs = rsBase * (1f + 0.035f * bump)
                orb += dt * (1.2f + 3.5f * smooth(0f, 9f, feedT)) * dir
                // it eats one thing after another
                for (it in order) {
                    val p = it.piece
                    val e = feedT - it.start
                    if (e > -0.25f && p.state == 0) p.state = 1 // photograph it just before it goes
                    if (e <= 0f || p.state == 0) continue
                    if (p.state == 1) { if (p.shot == 2) p.state = 3 else continue }
                    p.fallE = e
                    p.eat = if (p.page) (e / (p.tf + 0.2f)).coerceIn(0.01f, 1f)
                    else (e / (p.tf * 0.95f + 1.1f)).coerceIn(0.01f, 1f)
                }
            }
            Phase.Eject -> {
                ejT += dt
                rs = rs0 * (1f - smooth(0.2f, 2.8f, ejT))
                // nothing settles back into the real app while a page is still pouring back out
                val pagesBack = order.none { it.piece.page && it.piece.state == 3 }
                form = 1f - smooth(1.6f, 3.2f, ejT)
                for (it in order) {
                    val p = it.piece
                    if (p.page) {
                        if (p.state != 3) continue
                        val back = 1f - smooth(0.4f, 2.6f, ejT)
                        p.fallE = max(0f, p.fallBack) * back
                        if (back <= 0.001f) { p.reset() }
                        continue
                    }
                    val f = p.fly
                    if (f != null) {
                        val q = ejT - f.start
                        if (q < 0f) continue
                        val kk = 58f * smooth(0.15f, 0.9f, q) + 0.01f
                        val d = 2f * 0.38f * sqrt(kk) + 2.2f * (1f - smooth(0.4f, 1.2f, q)) + 0.5f
                        val k2 = 32f * smooth(0.25f, 1f, q)
                        val d2 = 2f * 0.42f * sqrt(k2) + 1.2f
                        val nn = max(1, ceil(dt / 0.004f).toInt()); val hh = dt / nn
                        repeat(nn) {
                            f.vx += (-kk * f.x - d * f.vx) * hh; f.vy += (-kk * f.y - d * f.vy) * hh
                            f.x += f.vx * hh; f.y += f.vy * hh
                            f.vrx += (-k2 * f.rx - d2 * f.vrx) * hh; f.rx += f.vrx * hh
                            f.vry += (-k2 * f.ry - d2 * f.vry) * hh; f.ry += f.vry * hh
                            f.vrz += (-k2 * f.rz - d2 * f.vrz) * hh; f.rz += f.vrz * hh
                        }
                        if (pagesBack && q > 1.6f && hypot(f.x, f.y) < 0.5f * k && hypot(f.vx, f.vy) < 8f * k &&
                            abs(f.rx) + abs(f.ry) + abs(f.rz) < 1.5f
                        ) { p.reset() }
                    } else if (p.back) {
                        p.fallE = max(0f, p.fallE - dt * 1.6f)
                        if (p.fallE <= 0f && pagesBack) p.reset()
                    }
                }
                // the "10" flies out of the hole and is reeled back to the button
                val q = ejT - 0.35f
                if (q >= 0f) {
                    val kk = 58f * smooth(0.15f, 0.9f, q) + 0.01f
                    val d = 2f * 0.38f * sqrt(kk) + 2.2f * (1f - smooth(0.4f, 1.2f, q)) + 0.5f
                    val k2 = 32f * smooth(0.25f, 1f, q)
                    val d2 = 2f * 0.42f * sqrt(k2) + 1.2f
                    val nn = 4; val hh = dt / nn
                    repeat(nn) {
                        tenVx += (-kk * tenX - d * tenVx) * hh; tenVy += (-kk * tenY - d * tenVy) * hh
                        tenX += tenVx * hh; tenY += tenVy * hh
                        tenVrz += (-k2 * tenRz - d2 * tenVrz) * hh; tenRz += tenVrz * hh
                    }
                }
                val settled = order.none { it.piece.fly != null || it.piece.back || it.piece.state == 3 } && ejT > 3.3f
                if (settled) {
                    phase = Phase.Fade; fadeT = 0f
                    // then Up next slides itself closed again
                    if (queueOpen) scope?.launch { runCatching { closeQueue() } }
                    queueOpen = false
                }
            }
            Phase.Fade -> {
                fadeT += dt; rs = 0f; form = 0f
                if (fadeT > 0.3f) end()
            }
            Phase.Idle -> Unit
        }
        applyShake()
        frame++
    }

    /** Top layer first; in each, nearest first, then the layer's own page. */
    private fun buildOrder() {
        val scr = screen
        val sp = spin
        for (p in pieces) p.bounds = p.coords?.takeIf { it.isAttached }?.let {
            runCatching { it.boundsInRoot() }.getOrNull()
        } ?: Rect.Zero
        fun onScreen(p: HolePiece) = p.attached && (p.owner == null || p.owner !== sp) &&
            p.bounds.width > 1f && p.bounds.height > 1f && p.bounds.overlaps(scr)
        val list = ArrayList<HolePiece>()
        for (layer in HoleLayer.entries.reversed()) {
            val here = pieces.filter { it.layer == layer && onScreen(it) }
            if (here.isEmpty()) continue
            here.filter { !it.page }.sortedBy { hypot(it.bounds.center.x - cx, it.bounds.center.y - cy) }.forEach(list::add)
            here.filter { it.page }.forEach(list::add)
        }
        var tt = 0f
        val items = ArrayList<Item>()
        for (p in list) {
            if (p.page) {
                val s = tt + 0.5f
                items += Item(p, s); tt = s + 3.6f
                p.tf = T_PAGE * (hypot(max(cx - scr.left, scr.right - cx), max(cy - scr.top, scr.bottom - cy)) / (150f * k)).pow(K_PAGE)
            } else {
                items += Item(p, tt); tt += 0.6f
                val b = p.bounds
                val far = maxOf(
                    hypot(b.left - cx, b.top - cy), hypot(b.right - cx, b.top - cy),
                    hypot(b.left - cx, b.bottom - cy), hypot(b.right - cx, b.bottom - cy),
                )
                p.tf = T_PIECE * (far / (150f * k)).pow(K_PIECE)
            }
        }
        order = items
    }

    /** Everything that's on its way in or out right now, for the overlay to draw. */
    internal fun moving(): List<HolePiece> = order.map { it.piece }.filter { it.state == 3 && it.bitmap != null }
        // drawn bottom layer first, each page under its own pieces
        .sortedWith(compareBy({ it.layer.ordinal }, { if (it.page) 0 else 1 }))

    // ---- the fall ---------------------------------------------------------------------------------

    /** Where a point of something falling in is now: from rest, faster and faster, winding round. */
    fun infall(sx: Float, sy: Float, e: Float, t0: Float, kExp: Float, out: FloatArray, i: Int) {
        val dx = sx - cx; val dy = sy - cy
        val d = hypot(dx, dy).coerceAtLeast(0.01f)
        val f = atan2(dy, dx)
        val tt = t0 * (d / (150f * k)).pow(kExp)
        val p = (e / tt).coerceIn(0f, 1f)
        val r = d * cos(p * PI.toFloat() / 2f).coerceAtLeast(0f).pow(4f / 3f)
        val th = f + dir * 2.4f * (sqrt(d / max(r, d * 0.04f)) - 1f)
        out[i] = cx + cos(th) * r
        out[i + 1] = cy + sin(th) * r
    }

    /** The black's edge (px). */
    fun shadowR() = max(4f * k, rs * 2.6f * form) * 1.04f

    const val T_PIECE = 2.9f
    const val K_PIECE = 0.8f
    const val T_PAGE = 3.2f
    const val K_PAGE = 0.7f

    fun smooth(a: Float, b: Float, x: Float): Float { val tt = ((x - a) / (b - a)).coerceIn(0f, 1f); return tt * tt * (3 - 2 * tt) }
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
}
