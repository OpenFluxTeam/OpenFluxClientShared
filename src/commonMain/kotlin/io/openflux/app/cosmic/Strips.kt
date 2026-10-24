package io.openflux.app.cosmic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sqrt

/** Points on the canvas kept as two float arrays: setting one boxes nothing, unlike an `Array<Offset>`. */
class Points(val size: Int) {
    val x = FloatArray(size)
    val y = FloatArray(size)
    val indices: IntRange get() = 0 until size

    operator fun set(i: Int, p: Offset) { x[i] = p.x; y[i] = p.y }
    operator fun get(i: Int): Offset = Offset(x[i], y[i])
}

/** Draws [vertexCount] coloured vertices ([positions] x, y; [colors] ARGB) as the triangles [indices] name. */
expect fun DrawScope.drawTriangles(positions: FloatArray, colors: IntArray, vertexCount: Int, indices: ShortArray, indexCount: Int)

/**
 * Lines that change every frame, as ready triangles: a strip of three rows along each line, clear at its edges
 * and [Color] along its middle, so it is soft-edged without antialiasing. A stroked path that moves is cut into
 * triangles again every frame (and on the desktop drawn through a mask), which is most of what the scene's links,
 * orbits and screen waves cost the graphics card; a batch of strips is one draw. The batch lives in plain arrays
 * that grow once and are reused, so a frame allocates nothing.
 */
class Strips {
    private var positions = FloatArray(4096)
    private var colors = IntArray(2048)
    private var indices = ShortArray(6144)
    private var vertices = 0
    private var count = 0

    val isEmpty: Boolean get() = count == 0

    private fun vertex(x: Float, y: Float, argb: Int) {
        if (vertices * 2 + 2 > positions.size) {
            positions = positions.copyOf(positions.size * 2)
            colors = colors.copyOf(colors.size * 2)
        }
        positions[vertices * 2] = x
        positions[vertices * 2 + 1] = y
        colors[vertices] = argb
        vertices++
    }

    private fun triangle(a: Int, b: Int, c: Int) {
        if (count + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[count++] = a.toShort(); indices[count++] = b.toShort(); indices[count++] = c.toShort()
    }

    /** A line through the first [n] of [points], [half] wide each side of its middle. */
    fun line(points: Points, n: Int, half: Float, color: Color) {
        if (n < 2) return
        // Under a pixel each side the soft edge leaves gaps: a pixel wide at least, as much fainter as it is thinner.
        if (half < 1f) return line(points, n, 1f, color.copy(alpha = color.alpha * sqrt(half.coerceAtLeast(0.1f))))
        // Indices are shorts: start a new batch rather than run past them.
        if (vertices + n * 3 > Short.MAX_VALUE) return
        val solid = color.toArgb()
        val clear = solid and 0x00FFFFFF
        val first = vertices
        val xs = points.x
        val ys = points.y
        for (i in 0 until n) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == n - 1) n - 1 else i + 1
            var nx = -(ys[b] - ys[a])
            var ny = xs[b] - xs[a]
            val l = sqrt(nx * nx + ny * ny)
            if (l < 1e-4f) { nx = 0f; ny = 0f } else { nx = nx / l * half; ny = ny / l * half }
            vertex(xs[i] + nx, ys[i] + ny, clear)
            vertex(xs[i], ys[i], solid)
            vertex(xs[i] - nx, ys[i] - ny, clear)
        }
        for (i in 0 until n - 1) {
            val o = first + i * 3
            for (k in 0..1) {
                val a = o + k; val b = o + k + 1; val c = o + 3 + k; val d = o + 3 + k + 1
                triangle(a, b, c)
                triangle(b, d, c)
            }
        }
    }

    /** A solid four-cornered shape, corners in order round it: small and dark enough not to want soft edges. */
    fun quad(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
        if (vertices + 4 > Short.MAX_VALUE) return
        val argb = color.toArgb()
        val o = vertices
        vertex(a.x, a.y, argb); vertex(b.x, b.y, argb); vertex(c.x, c.y, argb); vertex(d.x, d.y, argb)
        triangle(o, o + 1, o + 2)
        triangle(o, o + 2, o + 3)
    }

    private val pair = Points(2)

    /** A short straight line from [a] to [b]: a dash. */
    fun dash(a: Offset, b: Offset, half: Float, color: Color) {
        pair[0] = a; pair[1] = b
        line(pair, 2, half, color)
    }

    /** Draws everything gathered, in one call, and starts again. */
    fun flush(scope: DrawScope) {
        if (count == 0) return
        scope.drawTriangles(positions, colors, vertices, indices, count)
        vertices = 0
        count = 0
    }
}
