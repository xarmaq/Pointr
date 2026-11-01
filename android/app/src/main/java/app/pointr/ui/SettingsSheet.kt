package app.pointr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pointr.AppViewModel
import app.pointr.BuildConfigCompat
import app.pointr.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by vm.prefs.settings.collectAsState()
    val servers by vm.prefs.servers.collectAsState()
    val accent = LocalAccent.current
    val haptics = LocalHaptics.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = P.Panel,
        contentColor = P.Text,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.settings), fontSize = 22.sp, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            SliderRow(stringResource(R.string.pointer_speed), s.pointerSpeed, 0.4f..3f) { v -> vm.prefs.update { it.copy(pointerSpeed = v) } }
            SliderRow(stringResource(R.string.acceleration), s.acceleration, 0f..1.5f) { v -> vm.prefs.update { it.copy(acceleration = v) } }
            SliderRow(stringResource(R.string.scroll_speed), s.scrollSpeed, 0.3f..3f) { v -> vm.prefs.update { it.copy(scrollSpeed = v) } }
            SliderRow(stringResource(R.string.laser_sensitivity), s.laserSensitivity, 0.3f..2.5f) { v -> vm.prefs.update { it.copy(laserSensitivity = v) } }

            SwitchRow(stringResource(R.string.natural_scroll), stringResource(R.string.natural_scroll_sub), s.naturalScroll) { v ->
                vm.prefs.update { it.copy(naturalScroll = v) }
            }
            SwitchRow(stringResource(R.string.haptics), null, s.haptics) { v -> vm.prefs.update { it.copy(haptics = v) } }
            SwitchRow(stringResource(R.string.volume_keys), stringResource(R.string.volume_keys_sub), s.volumeKeys) { v ->
                vm.prefs.update { it.copy(volumeKeys = v) }
            }

            Section(stringResource(R.string.accent))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                P.Accents.forEachIndexed { i, c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(c)
                            .border(if (i == s.accent) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { vm.prefs.update { it.copy(accent = i) }; haptics.tick() },
                        contentAlignment = Alignment.Center,
                    ) { if (i == s.accent) IxIcon(Ix.Tick, Color.White, size = 20.dp) }
                }
            }

            Section(stringResource(R.string.computers))
            for (srv in servers.filter { it.key != null }) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Button).padding(14.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IxIcon(if (srv.id == vm.client.serverId) Ix.MonitorOn else Ix.Monitor, if (srv.id == vm.client.serverId) accent else P.Muted)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(srv.name, fontWeight = FontWeight.SemiBold)
                        Text(srv.host, color = P.Muted, fontSize = 13.sp)
                    }
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(P.Laser.copy(alpha = 0.12f)).clickable { vm.forget(srv.id) },
                        contentAlignment = Alignment.Center,
                    ) { IxIcon(Ix.Trash, P.Laser, size = 18.dp, contentDescription = stringResource(R.string.forget)) }
                }
            }

            Section(stringResource(R.string.gestures_title))
            Text(stringResource(R.string.gestures), color = P.Muted, fontSize = 13.sp, lineHeight = 20.sp)

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.version, BuildConfigCompat.versionName(vm.getApplication())), color = P.Muted,
                fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = P.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val accent = LocalAccent.current
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("%.1f×".format(value), color = P.Text, fontSize = 13.sp, fontFamily = Unbounded)
        }
        Slider(
            value, onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = P.Button),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = P.Muted, fontSize = 13.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current, checkedThumbColor = Color.White,
                uncheckedTrackColor = P.Button, uncheckedBorderColor = P.ButtonEdge),
        )
    }
}
