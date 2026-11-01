package app.pointr.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.pointr.data.UserSettings
import app.pointr.net.ScreenFrame
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import app.pointr.net.PointrClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val WheelWidth = 34.dp
private val WheelGap = 8.dp
private const val NotchFraction = 0.34f
private val SlotGap = 14.dp

/** Where the wheel's slot sits: on the right edge, above whatever floats over the pad's bottom. */
private class Slot(val top: Float, val bottom: Float)

private fun slot(h: Float, topInset: Float, bottomInset: Float, gap: Float): Slot {
    val visible = h - topInset - bottomInset
    val bottom = h - bottomInset - gap
    return Slot(bottom - visible * NotchFraction, bottom)
}

/**
 * The pad fills the screen; the header and remote float over it as glass. A slot is cut
 * into its right edge for the scroll wheel, with rounded outer corners and concave fillets.
 */
class NotchedPadShape(
    private val radius: Dp,
    private val notchWidth: Dp,
    private val topInset: Dp,
    private val bottomInset: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val nw = with(density) { notchWidth.toPx() }
        val c = r * 0.6f // small convex corners around the slot
        val k = r * 0.6f // concave fillets
        val w = size.width
        val h = size.height
        val sl = with(density) { slot(h, topInset.toPx(), bottomInset.toPx(), SlotGap.toPx()) }
        val p = Path().apply {
            moveTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
            lineTo(w, sl.top - c)
            arcTo(Rect(w - 2 * c, sl.top - 2 * c, w, sl.top), 0f, 90f, false)
            lineTo(w - nw + k, sl.top)
            arcTo(Rect(w - nw, sl.top, w - nw + 2 * k, sl.top + 2 * k), 270f, -90f, false)
            lineTo(w - nw, sl.bottom - k)
            arcTo(Rect(w - nw, sl.bottom - 2 * k, w - nw + 2 * k, sl.bottom), 180f, -90f, false)
            lineTo(w - c, sl.bottom)
            arcTo(Rect(w - 2 * c, sl.bottom, w, sl.bottom + 2 * c), 270f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Where the PC frame is drawn: fitted into the pad's visible middle, aspect kept. */
private fun frameRect(size: Size, f: ScreenFrame, top: Float, bottom: Float, side: Float, wheel: Float): Rect {
    val aw = size.width - 2 * side - wheel // keep clear of the scroll wheel's slot
    val ah = size.height - top - bottom - 2 * side
    val scale = minOf(aw / f.image.width, ah / f.image.height)
    val w = f.image.width * scale
    val h = f.image.height * scale
    val l = side + (aw - w) / 2
    val t = top + side + (ah - h) / 2
    return Rect(l, t, l + w, t + h)
}

private class Ripple(val at: Offset, val anim: Animatable<Float, *>)

@Composable
fun Trackpad(
    client: PointrClient,
    settings: UserSettings,
    laserMode: Boolean,
    onLaserTouch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    screen: ScreenFrame? = null,
    cursor: Offset? = null,
) {
    val haptics = LocalHaptics.current
    val accent = if (laserMode) P.Laser else LocalAccent.current
    val scope = rememberCoroutineScope()
    val shape = remember(topInset, bottomInset) { NotchedPadShape(30.dp, WheelWidth + WheelGap, topInset, bottomInset) }
    val currentScreen by rememberUpdatedState(screen)

    val touches = remember { mutableStateListOf<Offset>() }
    var lastTouch by remember { mutableStateOf(Offset.Unspecified) }
    val glow = remember { Animatable(0f) }
    // How far the dot membrane is pushed in under the finger; overshoots negative on rebound.
    val press = remember { Animatable(0f) }
    val intro = remember { Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = LinearEasing)) }
    var pressureBoost by remember { mutableFloatStateOf(0f) } // 0..1 extra depth from finger pressure
    val ripples = remember { mutableStateListOf<Ripple>() }
    val currentSettings by rememberUpdatedState(settings)
    var flingJob by remember { mutableStateOf<Job?>(null) }

    fun pressTo(target: Float, damping: Float, stiffness: Float) =
        scope.launch { press.animateTo(target, spring(damping, stiffness)) }

    /** Clicks through: dip a little deeper, then spring back with a bouncy rebound. */
    fun clickThrough() = scope.launch {
        press.animateTo(1.15f, tween(55))
        press.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 360f))
    }

    fun ripple(at: Offset) {
        val r = Ripple(at, Animatable(0f))
        ripples += r
        scope.launch {
            r.anim.animateTo(1f, tween(950, easing = LinearEasing))
            ripples -= r
        }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(P.Pad)
                .border(1.dp, P.PadEdge, shape)
                .drawBehind {
                    val g = glow.value
                    val depth = press.value + 0.5f * pressureBoost
                    val open = intro.value
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val maxD = hypot(cx, cy)
                    if (open < 1f) drawCircle(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * (1 - open)), Color.Transparent), Offset(cx, cy), maxD * (0.3f + open)),
                        maxD * (0.3f + open), Offset(cx, cy),
                    )
                    val pts = if (touches.isNotEmpty()) touches.toList()
                    else if (g > 0f && lastTouch.isSpecified()) listOf(lastTouch) else emptyList()
                    val dents = if (touches.isNotEmpty()) touches.toList()
                    else if (abs(depth) > 0.002f && lastTouch.isSpecified()) listOf(lastTouch) else emptyList()
                    val frame = currentScreen
                    val frameBox = frame?.let { frameRect(size, it, topInset.toPx(), bottomInset.toPx(), 14.dp.toPx(), (WheelWidth + WheelGap).toPx()) }
                    if (frame != null && frameBox != null) {
                        val clip = Path().apply { addRoundRect(RoundRect(frameBox, CornerRadius(14.dp.toPx()))) }
                        clipPath(clip) {
                            drawImage(
                                frame.image,
                                dstOffset = IntOffset(frameBox.left.roundToInt(), frameBox.top.roundToInt()),
                                dstSize = IntSize(frameBox.width.roundToInt(), frameBox.height.roundToInt()),
                                alpha = 0.92f,
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                        drawPath(clip, Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
                        val c = cursor
                        if (c != null) {
                            val at = Offset(frameBox.left + c.x * frameBox.width, frameBox.top + c.y * frameBox.height)
                            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), at, 22.dp.toPx()), 22.dp.toPx(), at)
                            drawCircle(accent, 6.dp.toPx(), at)
                            drawCircle(Color.White, 3.5.dp.toPx(), at)
                        }
                    }
                    val reach = 120.dp.toPx()
                    val dentR = 50.dp.toPx()
                    val dentR2 = dentR * dentR

                    for (p in pts) drawCircle(
                        Brush.radialGradient(listOf(accent.copy(alpha = 0.20f * g), Color.Transparent), p, reach * 1.4f),
                        reach * 1.4f, p,
                    )
                    // the well itself: a soft occlusion shadow where the surface sinks
                    if (depth > 0f) for (p in dents) drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f * min(depth, 1f)), Color.Transparent), p, dentR * 1.3f),
                        dentR * 1.3f, p,
                    )

                    val step = 17.dp.toPx()
                    val base = 1.15.dp.toPx()
                    val big = 3.1.dp.toPx()
                    val waveReach = 240.dp.toPx() // how far the click wave travels
                    val waveLen = 30.dp.toPx()
                    val waveDecay = 70.dp.toPx()
                    val wavePush = 5.dp.toPx()
                    val idle = Color.White.copy(alpha = if (frame != null) 0.06f else 0.10f)
                    val lit = Color.White.copy(alpha = 0.95f)
                    val cols = (size.width / step).toInt()
                    val rows = (size.height / step).toInt()
                    val ox = (size.width - (cols - 1) * step) / 2
                    val oy = (size.height - (rows - 1) * step) / 2
                    for (j in 0 until rows) for (i in 0 until cols) {
                        val x = ox + i * step
                        val y = oy + j * step
                        var k = 0f
                        for (p in pts) {
                            val d = hypot(x - p.x, y - p.y)
                            if (d < reach) { val f = 1 - d / reach; k = max(k, f * f) }
                        }
                        k *= g

                        // Gaussian dent z(x,y); dots slide into it, shrink with depth and catch
                        // light from the top-left on the far wall, shadow on the near one.
                        var z = 0f
                        var gx = 0f
                        var gy = 0f
                        var px = x
                        var py = y
                        for (p in dents) {
                            val dx = x - p.x
                            val dy = y - p.y
                            val q = (dx * dx + dy * dy) / dentR2
                            if (q > 7f) continue
                            val e = depth * exp(-q)
                            z += e
                            gx -= 2f * dx / dentR2 * e
                            gy -= 2f * dy / dentR2 * e
                            px -= dx * 0.36f * e
                            py -= dy * 0.36f * e
                        }
                        // A click sends a damped ripple across the membrane, like a struck drum skin.
                        for (r in ripples) {
                            val t = r.anim.value
                            val dx = x - r.at.x
                            val dy = y - r.at.y
                            val d = hypot(dx, dy).coerceAtLeast(1f)
                            val behind = t * waveReach - d
                            if (behind < 0f) continue
                            val fade = (1 - t) * (1 - t)
                            val amp = 0.6f * fade * exp(-behind / waveDecay)
                            val ph = behind / waveLen * 2f * PI.toFloat()
                            val wv = sin(ph) * amp
                            val slope = cos(ph) * amp * 2f * PI.toFloat() / waveLen // dz/d(behind) = -dz/dd
                            z += wv
                            gx += slope * dx / d
                            gy += slope * dy / d
                            px -= dx / d * wv * wavePush
                            py -= dy / d * wv * wavePush
                            k = max(k, abs(wv) * 1.1f)
                        }
                        val shade = (-(gx + gy) * 0.7f * dentR).coerceIn(-1f, 1f)

                        var c = if (k <= 0.004f) idle else lerp(idle, accent, min(1f, k * 1.25f))
                        if (shade > 0.01f) c = lerp(c, lit, min(1f, shade * 0.9f))
                        else if (shade < -0.01f) c = c.copy(alpha = c.alpha * (1f + shade).coerceAtLeast(0.12f))
                        if (z > 0f) c = c.copy(alpha = c.alpha * (1f - 0.3f * min(z, 1f)))
                        val radius = (base + (big - base) * k) * (1f - 0.45f * z).coerceIn(0.4f, 1.7f)
                        if (open < 1f) {
                            // nearer dots leave first; each eases out from the centre to its seat
                            val local = ((open * 1.5f - hypot(x - cx, y - cy) / maxD * 0.5f)).coerceIn(0f, 1f)
                            val e = 1 - (1 - local) * (1 - local) * (1 - local)
                            px = cx + (px - cx) * e
                            py = cy + (py - cy) * e
                            c = lerp(accent, c, e).copy(alpha = lerp(accent, c, e).alpha * local)
                        }
                        drawCircle(c, radius, Offset(px, py))
                    }
                }
                .pointerInput(laserMode) {
                    val slop = viewConfiguration.touchSlop
                    val edge = 34.dp.toPx()
                    var lastTapUp = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val s = currentSettings
                        val t0 = down.uptimeMillis
                        var lastTime = t0
                        var maxPointers = 1
                        var travel = 0f
                        var moved = false
                        var pendX = 0f
                        var pendY = 0f
                        var longArmed = false
                        var dragging = false
                        var scrolled = false
                        var three = Offset.Zero
                        var threeFired = false
                        val vt = VelocityTracker()
                        var finger = down.position
                        var edgeJob: Job? = null

                        // Pressure (usually contact area on Android) only deepens the dent visually;
                        // it is too noisy to press the mouse button with.
                        var p0 = down.pressure

                        fun startDrag() {
                            dragging = true
                            client.click("l", "down")
                            // Holding near an edge keeps the cursor travelling, so long drags
                            // never need the finger to be lifted and replaced.
                            edgeJob = scope.launch {
                                var last = withFrameNanos { it }
                                while (true) withFrameNanos { now ->
                                    val dt = (now - last) / 1e9f
                                    last = now
                                    val ex = edgePush(finger.x, size.width.toFloat(), edge)
                                    val ey = edgePush(finger.y, size.height.toFloat(), edge)
                                    if (ex != 0f || ey != 0f) {
                                        val speed = 700f * currentSettings.pointerSpeed * dt
                                        client.move(ex * abs(ex) * speed, ey * abs(ey) * speed)
                                    }
                                }
                            }
                        }

                        touches.clear(); touches += down.position
                        lastTouch = down.position
                        pressureBoost = 0f
                        scope.launch { glow.animateTo(1f, tween(110)) }
                        pressTo(0.5f, 0.55f, 1400f)
                        haptics.press()

                        // Second touch right after a tap, near it: a drag if the finger then moves,
                        // a double click if it just lifts.
                        val afterTap = !laserMode && t0 - lastTapUp < 220 &&
                            (down.position - lastTapPos).getDistance() < 28.dp.toPx()
                        lastTapUp = 0
                        if (laserMode) onLaserTouch(true)

                        while (true) {
                            val canArm = !laserMode && !afterTap && !longArmed && !moved && !dragging && maxPointers == 1
                            val event = if (canArm) {
                                val left = 650 - (SystemClock.uptimeMillis() - t0)
                                if (left <= 0) null else withTimeoutOrNull(left) { awaitPointerEvent(PointerEventPass.Main) }
                            } else awaitPointerEvent(PointerEventPass.Main)

                            if (event == null) {
                                // Held still: arm a drag. Lifting now does nothing (no surprise right click).
                                longArmed = true
                                haptics.deep()
                                pressTo(0.95f, 0.5f, 700f)
                                continue
                            }

                            val pressed = event.changes.filter { it.pressed }
                            touches.clear(); pressed.forEach { touches += it.position }
                            if (pressed.isNotEmpty()) { lastTouch = pressed[0].position; finger = pressed[0].position }
                            maxPointers = max(maxPointers, pressed.size)
                            if (pressed.isEmpty()) break

                            val now = event.changes[0].uptimeMillis
                            val dt = (now - lastTime).coerceAtLeast(1)
                            lastTime = now
                            val delta = pressed.fold(Offset.Zero) { a, c -> a + c.positionChange() } / pressed.size.toFloat()
                            val dDp = delta / density.density
                            event.changes.forEach { it.consume() }

                            if (pressed.size == 1 && maxPointers == 1 && !laserMode) {
                                val pr = pressed[0].pressure
                                if (now - t0 < 70) p0 = min(p0, pr) // settle the baseline on landing
                                val target = ((pr - p0) / max(p0 * 0.6f, 0.15f)).coerceIn(0f, 1f)
                                pressureBoost += (target - pressureBoost) * 0.25f // smoothed: no flicker
                            }

                            when {
                                pressed.size == 1 && maxPointers == 1 -> {
                                    travel += delta.getDistance()
                                    var px = dDp.x
                                    var py = dDp.y
                                    if (!moved) {
                                        pendX += px; pendY += py
                                        if (travel < slop * 0.5f) continue
                                        moved = true
                                        px = pendX; py = pendY
                                    }
                                    if (laserMode) {
                                        val gain = 2.2f * s.laserSensitivity
                                        client.laserMove(px * gain, py * gain)
                                    } else {
                                        if ((longArmed || afterTap) && !dragging) { startDrag(); haptics.click() }
                                        val gain = pointerGain(s, hypot(px, py) / dt)
                                        client.move(px * gain, py * gain)
                                    }
                                }
                                pressed.size == 2 && !laserMode -> {
                                    travel += delta.getDistance()
                                    if (travel > slop * 1.5f) { moved = true; scrolled = true }
                                    val centroid = (pressed[0].position + pressed[1].position) / 2f
                                    vt.addPosition(now, centroid)
                                    if (scrolled) sendScroll(client, s, dDp.x, dDp.y)
                                }
                                pressed.size >= 3 && !laserMode -> {
                                    three += dDp
                                    travel += delta.getDistance()
                                    if (travel > slop * 1.5f) moved = true
                                    if (!threeFired && three.getDistance() > 56f) {
                                        threeFired = true
                                        client.combo(
                                            if (abs(three.x) > abs(three.y)) { if (three.x < 0) "alttab" else "altshifttab" }
                                            else if (three.y < 0) "taskview" else "desktop"
                                        )
                                        haptics.confirm()
                                    }
                                }
                            }
                        }

                        edgeJob?.cancel()
                        pressureBoost = 0f
                        val duration = lastTime - t0
                        // Fingers of a multi-finger tap land and lift a little apart, so allow them longer.
                        val tap = !moved && !longArmed && !dragging && duration < (if (maxPointers == 1) 220 else 300)
                        when {
                            laserMode -> onLaserTouch(false)
                            dragging -> { client.click("l", "up"); haptics.release() }
                            longArmed && !moved && maxPointers == 1 -> {
                                val f = currentScreen
                                val box = f?.let {
                                    frameRect(Size(size.width.toFloat(), size.height.toFloat()), it,
                                        topInset.toPx(), bottomInset.toPx(), 14.dp.toPx(), (WheelWidth + WheelGap).toPx())
                                }
                                if (box != null && box.contains(down.position)) {
                                    client.cursorTo((down.position.x - box.left) / box.width, (down.position.y - box.top) / box.height)
                                    ripple(down.position)
                                    haptics.confirm()
                                }
                            }
                            tap -> {
                                when (maxPointers) {
                                    // a second tap doesn't arm a third: no accidental drag after a double click
                                    1 -> { client.click(); if (!afterTap) { lastTapUp = lastTime; lastTapPos = down.position } }
                                    2 -> client.click("r")
                                    else -> client.click("m")
                                }
                                ripple(down.position)
                                haptics.click()
                            }
                            scrolled -> {
                                val v = vt.calculateVelocity()
                                val vx = v.x / density.density
                                val vy = v.y / density.density
                                if (hypot(vx, vy) > 250f) flingJob = scope.launch {
                                    inertia(vx, vy) { dx, dy -> sendScroll(client, currentSettings, dx, dy) }
                                }
                            }
                        }
                        if (tap) clickThrough() else pressTo(0f, 0.4f, 420f)
                        touches.clear()
                        scope.launch { glow.animateTo(0f, tween(420)) }
                    }
                }
        )

        val d = LocalDensity.current
        val sl = with(d) { slot(maxHeight.toPx(), topInset.toPx(), bottomInset.toPx(), SlotGap.toPx()) }
        val wheelTop = with(d) { sl.top.toDp() } + WheelGap
        val wheelHeight = with(d) { (sl.bottom - sl.top).toDp() } - WheelGap * 2
        ScrollWheel(
            onScroll = { dyDp -> client.scroll(0f, -dyDp * 3.2f * currentSettings.scrollSpeed) },
            modifier = Modifier
                .offset { IntOffset((maxWidth - WheelWidth - 3.dp).roundToPx(), wheelTop.roundToPx()) }
                .size(WheelWidth, wheelHeight),
        )
    }
}

