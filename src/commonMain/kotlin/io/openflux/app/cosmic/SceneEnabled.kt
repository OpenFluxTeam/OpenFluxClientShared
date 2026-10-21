package io.openflux.app.cosmic

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the switch animates its scene. The app always does; a test run turns it off (build.gradle.kts), so the
 * tests that merely pass through Home do not wait on the scene's frame loop and picture decoding, which a slow CI
 * runner cannot finish in time. [CosmicSwitchTest] provides it back on.
 */
val LocalCosmicScene = staticCompositionLocalOf { cosmicSceneDefault() }

expect fun cosmicSceneDefault(): Boolean
