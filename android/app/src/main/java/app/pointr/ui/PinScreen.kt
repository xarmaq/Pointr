package app.pointr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pointr.R
import kotlin.math.roundToInt

@Composable
fun PinScreen(pcName: String, wrong: Boolean, attempt: Int, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val haptics = LocalHaptics.current
    val accent = LocalAccent.current
    var pin by remember { mutableStateOf("") }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(attempt) {
        if (!wrong) return@LaunchedEffect
        pin = ""
        haptics.reject()
        shake.animateTo(0f, keyframes {
            durationMillis = 360
            -18f at 50; 16f at 110; -12f at 170; 8f at 230; -4f at 290
        })
    }

    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d
        haptics.tick()
        if (pin.length == 4) onSubmit(pin)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text(stringResource(R.string.pin_title), color = P.Text, fontSize = 26.sp, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.pin_hint, pcName), color = P.Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))

        Row(
            Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(4) { i ->
                val filled = i < pin.length
                val current = i == pin.length
                Box(
                    Modifier
                        .size(62.dp, 78.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (filled) P.Panel else P.Pad)
                        .border(if (current) 2.dp else 1.dp, if (current) accent else P.PadEdge, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) Text(pin[i].toString(), color = P.Text, fontSize = 30.sp, fontFamily = Unbounded, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(if (wrong) stringResource(R.string.pin_wrong) else "", color = P.Laser, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))

        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .then(if (k.isEmpty()) Modifier else Modifier.background(Brush.verticalGradient(listOf(P.Button, P.Panel))).clickable {
                                    if (k == "⌫") { pin = pin.dropLast(1); haptics.tick() } else press(k)
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "⌫" -> IxIcon(Ix.Backspace, P.Text, size = 26.dp)
                                "" -> Unit
                                else -> Text(k, color = P.Text, fontSize = 26.sp, fontFamily = Unbounded, fontWeight = FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.cancel), color = P.Muted, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onCancel).padding(16.dp, 10.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}
