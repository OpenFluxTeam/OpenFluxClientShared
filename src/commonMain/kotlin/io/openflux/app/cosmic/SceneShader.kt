package io.openflux.app.cosmic

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A pixel shader written once: SkSL on the desktop (skia RuntimeEffect), AGSL on Android 13 and later
 * (RuntimeShader). The two languages share what the scene uses: `uniform shader` inputs read with `eval`,
 * `half4 main(float2)`, float vectors and loops with constant bounds.
 */
expect class SceneShader(source: String) {
    fun uniform(name: String, vararg values: Float)

    /** An image the shader reads with `eval`, in pixels; it repeats across x and stops at the edges in y. */
    fun image(name: String, image: ImageBitmap)

    fun brush(): Brush
}

/** False on Android before 13: the scene then draws its bodies from plain images. */
expect fun sceneShadersAvailable(): Boolean
