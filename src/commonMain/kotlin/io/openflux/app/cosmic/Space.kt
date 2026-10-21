package io.openflux.app.cosmic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// The little 3D the scene needs: vectors, rotations, a camera that projects points to the canvas, and the
// homography that lets a flat Compose drawing lie on any face in perspective.

class V3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float) = apply { this.x = x; this.y = y; this.z = z }
    fun set(o: V3) = set(o.x, o.y, o.z)
    fun length() = sqrt(x * x + y * y + z * z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    override fun toString() = "($x, $y, $z)"
}

/** A rotation as three rows; applied as `row · v`. */
class M3(
    var a: Float = 1f, var b: Float = 0f, var c: Float = 0f,
    var d: Float = 0f, var e: Float = 1f, var f: Float = 0f,
    var g: Float = 0f, var h: Float = 0f, var i: Float = 1f,
) {
    fun apply(v: V3, out: V3 = V3()): V3 = out.set(a * v.x + b * v.y + c * v.z, d * v.x + e * v.y + f * v.z, g * v.x + h * v.y + i * v.z)

    fun times(o: M3, out: M3 = M3()): M3 {
        val na = a * o.a + b * o.d + c * o.g; val nb = a * o.b + b * o.e + c * o.h; val nc = a * o.c + b * o.f + c * o.i
        val nd = d * o.a + e * o.d + f * o.g; val ne = d * o.b + e * o.e + f * o.h; val nf = d * o.c + e * o.f + f * o.i
        val ng = g * o.a + h * o.d + i * o.g; val nh = g * o.b + h * o.e + i * o.h; val ni = g * o.c + h * o.f + i * o.i
        out.a = na; out.b = nb; out.c = nc; out.d = nd; out.e = ne; out.f = nf; out.g = ng; out.h = nh; out.i = ni
        return out
    }

    fun transpose(out: M3 = M3()): M3 {
        val na = a; val nb = d; val nc = g; val nd = b; val ne = e; val nf = h; val ng = c; val nh = f; val ni = i
        out.a = na; out.b = nb; out.c = nc; out.d = nd; out.e = ne; out.f = nf; out.g = ng; out.h = nh; out.i = ni
        return out
    }

    companion object {
        fun rx(t: Float) = M3(1f, 0f, 0f, 0f, cos(t), -sin(t), 0f, sin(t), cos(t))
        fun ry(t: Float) = M3(cos(t), 0f, sin(t), 0f, 1f, 0f, -sin(t), 0f, cos(t))
        fun rz(t: Float) = M3(cos(t), -sin(t), 0f, sin(t), cos(t), 0f, 0f, 0f, 1f)
        /** x, then y, then z: the order three.js uses for Euler angles "XYZ". */
        fun euler(x: Float, y: Float, z: Float, out: M3 = M3()): M3 = rx(x).times(ry(y), out).times(rz(z), out)
    }
}

/**
 * A pinhole camera looking at the origin. [project] puts a world point on the canvas and gives its distance
 * from the eye; the scale of one world unit at the origin is [unit] pixels.
 */
class Camera {
    val eye = V3()
    private val right = V3()
    private val up = V3()
    private val back = V3()
    var focal = 1f
    var cx = 0f
    var cy = 0f

    /** Places the eye and aims it at the origin; [focalPx] is the focal length in pixels. */
    fun aim(x: Float, y: Float, z: Float, focalPx: Float, width: Float, height: Float) {
        eye.set(x, y, z)
        val len = eye.length()
        back.set(x / len, y / len, z / len)
        // right = worldUp × back, up = back × right
        right.set(back.z, 0f, -back.x)
        val rl = right.length()
        right.set(right.x / rl, right.y / rl, right.z / rl)
        up.set(back.y * right.z - back.z * right.y, back.z * right.x - back.x * right.z, back.x * right.y - back.y * right.x)
        focal = focalPx
        cx = width / 2
        cy = height / 2
    }

    /** Camera-space depth of a world point: larger is farther. */
    fun depth(p: V3): Float = -((p.x - eye.x) * back.x + (p.y - eye.y) * back.y + (p.z - eye.z) * back.z)

    fun project(p: V3): Offset {
        val dx = p.x - eye.x
        val dy = p.y - eye.y
        val dz = p.z - eye.z
        val xc = dx * right.x + dy * right.y + dz * right.z
        val yc = dx * up.x + dy * up.y + dz * up.z
        val zc = -(dx * back.x + dy * back.y + dz * back.z)
        val s = focal / zc.coerceAtLeast(0.05f)
        return Offset(cx + xc * s, cy - yc * s)
    }

