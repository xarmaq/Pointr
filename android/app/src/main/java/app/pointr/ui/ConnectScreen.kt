package app.pointr.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pointr.AppViewModel
import app.pointr.R
import app.pointr.net.ConnState
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private data class ServerRow(val id: String, val name: String, val host: String, val port: Int, val paired: Boolean, val online: Boolean)

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val state by vm.client.state.collectAsState()
    val found by vm.found.collectAsState()
    val known by vm.prefs.servers.collectAsState()
    val haptics = LocalHaptics.current
    var manual by remember { mutableStateOf(false) }

    val rows = buildList {
        for (f in found) add(ServerRow(f.id, f.name, f.host, f.port, known.any { it.id == f.id && it.key != null }, true))
        for (k in known) if (k.key != null && found.none { it.id == k.id }) add(ServerRow(k.id, k.name, k.host, k.port, true, false))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        RadarLogo(Modifier.size(168.dp))
        Text("POINTR", color = P.Text, fontSize = 34.sp, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.find_hint), color = P.Muted, fontSize = 15.sp, lineHeight = 22.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(30.dp))

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.find_title), color = P.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            CircularProgressIndicator(Modifier.size(16.dp), color = P.Muted, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(14.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rows.isEmpty()) item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).border(1.dp, P.PadEdge, RoundedCornerShape(24.dp))
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IxIcon(Ix.Radar, P.Muted)
                    Text(stringResource(R.string.searching), color = P.Muted, fontSize = 15.sp)
                }
            }
            items(rows, key = { it.id }) { r ->
                val busy = (state as? ConnState.Connecting)?.name == r.name
                ServerCard(r, busy) {
                    haptics.click()
                    vm.connectTo(r.host, r.port, r.name)
                }
            }
            if (state is ConnState.Failed) item {
                Text(stringResource(R.string.error_connect, (state as ConnState.Failed).name), color = P.Laser,
                    fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }

        Row(
            Modifier
                .padding(vertical = 16.dp)
                .clip(CircleShape)
                .background(P.Panel)
                .border(1.dp, P.PadEdge, CircleShape)
                .clickable { manual = true }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IxIcon(Ix.Edit, LocalAccent.current, size = 18.dp)
            Text(stringResource(R.string.enter_ip), color = P.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (manual) ManualIpDialog(onDismiss = { manual = false }) { ip ->
        manual = false
        vm.connectTo(ip, 47801, ip)
    }
}

@Composable
private fun ServerCard(r: ServerRow, busy: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(P.Panel, Color.White, 0.02f), P.Panel)))
            .border(1.dp, if (busy) accent else P.PadEdge, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(if (r.online) accent.copy(alpha = 0.14f) else P.Button),
            contentAlignment = Alignment.Center,
        ) { IxIcon(if (r.online) Ix.MonitorOn else Ix.Monitor, if (r.online) accent else P.Muted, size = 24.dp) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.name, color = if (r.online) P.Text else P.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.host, color = P.Muted, fontSize = 13.sp)
                if (r.paired) Text(
                    stringResource(R.string.paired), color = P.Online, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(P.Online.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
        else IxIcon(Ix.Next, P.Muted, size = 20.dp)
    }
}

@Composable
private fun ManualIpDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var ip by remember { mutableStateOf("") }
    val valid = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = P.Panel,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.enter_ip), color = P.Text, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                ip, { ip = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.ip_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = LocalAccent.current, cursorColor = LocalAccent.current),
            )
        },
        confirmButton = {
            TextButton({ onConnect(ip.trim()) }, enabled = valid) {
                Text(stringResource(R.string.connect), color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel), color = P.Muted) } },
    )
}

/** The trackpad's dot field with a glow sweeping around: "looking for something". */
@Composable
fun RadarLogo(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val t by rememberInfiniteTransition(label = "radar").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(modifier) {
        val n = 9
        val step = size.minDimension / n
        val c = Offset(size.width / 2, size.height / 2)
        val spot = c + Offset(kotlin.math.cos(t) * step * 2.2f, sin(t) * step * 2.2f)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent), spot, step * 3.5f), step * 3.5f, spot)
        val idle = Color.White.copy(alpha = 0.10f)
        for (i in 0 until n) for (j in 0 until n) {
            val p = Offset((i + 0.5f) * step, (j + 0.5f) * step)
            if (hypot(p.x - c.x, p.y - c.y) > size.minDimension / 2) continue
            val d = hypot(p.x - spot.x, p.y - spot.y) / (step * 3f)
            val k = max(0f, 1 - d).let { it * it }
            drawCircle(lerp(idle, accent, k), step * (0.09f + 0.16f * k), p)
        }
        drawCircle(Color.White, step * 0.32f, c)
    }
}
