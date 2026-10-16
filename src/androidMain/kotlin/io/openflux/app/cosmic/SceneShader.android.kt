package io.openflux.app.cosmic

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/** Created only where [sceneShadersAvailable] says so (Android 13 and later). */
actual class SceneShader actual constructor(source: String) {
    private val shader: RuntimeShader = create(source)
    private val images = HashMap<String, ImageBitmap>()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun create(source: String) = RuntimeShader(source)

    actual fun uniform(name: String, vararg values: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader.setFloatUniform(name, values)
    }

    actual fun image(name: String, image: ImageBitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || images[name] === image) return
        images[name] = image
        val input = BitmapShader(image.asAndroidBitmap(), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        input.filterMode = BitmapShader.FILTER_MODE_LINEAR
        shader.setInputShader(name, input)
    }

    actual fun brush(): Brush = ShaderBrush(shader)
}

actual fun sceneShadersAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
