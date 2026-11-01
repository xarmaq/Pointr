package app.pointr.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.pointr.R
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object P {
    val Bg = Color(0xFF06080B)
    val Pad = Color(0xFF0E1218)
    val PadEdge = Color(0xFF1C222C)
    val Panel = Color(0xFF11161E)
    val Button = Color(0xFF171C25)
    val ButtonEdge = Color(0xFF272E3A)
    val Text = Color(0xFFF2F5FA)
    val Muted = Color(0xFF8A93A3)
    val Online = Color(0xFF34D399)
    val Laser = Color(0xFFFF3B4A)

    val Accents = listOf(
        Color(0xFF4C8DFF), // blue
        Color(0xFFA78BFA), // violet
        Color(0xFF22D3EE), // cyan
        Color(0xFF34D399), // green
        Color(0xFFFBBF24), // amber
        Color(0xFFFF6B8B), // pink
    )
}

val LocalAccent = staticCompositionLocalOf { P.Accents[0] }

/**
 * Haptics that respect the user's switch. Where the vibrator supports composition
 * primitives (Android 11+/12+) the trackpad gets distinct textures: a soft contact when a
 * finger lands, a crisp click, a deep thud for press-and-hold, fine detents on the wheel.
 */
class Haptics(private val view: View, private val enabled: () -> Boolean) {
    private val vibrator: Vibrator? = view.context.getSystemService(Vibrator::class.java)

    // Primitive IDs are forwarded only from the Composition constants below.
    @SuppressLint("WrongConstant")
    private fun supported(vararg p: Int) =
        Build.VERSION.SDK_INT >= 30 && vibrator?.hasVibrator() == true && vibrator.areAllPrimitivesSupported(*p)

    private fun compose(vararg steps: Pair<Int, Float>): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !supported(*steps.map { it.first }.toIntArray())) return false
        val c = VibrationEffect.startComposition()
        steps.forEach { (prim, scale) -> c.addPrimitive(prim, scale) }
        vibrator!!.vibrate(c.compose())
        return true
    }

    private val lowTick get() = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
    private val thud get() = if (Build.VERSION.SDK_INT >= 31) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK

    /** Finger lands on the pad: the surface gives a little. */
    fun press() { if (enabled() && !compose(lowTick to 0.55f)) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    /** The surface clicks through, like a real trackpad switch. */
    fun click() { if (enabled() && !compose(VibrationEffect.Composition.PRIMITIVE_CLICK to 1f)) perform(HapticFeedbackConstants.CONTEXT_CLICK) }

    /** Pressed deep (press-and-hold armed). */
    fun deep() { if (enabled() && !compose(thud to 1f, VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f)) perform(HapticFeedbackConstants.LONG_PRESS) }

    /** Surface springs back after a drag. */
    fun release() { if (enabled() && !compose(VibrationEffect.Composition.PRIMITIVE_TICK to 0.45f)) perform(HapticFeedbackConstants.CLOCK_TICK) }

    /** One notch of the scroll wheel; [strength] 0..1. */
    fun detent(strength: Float = 0.6f) { if (enabled() && !compose(lowTick to strength.coerceIn(0.2f, 1f))) perform(HapticFeedbackConstants.CLOCK_TICK) }

    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun heavy() = deep()
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK)
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(type: Int) {
        if (enabled()) view.performHapticFeedback(type)
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { error("no haptics") }

/** Wide techno grotesque for the brand, headings and big numerals. */
@OptIn(ExperimentalTextApi::class)
val Unbounded = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.unbounded, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/** Clean UI text face with proper Cyrillic. */
@OptIn(ExperimentalTextApi::class)
val Onest = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.onest, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private fun pointrTypography(): Typography {
    val t = Typography()
    fun TextStyle.ui() = copy(fontFamily = Onest)
    fun TextStyle.display() = copy(fontFamily = Unbounded, letterSpacing = (-0.2).sp)
    return Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(),
        displaySmall = t.displaySmall.display(), headlineLarge = t.headlineLarge.display(),
        headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.ui(), titleMedium = t.titleMedium.ui(), titleSmall = t.titleSmall.ui(),
        bodyLarge = t.bodyLarge.ui(), bodyMedium = t.bodyMedium.ui(), bodySmall = t.bodySmall.ui(),
        labelLarge = t.labelLarge.ui(), labelMedium = t.labelMedium.ui(), labelSmall = t.labelSmall.ui(),
    )
}

/** Iconsax, Linear at rest and Bold when the thing it stands for is switched on. */
object Ix {
    val Settings = R.drawable.ix_setting_2_linear
    val Keyboard = R.drawable.ix_keyboard_linear
    val KeyboardOn = R.drawable.ix_keyboard_bold
    val Laser = R.drawable.ix_flash_linear
    val LaserOn = R.drawable.ix_flash_bold
    val Volume = R.drawable.ix_volume_high_linear
    val VolumeOn = R.drawable.ix_volume_high_bold
    val Mute = R.drawable.ix_volume_slash_linear
    val MuteOn = R.drawable.ix_volume_slash_bold
    val Moon = R.drawable.ix_moon_linear
    val MoonOn = R.drawable.ix_moon_bold
    val Prev = R.drawable.ix_arrow_left_2_linear
    val Next = R.drawable.ix_arrow_right_2_linear
    val Down = R.drawable.ix_arrow_down_2_linear
    val Up = R.drawable.ix_arrow_up_2_linear
    val Monitor = R.drawable.ix_monitor_linear
    val MonitorOn = R.drawable.ix_monitor_bold
    val Wifi = R.drawable.ix_wifi_linear
    val Edit = R.drawable.ix_edit_2_linear
    val Add = R.drawable.ix_add_linear
    val Logout = R.drawable.ix_logout_linear
    val Tick = R.drawable.ix_tick_circle_bold
    val Trash = R.drawable.ix_trash_linear
    val Backspace = R.drawable.ix_tag_cross_linear
    val Windows = R.drawable.ix_element_3_linear
    val Radar = R.drawable.ix_radar_2_linear
    val Mouse = R.drawable.ix_mouse_linear
}

@Composable
fun IxIcon(@DrawableRes res: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, contentDescription: String? = null) {
    Icon(painterResource(res), contentDescription, tint = tint, modifier = modifier.size(size))
}

@Composable
fun PointrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = pointrTypography(),
        colorScheme = darkColorScheme(
            background = P.Bg, surface = P.Pad, onSurface = P.Text, onBackground = P.Text,
            primary = P.Accents[0], surfaceContainerLow = P.Pad, surfaceContainer = P.Button,
        ),
        content = content,
    )
}