private fun Offset.isSpecified() = this != Offset.Unspecified

/**
 * Pointer gain for a finger speed in dp/ms: slow, deliberate movement gets sub-pixel
 * precision; fast flicks get the user's acceleration on top.
 */
private fun pointerGain(s: UserSettings, v: Float): Float {
    val t = ((v - 0.02f) / 0.12f).coerceIn(0f, 1f)
    val precision = 0.4f + 0.6f * t * t * (3 - 2 * t)
    return 1.6f * s.pointerSpeed * precision * (1f + s.acceleration * min(v / 0.5f, 3f))
}

/** -1..1: how far into the edge zone of [0, max] the finger is, and towards which side. */
private fun edgePush(v: Float, max: Float, zone: Float): Float = when {
    v < zone -> -(1f - v / zone)
    v > max - zone -> 1f - (max - v) / zone
    else -> 0f
}.coerceIn(-1f, 1f)

/** Two-finger deltas in dp → wheel units (120 = one notch). */
private fun sendScroll(client: PointrClient, s: UserSettings, dxDp: Float, dyDp: Float) {
    val k = 3.2f * s.scrollSpeed
    val sign = if (s.naturalScroll) 1f else -1f
    client.scroll(-dxDp * k * sign, dyDp * k * sign)
}

/** Exponential-decay momentum, like a laptop trackpad after the fingers lift. */
suspend fun inertia(vx: Float, vy: Float, emit: (Float, Float) -> Unit) {
    var x = vx
    var y = vy
    var last = androidx.compose.runtime.withFrameNanos { it }
    while (hypot(x, y) > 40f) {
        androidx.compose.runtime.withFrameNanos { now ->
            val dt = (now - last) / 1e9f
            last = now
            emit(x * dt, y * dt)
            val decay = exp(-3.4f * dt)
            x *= decay; y *= decay
        }
    }
}
