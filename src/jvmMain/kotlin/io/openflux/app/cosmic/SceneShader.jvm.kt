package io.openflux.app.cosmic

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

actual class SceneShader actual constructor(source: String) {
    private val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(source))
    private val images = HashMap<String, ImageBitmap>()

    actual fun uniform(name: String, vararg values: Float) {
        when (values.size) {
            1 -> builder.uniform(name, values[0])
            2 -> builder.uniform(name, values[0], values[1])
            3 -> builder.uniform(name, values[0], values[1], values[2])
            4 -> builder.uniform(name, values[0], values[1], values[2], values[3])
            else -> builder.uniform(name, values)
        }
    }

    actual fun image(name: String, image: ImageBitmap) {
        if (images[name] === image) return
        images[name] = image
        val shader = Image.makeFromBitmap(image.asSkiaBitmap()).makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
        builder.child(name, shader)
    }

    actual fun brush(): Brush = ShaderBrush(builder.makeShader(null).asComposeShader())
}

actual fun sceneShadersAvailable(): Boolean = true
