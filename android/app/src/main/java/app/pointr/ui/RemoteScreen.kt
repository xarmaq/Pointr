package app.pointr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pointr.AppViewModel
import app.pointr.R
import app.pointr.net.ConnState
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(vm: AppViewModel) {
    val client = vm.client
    val state by client.state.collectAsState()
    val settings by vm.prefs.settings.collectAsState()
    val volume by client.volume.collectAsState()
    val muted by client.muted.collectAsState()
    val blur by client.blur.collectAsState()
    val windows by client.windows.collectAsState()
    val screen by client.screen.collectAsState()
    val cursor by client.cursor.collectAsState()
    val screenDenied by client.screenDenied.collectAsState()
    val haptics = LocalHaptics.current
    val density = LocalDensity.current

    var laserMode by rememberSaveable { mutableStateOf(false) }
    var laserTouch by remember { mutableStateOf(false) }
    var dockOpen by rememberSaveable { mutableStateOf(false) }
    var screenOn by rememberSaveable { mutableStateOf(false) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var headerH by remember { mutableIntStateOf(0) }
    var footerH by remember { mutableIntStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible

    val layer = rememberGraphicsLayer()
    val glass = remember(layer) { Backdrop(layer) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(dockOpen, state is ConnState.Connected) {
        while (dockOpen && state is ConnState.Connected) { client.requestWindows(); delay(2500) }
    }
    // keep the stream in step with the toggle; it is also dropped when this screen goes away
    LaunchedEffect(screenOn) { client.setScreen(screenOn) }
    DisposableEffect(Unit) { onDispose { client.setScreen(false) } }

    GyroLaser(laserMode && laserTouch, settings.laserSensitivity) { dx, dy -> client.laserMove(dx, dy) }

    Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Trackpad(
            client = client,
            settings = settings,
            laserMode = laserMode,
            onLaserTouch = { on -> laserTouch = on; client.laser(on) },
            topInset = with(density) { headerH.toDp() },
            bottomInset = with(density) { footerH.toDp() },
            screen = screen,
            cursor = cursor,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { glass.origin = it.positionInRoot() }
                .drawWithContent {
                    // record the pad so the glass panes can redraw it blurred
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        )

        Column(
            Modifier.fillMaxWidth().onSizeChanged { headerH = it.height }.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Header(
                vm = vm,
                glass = glass,
                state = state,
                screenOn = screenOn,
                keyboardOpen = keyboardOpen,
                onSettings = { settingsOpen = true; haptics.click() },
                onScreen = { screenOn = !screenOn; haptics.click() },
                onKeyboard = { keyboardOpen = !keyboardOpen; haptics.click() },
            )
            Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnimatedVisibility(laserMode, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    ModeChip(stringResource(R.string.laser), P.Laser, Ix.LaserOn)
                }
                AnimatedVisibility(blur, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    ModeChip(stringResource(R.string.blurred), LocalAccent.current, Ix.MoonOn)
                }
                AnimatedVisibility(screenOn && screenDenied, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    ModeChip(stringResource(R.string.screen_denied), Color(0xFFFBBF24), Ix.Monitor)
                }
            }
        }

        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerH = it.height }.padding(8.dp)) {
            if (imeVisible) {
                GlassSurface(glass, RoundedCornerShape(22.dp)) {
                    KeyStrip(client, onHide = { keyboardOpen = false }, modifier = Modifier.padding(horizontal = 8.dp))
                }
            } else {
                GlassSurface(glass, RoundedCornerShape(34.dp)) {
                    RemotePanel(
                        client = client,
                        laserMode = laserMode,
                        onLaserMode = { laserMode = it },
                        blur = blur,
                        volume = volume,
                        muted = muted,
                        dockOpen = dockOpen,
                        onDockOpen = { dockOpen = it },
                        windows = windows,
                    )
                }
            }
        }
    }

    KeyboardBridge(keyboardOpen, onClosed = { keyboardOpen = false }, client)
    if (settingsOpen) SettingsSheet(vm, onDismiss = { settingsOpen = false })
}

@Composable
private fun Header(
    vm: AppViewModel,
    glass: Backdrop,
    state: ConnState,
    screenOn: Boolean,
    keyboardOpen: Boolean,
    onSettings: () -> Unit,
    onScreen: () -> Unit,
    onKeyboard: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val connected = state is ConnState.Connected
    val latency by vm.client.latency.collectAsState()
    val servers by vm.prefs.servers.collectAsState()
    val current = servers.firstOrNull { it.id == vm.client.serverId }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(1f, if (connected) 1f else 0.25f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    val dotColor by animateColorAsState(
        when {
            !connected -> Color(0xFFFBBF24)
            (latency ?: 0) > 120 -> Color(0xFFFF8A4C)
            else -> P.Online
        }, label = "dc",
    )

    GlassSurface(glass, CircleShape, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Ix.Settings, 44.dp, onSettings, contentDescription = stringResource(R.string.settings))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(CircleShape).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).alpha(dotAlpha).clip(CircleShape).background(dotColor))
                    Text(
                        when (state) {
                            is ConnState.Connected -> current?.name ?: state.name
                            is ConnState.Connecting -> stringResource(if (state.retry) R.string.reconnecting else R.string.connecting)
                            else -> stringResource(R.string.disconnected)
                        },
                        color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                    )
                    if (connected && latency != null) Text("$latency ms", color = P.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    IxIcon(Ix.Down, P.Muted, size = 16.dp)
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = P.Panel, shape = RoundedCornerShape(20.dp)) {
                    for (s in servers.filter { it.key != null }) {
                        val here = s.id == vm.client.serverId
                        DropdownMenuItem(
                            text = { Text(s.name, color = P.Text, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal) },
                            leadingIcon = { IxIcon(if (here) Ix.MonitorOn else Ix.Monitor, if (here) LocalAccent.current else P.Muted) },
                            trailingIcon = { if (here) IxIcon(Ix.Tick, LocalAccent.current, size = 18.dp) },
                            onClick = { menu = false; vm.connectKnown(s) },
                        )
                    }
                    HorizontalDivider(color = P.PadEdge, modifier = Modifier.padding(vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.switch_computer), color = P.Text) },
                        leadingIcon = { IxIcon(Ix.Add, P.Muted) },
                        onClick = { menu = false; vm.disconnect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.disconnect), color = P.Text) },
                        leadingIcon = { IxIcon(Ix.Logout, P.Muted) },
                        onClick = { menu = false; vm.disconnect() },
                    )
                }
            }
            RoundButton(Ix.Monitor, 44.dp, onScreen, active = screenOn, activeIcon = Ix.MonitorOn,
                contentDescription = stringResource(R.string.screen_view))
            Spacer(Modifier.width(6.dp))
            RoundButton(Ix.Keyboard, 44.dp, onKeyboard, active = keyboardOpen, activeIcon = Ix.KeyboardOn, contentDescription = "Keyboard")
        }
    }
}
