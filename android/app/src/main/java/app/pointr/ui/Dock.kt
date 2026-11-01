package app.pointr.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.pointr.R
import app.pointr.net.WindowItem
import kotlin.math.exp

/**
 * The PC's open windows as a macOS-style dock: slide a finger along it to magnify,
 * lift over an icon to bring that window to the front.
 */
@Composable
fun Dock(items: List<WindowItem>, onFocus: (WindowItem) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    var fingerX by remember { mutableFloatStateOf(Float.NaN) }
    var hovered by remember { mutableStateOf<WindowItem?>(null) }
    val currentItems by rememberUpdatedState(items)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(70.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (items.isEmpty()) {
                Text(stringResource(R.string.no_windows), color = P.Muted, fontSize = 13.sp)
                return@BoxWithConstraints
            }
            val gap = 10.dp
            val icon = min(46.dp, (maxWidth - 24.dp) / items.size - gap).coerceAtLeast(26.dp)
            val visible = items.take(((maxWidth - 24.dp) / (icon + gap)).toInt().coerceAtLeast(1))
            val slot = with(density) { (icon + gap).toPx() }
            val rowWidth = slot * visible.size
            val startX = with(density) { maxWidth.toPx() } / 2 - rowWidth / 2

            fun indexAt(x: Float) = ((x - startX) / slot).toInt().takeIf { x >= startX && it in visible.indices }

            Row(
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun track(x: Float) {
                            fingerX = x
                            val item = indexAt(x)?.let { currentItems.getOrNull(it) }
                            if (item != hovered) { hovered = item; if (item != null) haptics.tick() }
                        }
                        track(down.position.x)
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            if (!c.pressed) break
                            track(c.position.x)
                            c.consume()
                        }
                        hovered?.let { onFocus(it); haptics.confirm() }
                        hovered = null
                        fingerX = Float.NaN
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visible.forEachIndexed { i, w ->
                    val center = startX + slot * i + slot / 2
                    val target = if (fingerX.isNaN()) 1f else {
                        val d = (fingerX - center) / with(density) { 58.dp.toPx() }
                        1f + 0.45f * exp(-d * d)
                    }
                    val scale by animateFloatAsState(target, label = "mag")
                    Box(
                        Modifier
                            .size(icon)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationY = -(scale - 1f) * size.height * 0.45f
                            }
                            .clip(RoundedCornerShape(icon * 0.24f))
                            .background(P.Button),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (w.icon != null) Image(w.icon, w.app, Modifier.size(icon * 0.86f))
                        else Text(w.app.take(1).uppercase(), color = P.Text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(
            hovered?.let { it.title.ifBlank { it.app } } ?: "",
            color = P.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 6.dp, start = 24.dp, end = 24.dp).height(16.dp),
        )
    }
}
