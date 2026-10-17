package io.openflux.app.cosmic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.openflux.app.components.ConnectLook
import io.openflux.app.theme.AppTheme
import io.openflux.desktop.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Home's switch: a small universe. Off, the Moon turns in the middle and phones, tablets, laptops and monitors
 * drift round it with their screens dark. Connecting, the Moon's crust cracks with fire and holds so; connected,
 * it burns into the Sun, the screens wake as its light reaches them, the devices settle into orbits and links of
 * light with traffic running along them join them to each other and to the Sun. A failure flares red. The body is
 * the button: [label] names what pressing it does, [shortcut] is shown beside it on a pointer screen.
 * [traffic] (0..1) is how busy the connection is: the comets run faster.
 */
@Composable
fun CosmicSwitch(
    look: ConnectLook,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    traffic: Float = 0.5f,
    shortcut: String? = null,
) {
    val universe = remember { Universe() }
    val renderer = remember { CosmicRenderer(sceneShadersAvailable()) }
    val camera = remember { Camera() }
    val canvas = remember { CanvasSize() }
    val reduced = rememberReducedMotion()
    val scene = LocalCosmicScene.current
    val images by produceState<CosmicImages?>(null, scene) { if (scene) value = loadCosmicImages() }
    val frame = remember { mutableIntStateOf(0) }
    var first by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(look) {
        if (first) {
            aimCamera(camera, canvas.size, universe.time, reduced)
            universe.jump(look, camera)
            first = false
        } else {
            universe.show(look, reduced)
        }
    }
    SideEffect { universe.traffic = traffic.coerceIn(0f, 1f) }
    LaunchedEffect(universe, scene) {
        if (!scene) return@LaunchedEffect
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                aimCamera(camera, canvas.size, universe.time, reduced)
                universe.update(dt, camera, reduced)
                frame.intValue++
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val hover by animateFloatAsState(if (hovered) 1f else 0f)
    val press by animateFloatAsState(if (pressed) 1f else 0f)
    val shape = RoundedCornerShape(AppTheme.shapes.large)

    BoxWithConstraints(modifier.clip(shape).border(1.dp(), AppTheme.colors.border.copy(alpha = 0.6f), shape)) {
        Canvas(Modifier.fillMaxSize()) {
            frame.intValue
            if (canvas.size != size) {
                canvas.size = size
                aimCamera(camera, size, universe.time, reduced)
            }
            with(renderer) { drawScene(universe, camera, images, hover, press) }
        }
        // The body is the button: a round target over it, its size from the same camera.
        val density = LocalDensity.current
        val probe = remember { Camera() }
        aimCamera(probe, with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }, 0f, true)
        val diameter = with(density) { (bodyRadiusPx(probe) * 2.2f).toDp() }
        val target = @Composable {
            Box(
                Modifier.size(diameter).clip(CircleShape)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label },
            )
        }
        Box(Modifier.align(Alignment.Center)) {
            if (shortcut == null || AppTheme.touch) target() else Hint("$label · $shortcut", target)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hint(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

private class CanvasSize {
    var size = Size.Zero
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

/** Reads the pictures from the app's resources; null if they cannot be read (the scene then draws without them). */
@OptIn(ExperimentalResourceApi::class)
suspend fun loadCosmicImages(): CosmicImages? = runCatching {
    CosmicImages(
        moon = Res.readBytes("files/cosmic/moon.jpg").decodeToImageBitmap(),
        height = Res.readBytes("files/cosmic/moon_height.jpg").decodeToImageBitmap(),
        sun = Res.readBytes("files/cosmic/sun.jpg").decodeToImageBitmap(),
    )
}.getOrNull()

/** Whether the person asked the system for less motion. */
@Composable
expect fun rememberReducedMotion(): Boolean
