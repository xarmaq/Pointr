package app.pointr.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * What sits behind the glass: a recorded layer (the trackpad) and where it is on screen.
 * The trackpad records itself into [layer] every frame; glass panes redraw that recording
 * through a blur, so dots, glow and the PC picture show through, frosted.
 */
class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

/** Real frosted glass on Android 12+ (RenderEffect blur); a denser tint before that. */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blur = Build.VERSION.SDK_INT >= 31
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.clip(shape)) {
        if (blur) Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer { renderEffect = BlurEffect(24.dp.toPx(), 24.dp.toPx(), TileMode.Clamp) }
        ) {
            val o = backdrop.origin
            translate(o.x - origin.x, o.y - origin.y) { drawLayer(backdrop.layer) }
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        if (blur) listOf(Color.White.copy(alpha = 0.07f), P.Panel.copy(alpha = 0.55f))
                        else listOf(P.Panel.copy(alpha = 0.94f), P.Panel.copy(alpha = 0.97f))
                    )
                )
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), shape)
        )
        content()
    }
}
