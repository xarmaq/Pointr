package app.pointr.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private val Dark = Color(0xFF1E232B)
private val Light = Color(0xFFF3F6FA)
private const val LightAngle = -0.45f // radians above the roller's front: the key light sits high

/**
 * A knurled steel roller sunk into a well at the pad's corner. Drag it like a mouse wheel:
 * it keeps spinning when flicked, clicks through detents under the thumb, and sinks a
 * little while held.
 */
@Composable
fun ScrollWheel(onScroll: (dyDp: Float) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableFloatStateOf(0f) } // px travelled by the surface
    var pressed by remember { mutableStateOf(false) }
    var fling by remember { mutableStateOf<Job?>(null) }
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(0.55f, 900f), label = "sink")

    Canvas(
        modifier.pointerInput(Unit) {
            val detentPx = 12.dp.toPx()
            var lastBuzz = 0L
            fun apply(dyPx: Float) {
                val before = floor(phase / detentPx)
                phase += dyPx
                if (floor(phase / detentPx) != before) {
                    // Fast spins would smear into a buzz: thin the detents out and soften them.
                    val now = SystemClock.uptimeMillis()
                    val gap = now - lastBuzz
                    if (gap > 22) {
                        haptics.detent(if (gap > 90) 0.75f else 0.4f)
                        lastBuzz = now
                    }
                }
                onScroll(dyPx / density)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                fling?.cancel()
                pressed = true
                haptics.press()
                val vt = VelocityTracker()
                vt.addPosition(down.uptimeMillis, down.position)
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    apply(c.positionChange().y)
                    vt.addPosition(c.uptimeMillis, c.position)
                    c.consume()
                }
                pressed = false
                val v = vt.calculateVelocity().y
                if (abs(v) > 120f) fling = scope.launch { inertia(0f, v) { _, dy -> apply(dy) } }
            }
        }
    ) {
        val w = size.width
        val h = size.height

        // The well the roller sits in.
        val wellR = CornerRadius(w / 2)
        drawRoundRect(Color(0xFF040507), cornerRadius = wellR)
        drawRoundRect(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.12f to Color.Transparent,
                0.9f to Color.Transparent, 1f to Color.White.copy(alpha = 0.05f)),
            cornerRadius = wellR,
        )
        drawRoundRect(lerp(P.PadEdge, accent, sink * 0.8f), cornerRadius = wellR, style = Stroke(1.dp.toPx()))

        val inset = (3f + 1.2f * sink).dp.toPx()
        val rw = w - 2 * inset
        val rh = h - 2 * inset
        val roller = Path().apply { addRoundRect(RoundRect(inset, inset, w - inset, h - inset, CornerRadius(rw / 2))) }
        clipPath(roller) { drawRoller(Offset(inset, inset), Size(rw, rh), phase, sink, accent) }
    }
}

/** Brightness of a surface patch whose normal points [theta] radians up/down from the viewer. */
private fun lum(theta: Float): Float = (0.18f + 0.82f * cos(theta - LightAngle).coerceAtLeast(0f))

private fun DrawScope.drawRoller(o: Offset, s: Size, phase: Float, sink: Float, accent: Color) {
    val w = s.width
    val h = s.height
    val cylR = h / 2
    val cy = o.y + h / 2

    // Body: shaded like a cylinder lying across the screen.
    val stops = (0..16).map { i ->
        val theta = -PI.toFloat() / 2 + PI.toFloat() * i / 16
        val y = (sin(theta) + 1) / 2
        y to lerp(Dark, Light, lum(theta) * 0.55f)
    }.toTypedArray()
    drawRect(Brush.verticalGradient(*stops, startY = o.y, endY = o.y + h), o, s)

    // Diamond knurling: rows wrap around the cylinder and foreshorten towards its edges.
    val rowsAround = 64
    val dTheta = (2 * PI / rowsAround).toFloat()
    val turn = phase / cylR
    val cols = 4
    // Offset rows interlock, so each diamond spans two row pitches.
    val dw = w / cols
    val tilt = 0.55f // how steep each pyramid facet is
    for (i in 0 until rowsAround) {
        var theta = (i * dTheta + turn) % (2 * PI).toFloat()
        if (theta > PI) theta -= (2 * PI).toFloat()
        if (theta < -PI) theta += (2 * PI).toFloat()
        if (theta <= -PI / 2 + 0.05f || theta >= PI / 2 - 0.05f) continue
        val y = cy + cylR * sin(theta)
        val halfH = cylR * dTheta * cos(theta)
        val up = lum(theta - tilt)
        val down = lum(theta + tilt)
        val shift = if (i % 2 == 0) 0f else dw / 2
        for (j in -1..cols) {
            val cx = o.x + j * dw + dw / 2 + shift
            val hw = dw * 0.5f
            val top = Offset(cx, y - halfH)
            val bottom = Offset(cx, y + halfH)
            val left = Offset(cx - hw, y)
            val right = Offset(cx + hw, y)
            val apex = Offset(cx + hw * 0.08f, y - halfH * 0.08f)
            facet(left, top, apex, lerp(Dark, Light, (up + 0.10f).coerceAtMost(1f)))
            facet(top, right, apex, lerp(Dark, Light, up * 0.86f))
            facet(right, bottom, apex, lerp(Dark, Light, down * 0.55f))
            facet(bottom, left, apex, lerp(Dark, Light, down * 0.7f))
        }
    }

    // Specular streak where the key light hits, then ambient occlusion at the rounded sides.
    val specY = cy + cylR * sin(LightAngle)
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.38f - 0.12f * sink), 1f to Color.Transparent,
            startY = specY - h * 0.09f, endY = specY + h * 0.09f,
        ),
        o, s,
    )
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.55f), 0.22f to Color.Transparent,
            0.78f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
            startX = o.x, endX = o.x + w,
        ),
        o, s,
    )
    // Held: a faint accent cast, as if lit by the pad's glow.
    if (sink > 0f) drawRect(accent.copy(alpha = 0.14f * sink), o, s)
}

private fun DrawScope.facet(a: Offset, b: Offset, c: Offset, color: Color) {
    val p = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
    drawPath(p, color)
}
