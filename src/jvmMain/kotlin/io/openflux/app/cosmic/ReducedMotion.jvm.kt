package io.openflux.app.cosmic

import androidx.compose.runtime.Composable

/** Desktop systems give a JVM app no portable way to ask; the scene keeps its full motion. */
@Composable
actual fun rememberReducedMotion(): Boolean = false
