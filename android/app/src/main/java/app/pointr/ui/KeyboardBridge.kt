package app.pointr.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardHide
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pointr.net.PointrClient

// The IME needs something to delete, or backspace on an "empty" field never reaches us.
private const val SENTINEL = "    "
private val Reset = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/**
 * An invisible text field that turns whatever the phone keyboard produces (typing, swipe,
 * autocorrect, voice dictation) into text and backspaces on the PC.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardBridge(open: Boolean, onClosed: () -> Unit, client: PointrClient) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var value by remember { mutableStateOf(Reset) }
    val imeVisible = WindowInsets.isImeVisible
    var seenIme by remember { mutableStateOf(false) }

    LaunchedEffect(open) {
        if (open) { value = Reset; focus.requestFocus(); keyboard?.show() }
        else { keyboard?.hide(); focusManager.clearFocus(); seenIme = false }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) seenIme = true
        else if (open && seenIme) onClosed() // user dismissed the keyboard with back
    }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            val old = value.text
            val nt = new.text
            var p = 0
            while (p < old.length && p < nt.length && old[p] == nt[p]) p++
            val deleted = old.length - p
            val inserted = nt.substring(p)
            if (deleted > 0) client.backspace(deleted)
            if (inserted.isNotEmpty()) client.text(inserted)
            value = if (nt.length < SENTINEL.length || (new.composition == null && nt.length > 48)) Reset else new
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
    )
}

/** Keys a phone keyboard lacks, shown just above it. */
@Composable
fun KeyStrip(client: PointrClient, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    @Composable
    fun key(label: String? = null, icon: Int? = null, action: () -> Unit) {
        Box(
            Modifier
                .height(40.dp)
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(P.Button, P.Panel)))
                .border(1.dp, P.ButtonEdge, RoundedCornerShape(12.dp))
                .clickable { action(); haptics.tick() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) IxIcon(icon, P.Text, size = 20.dp, contentDescription = label)
            else Text(label!!, color = P.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        key(icon = Ix.Down, action = onHide)
        key("Esc") { client.key("esc") }
        key("Tab") { client.key("tab") }
        key(icon = Ix.Prev) { client.key("left") }
        key(icon = Ix.Up) { client.key("up") }
        key(icon = Ix.Down) { client.key("down") }
        key(icon = Ix.Next) { client.key("right") }
        key("Del") { client.key("delete") }
        key("Win") { client.key("win") }
        key("Ctrl+A") { client.key("a", "ctrl") }
        key("Ctrl+C") { client.key("c", "ctrl") }
        key("Ctrl+V") { client.key("v", "ctrl") }
        key("Ctrl+Z") { client.key("z", "ctrl") }
    }
}
