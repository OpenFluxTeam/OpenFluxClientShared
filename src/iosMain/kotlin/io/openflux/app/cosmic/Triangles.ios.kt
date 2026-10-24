package io.openflux.app.cosmic

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

private val trianglePaint = Paint()

actual fun DrawScope.drawTriangles(positions: FloatArray, colors: IntArray, vertexCount: Int, indices: ShortArray, indexCount: Int) {
    // Skia takes whole arrays: the batch's own part of each. Dst: the vertex colours as they are (there is no shader).
    drawContext.canvas.nativeCanvas.drawVertices(
        VertexMode.TRIANGLES,
        positions.copyOf(vertexCount * 2),
        colors.copyOf(vertexCount),
        null,
        indices.copyOf(indexCount),
        BlendMode.DST,
        trianglePaint,
    )
}
