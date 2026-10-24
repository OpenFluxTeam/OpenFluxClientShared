package io.openflux.app.cosmic

import android.os.Build
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

private val trianglePaint = android.graphics.Paint()
private val fallbackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private val fallbackPath = android.graphics.Path()

actual fun DrawScope.drawTriangles(positions: FloatArray, colors: IntArray, vertexCount: Int, indices: ShortArray, indexCount: Int) {
    val canvas = drawContext.canvas.nativeCanvas
    if (Build.VERSION.SDK_INT >= 29 || !canvas.isHardwareAccelerated) {
        // Android reads its part of each array by count; with no shader the paint shows the vertex colours.
        canvas.drawVertices(
            android.graphics.Canvas.VertexMode.TRIANGLES, vertexCount * 2, positions, 0, null, 0, colors, 0, indices, 0, indexCount, trianglePaint,
        )
        return
    }
    // Before Android 10 a hardware canvas skips drawVertices: each triangle as a path, in half its brightest colour.
    var i = 0
    while (i < indexCount) {
        val a = indices[i].toInt(); val b = indices[i + 1].toInt(); val c = indices[i + 2].toInt()
        val argb = maxOf(colors[a], colors[b], colors[c], compareBy { it ushr 24 })
        fallbackPaint.color = ((argb ushr 25) shl 24) or (argb and 0xFFFFFF)
        fallbackPath.rewind()
        fallbackPath.moveTo(positions[a * 2], positions[a * 2 + 1])
        fallbackPath.lineTo(positions[b * 2], positions[b * 2 + 1])
        fallbackPath.lineTo(positions[c * 2], positions[c * 2 + 1])
        fallbackPath.close()
        canvas.drawPath(fallbackPath, fallbackPaint)
        i += 3
    }
}
