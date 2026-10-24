package io.openflux.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas as PictureCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openflux.desktop.service.PlatformServices

/** A QR code of [text], black on white whatever the theme (scanners want it so), at most 320 dp wide. */
@Composable
fun QrView(text: String, platform: PlatformServices, modifier: Modifier = Modifier) {
    val matrix = remember(text) { platform.qrMatrix(text) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // One pixel a module, made once and drawn scaled without smoothing: a window redrawn for an animation
        // then draws one picture, not a rectangle for each of a thousand and more modules.
        val picture = remember(matrix) { qrPicture(matrix) }
        Canvas(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp)
                .semantics { contentDescription = "QR-код ссылки" },
        ) {
            if (picture == null) return@Canvas
            drawImage(picture, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
        }
    }
}

/** The QR's modules as a picture of one pixel each, black on white; null for an empty matrix. */
private fun qrPicture(matrix: List<BooleanArray>): ImageBitmap? {
    val n = matrix.size
    if (n == 0) return null
    val image = ImageBitmap(n, n)
    val canvas = PictureCanvas(image)
    val white = Paint().apply { color = Color.White }
    val black = Paint().apply { color = Color.Black; isAntiAlias = false }
    canvas.drawRect(0f, 0f, n.toFloat(), n.toFloat(), white)
    for (y in 0 until n) for (x in 0 until matrix[y].size) {
        if (matrix[y][x]) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, black)
    }
    return image
}
