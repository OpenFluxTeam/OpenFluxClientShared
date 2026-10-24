package io.openflux.app.components

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos

/**
 * Calls [frame] once a frame, at whatever rate the screen runs (Compose's frame clock knows it), with the seconds
 * since the last call: the Moon-and-Sun scene's clock.
 */
suspend fun everyFrame(frame: (dt: Float) -> Unit): Nothing {
    var last = 0L
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.15f)
            last = now
            frame(dt)
        }
    }
}
