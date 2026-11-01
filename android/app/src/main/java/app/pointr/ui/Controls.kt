package app.pointr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.pointr.net.PointrClient
import app.pointr.net.WindowItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A physical key: convex and lifted on a soft shadow at rest; when pressed it sinks flush,
 * turns concave and the face darkens. Releasing it throws a ring of dots outwards.
 */
@Composable
fun RoundButton(
    icon: Int,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeIcon: Int = icon,
    activeColor: Color = LocalAccent.current,
    ring: Color? = null,
    contentDescription: String? = null,
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(0.6f, if (pressed) 2200f else 700f), label = "sink")
    val fill by animateColorAsState(if (active) activeColor else P.Button, label = "f")
    val tint by animateColorAsState(if (active) Color.White else P.Text, label = "t")
    val burst = remember { Animatable(1f) }
    val burstColor = if (active) Color.White else ring ?: activeColor

    LaunchedEffect(pressed) { if (pressed) haptics.press() }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            // lifted: shadow falls below; pressed: key sits on the plate
            drawCircle(Color.Black.copy(alpha = 0.55f * (1 - sink)), r * 0.98f, center + Offset(0f, (4f - 3.5f * sink).dp.toPx()))
            if (active) drawCircle(
                Brush.radialGradient(listOf(fill.copy(alpha = 0.35f), Color.Transparent), center, r * 1.6f), r * 1.6f,
            )
            val t = burst.value
            if (t < 1f) for (i in 0 until 16) {
                val a = i / 16f * 2f * PI.toFloat()
                val rr = r * (0.75f + 0.65f * t)
                drawCircle(burstColor.copy(alpha = 0.9f * (1 - t) * (1 - t)), 2.2.dp.toPx() * (1 - 0.5f * t),
                    center + Offset(cos(a) * rr, sin(a) * rr))
            }
        }
        Box(
            Modifier
                .size(size)
                .scale(1f - 0.06f * sink)
                .clip(CircleShape)
                .background(
                    // convex at rest (lit from above), concave when pressed (lit from below)
                    Brush.verticalGradient(listOf(lerp(fill, Color.White, 0.09f * (1 - sink)), fill, lerp(fill, Color.White, 0.05f * sink)))
                )
                .drawWithContent {
                    drawContent()
                    if (sink > 0f) drawCircle(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f * sink), Color.Transparent), 0f, this.size.height * 0.45f),
                    )
                }
                .border(if (ring != null) 2.dp else 1.dp, ring ?: lerp(P.ButtonEdge, Color.White, 0.08f * (1 - sink)), CircleShape)
                .clickable(source, indication = null) {
                    onClick()
                    scope.launch { burst.snapTo(0f); burst.animateTo(1f, tween(480)) }
                },
            contentAlignment = Alignment.Center,
        ) {
            IxIcon(if (active) activeIcon else icon, tint, Modifier.scale(1f - 0.05f * sink), size = size * 0.44f,
                contentDescription = contentDescription)
        }
    }
}

/**
 * The clicker as one slab floating over the trackpad's foot (the caller frames it in glass): back/next on the ends, laser, volume and
 * screen-hide between. Drag it up (or tap the grabber) to slide the PC's windows out above.
 */
