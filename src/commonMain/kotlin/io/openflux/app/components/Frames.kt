package io.openflux.app.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay

/** How often the app's endless animations (Home's switch, the node's button) move: plenty for them, a fraction of a fast screen. */
const val ANIMATION_FPS = 30

/** The same while the window is behind others or minimised. */
const val BACKGROUND_FPS = 10

/** Whether the window is in front, for [throttledFrames]. */
@Composable
fun rememberWindowFocused(): State<Boolean> = rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)

/**
 * Calls [frame] inside a frame with the seconds since the last call, [ANIMATION_FPS] times a second, or
 * [BACKGROUND_FPS] while [focused] is false. Between frames it sleeps rather than wait for the next one: a frame
 * asked for repaints the whole window on the desktop, so waiting on every one would draw it at the screen's rate.
 */
suspend fun throttledFrames(focused: State<Boolean>, frame: (dt: Float) -> Unit): Nothing {
    var last = 0L
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.15f)
            last = now
            frame(dt)
        }
        // Less a few milliseconds: the wait for the screen's next frame comes on top.
        delay(1000L / (if (focused.value) ANIMATION_FPS else BACKGROUND_FPS) - 4)
    }
}
