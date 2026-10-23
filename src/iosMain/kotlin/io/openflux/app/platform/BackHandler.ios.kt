package io.openflux.app.platform

import androidx.compose.runtime.Composable

/** iOS has no system back button; the navigation's own swipe-back covers it. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