@Composable
fun RemotePanel(
    client: PointrClient,
    laserMode: Boolean,
    onLaserMode: (Boolean) -> Unit,
    blur: Boolean,
    volume: Float,
    muted: Boolean,
    dockOpen: Boolean,
    onDockOpen: (Boolean) -> Unit,
    windows: List<WindowItem>,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val accent = LocalAccent.current
    var showVolume by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        modifier
            .fillMaxWidth()
            .draggable(
                rememberDraggableState { drag += it },
                Orientation.Vertical,
                onDragStopped = { v ->
                    if (!dockOpen && (drag < -36f || v < -700f)) { onDockOpen(true); haptics.tick() }
                    else if (dockOpen && (drag > 36f || v > 700f)) { onDockOpen(false); haptics.tick() }
                    drag = 0f
                },
            ),
    ) {
        Box(
            Modifier.fillMaxWidth().height(20.dp).clickable(remember { MutableInteractionSource() }, null) {
                onDockOpen(!dockOpen); haptics.tick()
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = if (dockOpen) 0.35f else 0.18f)))
        }
        AnimatedVisibility(dockOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Dock(windows, onFocus = { client.focus(it.id) }, modifier = Modifier.padding(horizontal = 10.dp))
                Box(Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Ix.Prev, 66.dp, { client.key("left"); haptics.confirm() }, contentDescription = "Previous")
            RoundButton(Ix.Laser, 50.dp, { onLaserMode(!laserMode); haptics.click() },
                active = laserMode, activeIcon = Ix.LaserOn, activeColor = P.Laser, contentDescription = "Laser")
            Box {
                RoundButton(if (muted) Ix.Mute else Ix.Volume, 50.dp, { showVolume = !showVolume; haptics.click() },
                    active = showVolume, activeIcon = if (muted) Ix.MuteOn else Ix.VolumeOn, contentDescription = "Volume")
                if (showVolume) VolumePopup(client, volume, muted) { showVolume = false }
            }
            RoundButton(Ix.Moon, 50.dp, { client.toggleBlur(); haptics.click() },
                active = blur, activeIcon = Ix.MoonOn, contentDescription = "Hide screen")
            RoundButton(Ix.Next, 66.dp, { client.key("right"); haptics.confirm() }, ring = accent, contentDescription = "Next")
        }
    }
}

@Composable
private fun VolumePopup(client: PointrClient, volume: Float, muted: Boolean, onDismiss: () -> Unit) {
    val haptics = LocalHaptics.current
    val accent = LocalAccent.current
    var level by remember { mutableFloatStateOf(volume) }
    var lastSent by remember { mutableLongStateOf(0L) }
    val shown = if (muted) 0f else level
    val h = 210.dp
    val w = 66.dp

    Popup(
        alignment = Alignment.BottomCenter,
        offset = with(LocalDensity.current) { IntOffset(0, -(64.dp.toPx()).roundToInt()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(shown * 100).roundToInt()}", color = P.Text, fontSize = 15.sp, fontFamily = Unbounded,
                fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            Box(
                Modifier
                    .size(w, h)
                    .clip(RoundedCornerShape(w / 2))
                    .background(P.Panel)
                    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.1f), P.PadEdge)), RoundedCornerShape(w / 2))
                    .pointerInput(Unit) {
                        fun set(y: Float) {
                            val v = (1f - y / size.height).coerceIn(0f, 1f)
                            if ((v * 20).roundToInt() != (level * 20).roundToInt()) haptics.detent(0.5f)
                            level = v
                            val now = System.currentTimeMillis()
                            if (now - lastSent > 30 || v == 0f || v == 1f) { client.setVolume(v); lastSent = now }
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            set(down.position.y)
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.first()
                                if (!c.pressed) break
                                set(c.position.y)
                                c.consume()
                            }
                            client.setVolume(level)
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Canvas(Modifier.size(w, h)) {
                    val fillH = size.height * shown
                    drawRoundRect(
                        Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.15f), accent), startY = size.height - fillH, endY = size.height),
                        topLeft = Offset(0f, size.height - fillH),
                        size = Size(size.width, fillH),
                        cornerRadius = CornerRadius(size.width / 2),
                    )
                }
                Box(
                    Modifier.padding(bottom = 10.dp).size(46.dp).clip(CircleShape).clickable { client.toggleMute(); haptics.click() },
                    contentAlignment = Alignment.Center,
                ) {
                    IxIcon(if (muted || shown == 0f) Ix.MuteOn else Ix.VolumeOn, Color.White, size = 24.dp)
                }
            }
        }
    }
}

/** Small status chip floated over the trackpad while laser or blur is on. */
@Composable
fun ModeChip(text: String, color: Color, icon: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        IxIcon(icon, color, size = 14.dp)
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
