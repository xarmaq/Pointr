package app.pointr

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import app.pointr.net.ConnState
import app.pointr.ui.ConnectScreen
import app.pointr.ui.Haptics
import app.pointr.ui.LocalAccent
import app.pointr.ui.LocalHaptics
import app.pointr.ui.P
import app.pointr.ui.PinScreen
import app.pointr.ui.PointrTheme
import app.pointr.ui.RemoteScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.prefs.settings.collectAsState()
            val state by vm.client.state.collectAsState()
            val view = LocalView.current
            val hapticsOn by rememberUpdatedState(settings.haptics)
            val haptics = remember(view) { Haptics(view) { hapticsOn } }

            PointrTheme {
                CompositionLocalProvider(
                    LocalAccent provides P.Accents[settings.accent.coerceIn(P.Accents.indices)],
                    LocalHaptics provides haptics,
                ) {
                    val screen = when {
                        state is ConnState.NeedPin -> "pin"
                        state is ConnState.Connected -> "remote"
                        state is ConnState.Connecting && vm.hadSession -> "remote"
                        else -> "connect"
                    }
                    Box(Modifier.fillMaxSize().background(P.Bg).systemBarsPadding().imePadding()) {
                        AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
                            when (s) {
                                "remote" -> RemoteScreen(vm)
                                "pin" -> {
                                    val pin = state as? ConnState.NeedPin
                                    BackHandler { vm.disconnect() }
                                    PinScreen(
                                        pcName = pin?.name ?: "",
                                        wrong = pin?.wrong ?: false,
                                        attempt = pin?.attempt ?: 0,
                                        onSubmit = vm.client::submitPin,
                                        onCancel = vm::disconnect,
                                    )
                                }
                                else -> ConnectScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Volume rockers act as a clicker while a PC is connected, so slides change without looking. */
    // Public Activity callback; the inherited AndroidX implementation carries a library restriction.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            vm.prefs.settings.value.volumeKeys && vm.client.state.value is ConnState.Connected
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                vm.client.key(if (code == KeyEvent.KEYCODE_VOLUME_UP) "right" else "left")
                if (vm.prefs.settings.value.haptics) window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        vm.resume()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.pause()
    }
}