    /** The scale at the origin: pixels per world unit there. */
    val unit: Float get() = focal / eye.length()

    /** Whether the eye sees the front of a face whose outward normal is [n] at point [p]. */
    fun faces(p: V3, n: V3): Boolean = (eye.x - p.x) * n.x + (eye.y - p.y) * n.y + (eye.z - p.z) * n.z > 0f

    /** The rotation from world to camera axes (rows: right, up, back): the body shader works in view space. */
    fun viewRows(out: M3 = M3()): M3 {
        out.a = right.x; out.b = right.y; out.c = right.z
        out.d = up.x; out.e = up.y; out.f = up.z
        out.g = back.x; out.h = back.y; out.i = back.z
        return out
    }
}

/**
 * The projective map taking the unit square (0,0)-(1,0)-(1,1)-(0,1) to the quad [p0]..[p3] on the canvas, as a
 * Compose matrix: draw anything in the unit square under it and it lies on the quad in perspective.
 */
fun squareToQuad(p0: Offset, p1: Offset, p2: Offset, p3: Offset, out: Matrix = Matrix()): Matrix {
    val sx = p0.x - p1.x + p2.x - p3.x
    val sy = p0.y - p1.y + p2.y - p3.y
    val a: Float; val b: Float; val c: Float; val d: Float; val e: Float; val f: Float; val g: Float; val h: Float
    if (kotlin.math.abs(sx) < 1e-4f && kotlin.math.abs(sy) < 1e-4f) {
        a = p1.x - p0.x; b = p3.x - p0.x; c = p0.x
        d = p1.y - p0.y; e = p3.y - p0.y; f = p0.y
        g = 0f; h = 0f
    } else {
        val dx1 = p1.x - p2.x; val dx2 = p3.x - p2.x
        val dy1 = p1.y - p2.y; val dy2 = p3.y - p2.y
        val den = dx1 * dy2 - dx2 * dy1
        g = (sx * dy2 - dx2 * sy) / den
        h = (dx1 * sy - sx * dy1) / den
        a = p1.x - p0.x + g * p1.x; b = p3.x - p0.x + h * p3.x; c = p0.x
        d = p1.y - p0.y + g * p1.y; e = p3.y - p0.y + h * p3.y; f = p0.y
    }
    val v = out.values
    for (k in 0 until 16) v[k] = 0f
    // Column-major 4x4: x' = (a x + b y + c) / (g x + h y + 1), likewise y'.
    v[0] = a; v[4] = b; v[12] = c
    v[1] = d; v[5] = e; v[13] = f
    v[3] = g; v[7] = h; v[15] = 1f
    v[10] = 1f
    return out
}

/** The same random numbers every time, so the universe looks the same on every launch. */
class Rng(seed: Int) {
    private var s = seed.toLong() and 0xffffffffL
    fun next(): Float {
        s = (s + 0x6d2b79f5L) and 0xffffffffL
        var t = s
        t = ((t xor (t ushr 15)) * (t or 1)) and 0xffffffffL
        t = (t xor (t + (((t xor (t ushr 7)) * (t or 61)) and 0xffffffffL))) and 0xffffffffL
        return (((t xor (t ushr 14)) and 0xffffffffL).toDouble() / 4294967296.0).toFloat()
    }
}

/** A value easing to a target after a delay; asked again mid-way it starts from where it is. */
class Ramp(initial: Float = 0f) {
    var value = initial
        private set
    private var from = initial
    var target = initial
        private set
    private var start = 0f
    private var duration = 0f
    private var ease: (Float) -> Float = ::easeInOut

    fun to(target: Float, now: Float, delay: Float = 0f, duration: Float = 1f, ease: (Float) -> Float = ::easeInOut) {
        from = value
        this.target = target
        start = now + delay
        this.duration = duration.coerceAtLeast(0.0001f)
        this.ease = ease
    }

    fun snap(v: Float) {
        value = v; from = v; target = v; duration = 0f
    }

    fun update(now: Float): Float {
        if (now <= start) return value
        val t = ((now - start) / duration).coerceIn(0f, 1f)
        value = from + (target - from) * ease(t)
        return value
    }
}

fun easeInOut(t: Float): Float = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
fun easeOut(t: Float): Float = 1 - (1 - t).let { it * it * it }
fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
