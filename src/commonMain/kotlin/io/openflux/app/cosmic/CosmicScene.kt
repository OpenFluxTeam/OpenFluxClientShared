package io.openflux.app.cosmic

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The scene's pictures: the Moon's colour and relief (NASA LRO / LOLA) and a patch of the Sun's surface (NASA SDO). */
class CosmicImages(val moon: ImageBitmap, val height: ImageBitmap, val sun: ImageBitmap)

/** How far the camera stands and how much of the world the frame must hold. */
private const val EYE_DISTANCE = 17.2f
private const val HALF_WIDE = 5.9f
private const val HALF_TALL = 4.7f

/** Aims [camera] at the body for a canvas of [size]: the orbits fill it whatever its proportions. */
fun aimCamera(camera: Camera, size: Size, t: Float, reduced: Boolean) {
    if (size.width <= 0f || size.height <= 0f) return
    val focal = min(size.width / 2 * EYE_DISTANCE / HALF_WIDE, size.height / 2 * EYE_DISTANCE / HALF_TALL)
    val drift = if (reduced) 0f else sin(t * 0.07f) * 0.25f
    camera.aim(0f, 1.5f, EYE_DISTANCE + drift, focal, size.width, size.height)
}

/** The body's radius on the canvas for [camera]. */
fun bodyRadiusPx(camera: Camera): Float = BODY_RADIUS * camera.unit

/**
 * Draws a [Universe] as the camera sees it, back to front, straight on the page: the far halves of the orbits,
 * the devices behind the body, its corona, the body, the ring of light, the near halves of the orbits, the links and their
 * traffic, the devices in front.
 */
class CosmicRenderer(shaders: Boolean) {
    private val body: SceneShader?
    private val corona: SceneShader?

    init {
        var b: SceneShader? = null
        var c: SceneShader? = null
        if (shaders) {
            runCatching {
                b = SceneShader(BODY_SHADER)
                c = SceneShader(CORONA_SHADER)
            }.onFailure { println("[cosmic] shaders unavailable, drawing from images: ${it.message}") }
        }
        body = b
        corona = c
    }

    private val tmp = V3()
    private val tmp2 = V3()
    private val corners = Array(4) { V3() }
    private val projected = Array(4) { Offset.Zero }
    private val matrix = Matrix()
    private val rows = M3()
    private val bodyFrame = M3()

    fun DrawScope.drawScene(u: Universe, cam: Camera, images: CosmicImages?, hover: Float, press: Float) {
        val h = u.heat.value
        val dp = density
        val bodyDepth = cam.depth(tmp.set(0f, 0f, 0f))
        orbits(u, cam, bodyDepth, behind = true)
        val sorted = u.devices.sortedByDescending { it.depth }
        for (d in sorted) if (d.depth > bodyDepth) device(d, u, cam, h)
        val radius = bodyRadiusPx(cam) * (1f + 0.035f * hover) * (1f - 0.04f * press)
        val center = cam.project(tmp.set(0f, 0f, 0f))
        coronaLayer(u, center, radius, h, hover)
        bodyLayer(u, cam, center, radius, h, hover, images)
        shock(u, center, radius, dp)
        orbits(u, cam, bodyDepth, behind = false)
        links(u, cam, dp)
        for (d in sorted) if (d.depth <= bodyDepth) device(d, u, cam, h)
    }

    // ---- The orbits, faint, with light running along them ----

    private val ringPoints = Points(121)
    private val ringBehind = BooleanArray(121)
    private val strips = Strips()
    private val wavePoints = Points(25)
    private val linePoints = Points(27)

    private fun DrawScope.orbits(u: Universe, cam: Camera, bodyDepth: Float, behind: Boolean) {
        val show = u.devices.sumOf { it.order.value.toDouble() }.toFloat() / u.devices.size
        if (show < 0.02f) return
        val n = 120
        val dp = density
        val dash = 2f * dp
        val period = 9f * dp
        // The dashes run forward 14 dp a second; walked along the ring by hand, all drawn as one batch of lines.
        val phase = ((u.time * 14f * dp) % period + period) % period
        // Soft-edged, so a little brighter in the middle than the 1 dp line it replaces.
        val dashColor = Color(0.45f, 0.78f, 1f, 0.3f * show)
        for (ring in u.rings) {
            for (k in 0..n) {
                val a = k.toFloat() / n * 2 * PI.toFloat()
                tmp.set(cos(a) * ring.radius, 0f, sin(a) * ring.radius)
                ring.tilt.apply(tmp, tmp2)
                ringBehind[k] = cam.depth(tmp2) > bodyDepth
                ringPoints[k] = cam.project(tmp2)
            }
            var along = -phase
            for (k in 0 until n) {
                val a = ringPoints[k]
                val b = ringPoints[k + 1]
                val len = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
                if (ringBehind[k] == behind && ringBehind[k + 1] == behind && len > 0f) {
                    // Dash starts inside this segment: from the first period mark at or after `along`.
                    var start = ceilTo(along, period)
                    while (start < along + len) {
                        val s0 = ((start - along) / len).coerceIn(0f, 1f)
                        val s1 = ((start + dash - along) / len).coerceIn(0f, 1f)
                        strips.dash(Offset(a.x + (b.x - a.x) * s0, a.y + (b.y - a.y) * s0), Offset(a.x + (b.x - a.x) * s1, a.y + (b.y - a.y) * s1), 1.2f * dp, dashColor)
                        start += period
                    }
                }
                along += len
            }
        }
        strips.flush(this)
    }

    private fun ceilTo(x: Float, step: Float): Float = kotlin.math.ceil(x / step) * step

    // ---- The body ----

    private fun DrawScope.coronaLayer(u: Universe, center: Offset, radius: Float, h: Float, hover: Float) {
        // The Moon's halo is gone by 2.6 radii, the Sun's corona by 3: the shader runs on that disc only (past 3 it
        // was a percent of light over two fifths of the disc's area, worked out every frame).
        val span = radius * (if (h > 0.01f || u.red.value > 0f) 3f else 2.6f)
        val shader = corona
        if (shader != null) {
            shader.uniform("uCenter", center.x, center.y)
            shader.uniform("uRadius", radius)
            shader.uniform("uTime", u.time)
            shader.uniform("uHeat", h)
            shader.uniform("uHover", hover)
            shader.uniform("uRed", u.red.value)
            val l = u.loops
            fun life(i: Int): Float {
                val age = (u.time - l[i].born) / l[i].life
                return if (age < 0f || age > 1f) 0f else sin(PI.toFloat() * age) * h
            }
            fun height(i: Int): Float = l[i].height * (0.55f + 0.45f * ((u.time - l[i].born) / l[i].life * 2.2f).coerceIn(0f, 1f))
            shader.uniform("uLoopAngle", l[0].angle, l[1].angle, l[2].angle, l[3].angle)
            shader.uniform("uLoopWidth", l[0].width, l[1].width, l[2].width, l[3].width)
            shader.uniform("uLoopHeight", height(0), height(1), height(2), height(3))
            shader.uniform("uLoopLife", life(0), life(1), life(2), life(3))
            drawCircle(shader.brush(), span, center)
        } else {
            val cold = Color(0.45f, 0.66f, 1f)
            val warm = Color(1f, 0.55f, 0.15f)
            val c = lerp(cold, warm, h)
            drawCircle(
                Brush.radialGradient(
                    0.5f to c.copy(alpha = 0.55f), 0.62f to c.copy(alpha = 0.22f), 1f to Color.Transparent,
                    center = center, radius = radius * 2f,
                ),
                radius * 2f, center,
            )
        }
    }

    private fun DrawScope.bodyLayer(u: Universe, cam: Camera, center: Offset, radius: Float, h: Float, hover: Float, images: CosmicImages?) {
        val shader = body
        if (shader != null && images != null) {
            // View space → the body's own frame: the camera's axes, then the leaning axis, then the spin.
            M3.rz(0.12f).times(M3.rx(0.18f), bodyFrame).times(M3.ry(u.spin), bodyFrame)
            cam.viewRows(rows).times(bodyFrame, rows).transpose(rows)
            shader.uniform("uCenter", center.x, center.y)
            shader.uniform("uRadius", radius)
            shader.uniform("uRow0", rows.a, rows.b, rows.c)
            shader.uniform("uRow1", rows.d, rows.e, rows.f)
            shader.uniform("uRow2", rows.g, rows.h, rows.i)
            shader.uniform("uTime", u.time)
            shader.uniform("uHeat", h)
            shader.uniform("uHover", hover)
            shader.uniform("uRed", u.red.value)
            shader.uniform("uMoonSize", images.moon.width.toFloat(), images.moon.height.toFloat())
            shader.uniform("uHeightSize", images.height.width.toFloat(), images.height.height.toFloat())
            shader.uniform("uSunSize", images.sun.width.toFloat(), images.sun.height.toFloat())
            shader.image("uMoon", images.moon)
            shader.image("uHeight", images.height)
            shader.image("uSun", images.sun)
            drawCircle(shader.brush(), radius + 2f, center)
            return
        }
        // Without shaders: the pictures turn inside the circle, shaded into a sphere.
        val box = Size(radius * 2, radius * 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(topLeft, box)) }
        clipPath(circle) {
            drawCircle(lerp(Color(0xFF8C93A3), Color(0xFFE0641A), h), radius, center)
            if (images != null) {
                val moon = images.moon
                val w = moon.height
                val x = (((-u.spin / (2 * PI)) % 1.0 + 1.0) % 1.0 * (moon.width - w)).toInt()
                drawImage(moon, IntOffset(x, 0), IntSize(w, moon.height), IntOffset(topLeft.x.toInt(), topLeft.y.toInt()), IntSize(box.width.toInt(), box.height.toInt()), alpha = 1f - h)
                drawImage(images.sun, IntOffset.Zero, IntSize(images.sun.width, images.sun.height), IntOffset(topLeft.x.toInt(), topLeft.y.toInt()), IntSize(box.width.toInt(), box.height.toInt()), alpha = h)
            }
            drawCircle(
                Brush.radialGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f), center = Offset(center.x - radius * 0.25f, center.y - radius * 0.25f), radius = radius * 1.35f),
                radius, center,
            )
            if (u.red.value > 0f) drawCircle(Color(0.9f, 0.1f, 0.05f, 0.45f * u.red.value), radius, center)
        }
    }

    private fun DrawScope.shock(u: Universe, center: Offset, radius: Float, dp: Float) {
        val p = u.shockProgress
        if (p >= 1f) return
        val e = 1f - (1f - p).pow(3)
        val color = lerp(Color(1f, 0.72f, 0.38f), Color(0.5f, 0.66f, 1f), u.shockColdness)
        drawCircle(color.copy(alpha = (1f - p).pow(2.2f) * 0.55f), radius * (1.15f + 1.7f * e), center, style = Stroke((3f - 2.2f * e) * dp))
    }

    // ---- Links and traffic ----

    private val curve = Points(25)
    /** Comet tails, by link colour (gold, blue) and place in the tail: drawn as one batch of round points each. */
    private val tailPoints = Array(2) { Array(9) { ArrayList<Offset>(64) } }
    private val glowPoints = Array(3) { ArrayList<Offset>(32) }
    private val corePoints = ArrayList<Offset>(96)
    private val glowColors = arrayOf(lerp(GOLD, Color.White, 0.55f), lerp(BLUE, Color.White, 0.55f), Color(0.85f, 0.96f, 1f))
    /** A glow of radius 1 at the origin per colour, made once: each comet is drawn with it moved and scaled. */
    private val glowBrushes = glowColors.map { c -> Brush.radialGradient(listOf(c.copy(alpha = 0.55f), c.copy(alpha = 0.12f), Color.Transparent), Offset.Zero, 1f) }

    private fun DrawScope.links(u: Universe, cam: Camera, dp: Float) {
        val t = u.time
        for (side in tailPoints) for (list in side) list.clear()
        for (list in glowPoints) list.clear()
        corePoints.clear()
        for (l in u.links) {
            if (t < l.born) continue
            val drawn = easeOut(((t - l.born) / l.drawTime).coerceIn(0f, 1f))
            val gone = if (l.retireAt == Float.POSITIVE_INFINITY) 0f else easeOut(((t - l.retireAt) / Universe.RETIRE_TIME).coerceIn(0f, 1f))
            if (gone >= 1f) continue
            val a = u.devices[l.a].anchor
            val b = if (l.b == -1) {
                val len = a.length()
                V3(a.x / len * BODY_RADIUS * 0.98f, a.y / len * BODY_RADIUS * 0.98f, a.z / len * BODY_RADIUS * 0.98f)
            } else u.devices[l.b].anchor
            // A gentle bend, turned across the line of sight; links between devices also bow away from the body.
            val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2; val mz = (a.z + b.z) / 2
            val vx = cam.eye.x - mx; val vy = cam.eye.y - my; val vz = cam.eye.z - mz
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val span = sqrt(dx * dx + dy * dy + dz * dz)
            var ox = dy * vz - dz * vy; var oy = dz * vx - dx * vz; var oz = dx * vy - dy * vx
            val ol = sqrt(ox * ox + oy * oy + oz * oz).coerceAtLeast(1e-4f)
            ox = ox / ol * span * l.bend; oy = oy / ol * span * l.bend; oz = oz / ol * span * l.bend
            val ml = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-4f)
            val push = if (l.b == -1) 0f else span * 0.08f
            val cx = mx + ox + mx / ml * push; val cy = my + oy + my / ml * push; val cz = mz + oz + mz / ml * push
            for (j in curve.indices) {
                val s = j.toFloat() / (curve.size - 1)
                val w = 1 - s
                tmp.set(w * w * a.x + 2 * w * s * cx + s * s * b.x, w * w * a.y + 2 * w * s * cy + s * s * b.y, w * w * a.z + 2 * w * s * cz + s * s * b.z)
                curve[j] = cam.project(tmp)
            }
            val base = if (l.gold) GOLD else BLUE
            val side = if (l.gold) 0 else 1
            val count = polyline(gone, drawn)
            strips.line(linePoints, count, 4f * dp, base.copy(alpha = 0.12f))
            strips.line(linePoints, count, 1.4f * dp, base.copy(alpha = 0.7f))
            if (drawn < 1f) { val p = at(drawn); glowPoints[2] += p; corePoints += p }
            if (drawn >= 1f && gone == 0f) {
                for (k in 0 until l.count) {
                    val ph = u.pulsePhase(l, k)
                    val head = if (l.dir > 0) ph else 1 - ph
                    for (j in 9 downTo 1) {
                        val s = head - l.dir * j * 0.011f
                        if (s < 0f || s > 1f) continue
                        tailPoints[side][j - 1] += at(s)
                    }
                    val p = at(head)
                    glowPoints[side] += p
                    corePoints += p
                }
            }
        }
        strips.flush(this)
        // The comets, after the lines: a few batches instead of a draw (and a new gradient) for every dot.
        for (side in 0..1) {
            val tail = lerp(if (side == 0) GOLD else BLUE, Color.White, 0.3f)
            for (j in 9 downTo 1) {
                val list = tailPoints[side][j - 1]
                if (list.isEmpty()) continue
                val f = 1f - j / 10f
                drawPoints(list, PointMode.Points, tail.copy(alpha = f.pow(1.5f) * 0.75f), strokeWidth = 2f * (0.6f + 1.6f * f) * dp, cap = StrokeCap.Round)
            }
        }
        for (c in 0..2) {
            val r = (if (c == 2) 6f else 7f) * dp * 2.2f
            for (p in glowPoints[c]) {
                withTransform({ translate(p.x, p.y); scale(r, r, Offset.Zero) }) { drawCircle(glowBrushes[c], 1f, Offset.Zero) }
            }
        }
        if (corePoints.isNotEmpty()) drawPoints(corePoints, PointMode.Points, Color.White.copy(alpha = 0.95f), strokeWidth = 7f * dp * 0.56f, cap = StrokeCap.Round)
    }

    /** The drawn part of the link, [from]..[to] of it, into [linePoints]; how many points it took. */
    private fun polyline(from: Float, to: Float): Int {
        val n = curve.size - 1
        var k = 0
        linePoints[k++] = at(from)
        val i0 = (from * n).toInt() + 1
        val i1 = (to * n).toInt()
        for (i in i0..min(i1, n)) linePoints[k++] = curve[i]
        linePoints[k++] = at(to)
        return k
    }

    private fun at(s: Float): Offset {
        val n = curve.size - 1
        val x = (s.coerceIn(0f, 1f) * n)
        val i = min(x.toInt(), n - 1)
        val f = x - i
        val a = curve[i]
        val b = curve[i + 1]
        return Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    // ---- Devices ----

    private fun DrawScope.device(d: Device, u: Universe, cam: Camera, h: Float) {
        val visible = ArrayList<Pair<Panel, Float>>(8)
        for (panel in d.panels) {
            d.rotation.apply(panel.normal, tmp2)
            worldCorners(d, panel)
            val cx = (corners[0].x + corners[2].x) / 2; val cy = (corners[0].y + corners[2].y) / 2; val cz = (corners[0].z + corners[2].z) / 2
            tmp.set(cx, cy, cz)
            if (!cam.faces(tmp, tmp2)) continue
            visible += panel to cam.depth(tmp)
        }
        visible.sortByDescending { it.second }
        // Light spilling from a woken screen, under the device.
        for ((panel, _) in visible) if (panel.face == Face.Screen && d.power.value > 0.3f) halo(d, panel, cam)
        for ((panel, _) in visible) panel(d, panel, u, cam, h)
    }

    private fun worldCorners(d: Device, panel: Panel) {
        for (k in 0..3) {
            d.rotation.apply(panel.corners[k], corners[k])
            corners[k].set(corners[k].x + d.position.x, corners[k].y + d.position.y, corners[k].z + d.position.z)
        }
    }

    /** Sets [matrix] so a W×H drawing (W = 100 × aspect, H = 100) lies on [panel] in perspective, top-left first. */
    private fun place(d: Device, panel: Panel, cam: Camera) {
        worldCorners(d, panel)
        for (k in 0..3) projected[k] = cam.project(corners[k])
        squareToQuad(projected[3], projected[2], projected[1], projected[0], matrix)
    }

    private fun lit(d: Device, panel: Panel, cam: Camera, h: Float): Color {
        d.rotation.apply(panel.normal, tmp2)
        val cx = (corners[0].x + corners[2].x) / 2; val cy = (corners[0].y + corners[2].y) / 2; val cz = (corners[0].z + corners[2].z) / 2
        val dist = sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(0.5f)
        val lx = -cx / dist; val ly = -cy / dist; val lz = -cz / dist
        val diffuse = max(0f, tmp2.x * lx + tmp2.y * ly + tmp2.z * lz)
        val near = (10f / (dist * dist)).coerceIn(0.35f, 1.4f)
        val sun = Color(1f, 0.8f, 0.55f)
        val moon = Color(0.62f, 0.74f, 1f)
        val light = lerp(moon.copy(alpha = 1f), sun, h)
        val strength = (0.8f + 0.5f * h) * near
        // Cold light from above and behind outlines them against space.
        val rim = max(0f, tmp2.x * -0.55f + tmp2.y * 0.73f + tmp2.z * -0.37f) * 0.5f * (1f - 0.5f * h)
        var vx = cam.eye.x - cx; var vy = cam.eye.y - cy; var vz = cam.eye.z - cz
        val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
        var hx = lx + vx; var hy = ly + vy; var hz = lz + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f); hx /= hl; hy /= hl; hz /= hl
        val spec = max(0f, tmp2.x * hx + tmp2.y * hy + tmp2.z * hz).pow(26f) * panel.material.metal * 0.55f * strength
        val base = panel.material.color
        val amb = 0.2f
        return Color(
            (base.red * (amb + diffuse * strength * light.red + rim * 0.6f) + spec * light.red).coerceIn(0f, 1f),
            (base.green * (amb + diffuse * strength * light.green + rim * 0.7f) + spec * light.green).coerceIn(0f, 1f),
            (base.blue * (amb + diffuse * strength * light.blue + rim) + spec * light.blue).coerceIn(0f, 1f),
        )
    }

    private fun DrawScope.panel(d: Device, panel: Panel, u: Universe, cam: Camera, h: Float) {
        place(d, panel, cam)
        val color = lit(d, panel, cam, h)
        val w = 100f * panel.aspect
        val hh = 100f
        val r = panel.round * min(w, hh)
        // The face as the polygon it projects to, filled straight: no perspective matrix, so no mask.
        val face = outline(0f, 0f, w, hh, r, w, hh)
        drawPath(face, color)
        // A metal sheen across the face.
        drawPath(face, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.0f), Color.White.copy(alpha = 0.07f + 0.05f * h), Color.White.copy(alpha = 0f)), map(0f, 0f, w, hh), map(w, hh, w, hh)))
        when (panel.face) {
            Face.Screen -> {
                val m = 0.035f * min(w, hh)
                val p = d.power.value
                if (p > 0.001f && p < 0.999f) {
                    // Waking or going to sleep: the picture opens inside a clip, drawn in the screen's own frame.
                    val kept = brushes(panel)
                    withTransform({
                        transform(matrix)
                        scale(1f / w, 1f / hh, Offset.Zero)
                    }) { screen(d, u, Offset(m, m), Size(w - 2 * m, hh - 2 * m), (r - m).coerceAtLeast(1f), h, kept) }
                } else {
                    flatScreen(d, u, m, w, hh, (r - m).coerceAtLeast(1f), h, awake = p >= 0.999f)
                }
            }
            Face.Keys -> {
                val keys = keyRects(panel, w, hh)
                var i = 0
                while (i < keys.size) {
                    val x0 = keys[i]; val y0 = keys[i + 1]; val x1 = keys[i + 2]; val y1 = keys[i + 3]
                    strips.quad(map(x0, y0, w, hh), map(x1, y0, w, hh), map(x1, y1, w, hh), map(x0, y1, w, hh), KEY)
                    i += 4
                }
                strips.flush(this)
            }
            else -> Unit
        }
    }

    /** The point (x, y) of a [w]×[h] panel drawing on the canvas, through the projective map [place] set. */
    private fun map(x: Float, y: Float, w: Float, h: Float): Offset {
        val v = matrix.values
        val s = x / w
        val t = y / h
        val z = v[3] * s + v[7] * t + v[15]
        return Offset((v[0] * s + v[4] * t + v[12]) / z, (v[1] * s + v[5] * t + v[13]) / z)
    }

    private val shape = Path()

    /**
     * A rounded rectangle of a panel's [w]×[h] drawing as the convex polygon it projects to. The GPU fills a convex
     * polygon directly; a rounded rectangle under a perspective matrix it draws through a mask, every frame.
     */
    private fun outline(x: Float, y: Float, rw: Float, rh: Float, r: Float, w: Float, h: Float): Path {
        val path = shape
        path.reset()
        val rr = r.coerceIn(0f, min(rw, rh) / 2)
        val steps = if (rr > 0f) 3 else 0
        var first = true
        for (c in 0..3) {
            val cx = if (c == 0 || c == 3) x + rr else x + rw - rr
            val cy = if (c < 2) y + rr else y + rh - rr
            for (k in 0..steps) {
                val a = PI.toFloat() * (1f + c * 0.5f) + (if (steps == 0) 0f else k * PI.toFloat() / 2 / steps)
                val p = map(cx + rr * cos(a), cy + rr * sin(a), w, h)
                if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                first = false
            }
        }
        path.close()
        return path
    }

    /** A circle of a panel's drawing as the polygon it projects to. */
    private fun disc(cx: Float, cy: Float, radius: Float, w: Float, h: Float): Path {
        val path = shape
        path.reset()
        for (k in 0 until 16) {
            val a = k * 2 * PI.toFloat() / 16
            val p = map(cx + radius * cos(a), cy + radius * sin(a), w, h)
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        return path
    }

    private val markPoints = Points(17)
    private val arcPoints = Points(9)

    /** A screen fully asleep or fully awake, drawn flat on the canvas: the glass, and when [awake] the picture. */
    private fun DrawScope.flatScreen(d: Device, u: Universe, m: Float, w: Float, hh: Float, r: Float, h: Float, awake: Boolean) {
        val sw = w - 2 * m
        val sh = hh - 2 * m
        val glass = outline(m, m, sw, sh, r, w, hh)
        // Glass that is off: black, with a sheen of whatever light falls on it.
        drawPath(glass, Color(0xFF030408))
        drawPath(glass, Brush.linearGradient(
            0.35f to Color.Transparent, 0.5f to lerp(Color(0.5f, 0.6f, 0.8f), Color(1f, 0.75f, 0.45f), h).copy(alpha = 0.06f + 0.08f * h), 0.62f to Color.Transparent,
            start = map(m, m, w, hh), end = map(m + sw, m + sh, w, hh),
        ))
        if (!awake) return
        // What a woken screen shows (as [ui]): the OpenFlux mark with a turning arc, rows of light, a line of traffic.
        val t = u.time
        val tint = d.tint
        val ox = m
        val oy = m
        val wide = sw > sh * 1.2f
        drawPath(glass, Brush.linearGradient(listOf(Color(0.01f, 0.07f, 0.15f), Color(0.0f, 0.02f, 0.06f)), map(ox, oy, w, hh), map(ox, oy + sh, w, hh)))
        // Pixels of the canvas to one unit of the drawing, for the lines' widths.
        val a0 = map(ox, oy, w, hh)
        val a1 = map(ox + sw, oy, w, hh)
        val k = sqrt((a1.x - a0.x) * (a1.x - a0.x) + (a1.y - a0.y) * (a1.y - a0.y)) / sw
        val unit = sh
        val lx = if (wide) ox + unit * 0.22f else ox + sw / 2
        val ly = if (wide) oy + unit * 0.28f else oy + unit * 0.24f
        val lr = unit * 0.075f
        drawPath(disc(lx, ly, lr * 2.2f, w, hh), tint.copy(alpha = 0.18f))
        for (j in 0..16) {
            val a = j * 2 * PI.toFloat() / 16
            markPoints[j] = map(lx + lr * cos(a), ly + lr * sin(a), w, hh)
        }
        strips.line(markPoints, 17, unit * 0.007f * k, tint.copy(alpha = 0.85f))
        val start = ((t * 140f + d.seed * 60f) % 360f) * PI.toFloat() / 180f
        for (j in 0..8) {
            val a = start + j * (70f * PI.toFloat() / 180f) / 8
            arcPoints[j] = map(lx + lr * cos(a), ly + lr * sin(a), w, hh)
        }
        strips.line(arcPoints, 9, unit * 0.01f * k, Color.White.copy(alpha = 0.9f))
        val x0 = if (wide) ox + unit * 0.44f else ox + sw * 0.1f
        val room = if (wide) sw - unit * 0.56f else sw * 0.8f
        for (i in 0 until 4) {
            val y = if (wide) oy + unit * (0.2f + i * 0.13f) else oy + unit * (0.44f + i * 0.1f)
            val hsh = (sin(d.seed * 12.9898f + i * 78.233f) * 43758.547f).let { it - kotlin.math.floor(it) }
            val rw = room * (0.35f + 0.6f * hsh)
            val shimmer = 0.5f + 0.5f * sin(t * 1.7f + i * 1.3f + d.seed * 9f)
            drawPath(outline(x0, y, rw, unit * 0.045f, unit * 0.022f, w, hh), lerp(tint, Color.White, 0.25f).copy(alpha = 0.18f + 0.22f * shimmer))
        }
        val wy = oy + unit * 0.84f
        val left = ox + sw * 0.08f
        val right = ox + sw * 0.92f
        for (j in 0..24) {
            val x = left + (right - left) * j / 24f
            val cx = (x - ox) / unit
            val y = wy + unit * 0.045f * sin(cx * 15f - t * 3.2f + d.seed * 5f) * sin(cx * 3.3f + t * 0.8f)
            wavePoints[j] = map(x, y, w, hh)
        }
        strips.line(wavePoints, 25, unit * 0.03f * k, tint.copy(alpha = 0.25f))
        strips.line(wavePoints, 25, unit * 0.012f * k, tint)
        strips.flush(this)
        drawPath(disc(lx, ly, unit * 0.018f, w, hh), Color.White)
        if (d.flash > 0f) drawPath(outline(m, m, sw, sh, r, w, hh), d.tint.copy(alpha = d.flash * 0.3f))
    }

    /** What a panel keeps between frames: a waking screen's gradients, a deck's keys. */
    private class PanelBrushes {
        var glass: Brush? = null
        var screen: Brush? = null
        var screenAlpha = -1f
        var keys: FloatArray? = null
    }

    private val panelBrushes = HashMap<Panel, PanelBrushes>()

    private fun brushes(panel: Panel): PanelBrushes = panelBrushes.getOrPut(panel) { PanelBrushes() }

    private fun keyRects(panel: Panel, w: Float, hh: Float): FloatArray = brushes(panel).let { b -> b.keys ?: keyboard(w, hh).also { b.keys = it } }

    private fun DrawScope.halo(d: Device, panel: Panel, cam: Camera) {
        // Drawn in the screen plane but outside it: what the glow of the screen puts on the space round it.
        val worldW = 100f * panel.aspect
        val worldH = 100f
        val p = d.power.value
        place(d, panel, cam)
        val a = p * p * (0.6f + 0.5f * d.flash)
        for ((grow, alpha) in listOf(16f to 0.05f, 9f to 0.08f, 4f to 0.12f)) {
            drawPath(outline(-grow, -grow, worldW + 2 * grow, worldH + 2 * grow, grow + 6f, worldW, worldH), d.tint.copy(alpha = alpha * a))
        }
    }

    private fun DrawScope.screen(d: Device, u: Universe, topLeft: Offset, s: Size, r: Float, h: Float, kept: PanelBrushes) {
        val corner = CornerRadius(r)
        // Glass that is off: black, with a sheen of whatever light falls on it.
        drawRoundRect(Color(0xFF030408), topLeft, s, corner)
        val glass = kept.glass ?: Brush.linearGradient(
            0.35f to Color.Transparent, 0.5f to lerp(Color(0.5f, 0.6f, 0.8f), Color(1f, 0.75f, 0.45f), h).copy(alpha = 0.06f + 0.08f * h), 0.62f to Color.Transparent,
            start = topLeft, end = Offset(topLeft.x + s.width, topLeft.y + s.height),
        ).also { kept.glass = it }
        drawRoundRect(glass, topLeft, s, corner)
        val p = d.power.value
        if (p <= 0.001f) return
        // Waking up as an old tube does: a line, the picture opening from it, a flash settling; asleep, the reverse.
        val cx = topLeft.x + s.width / 2
        val cy = topLeft.y + s.height / 2
        val halfW = smooth(0f, 0.3f, p) * s.width / 2
        val halfH = s.height * (0.006f + (0.5f - 0.006f) * smooth(0.25f, 0.65f, p))
        val settle = smooth(0.55f, 1f, p)
        clipRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH) {
            if (settle > 0f) ui(d, u, topLeft, s, corner, settle, kept)
            if (settle < 1f) drawRect(lerp(d.tint, Color(0.85f, 0.96f, 1f), 0.5f).copy(alpha = (1f - settle) * 0.85f), topLeft, s)
        }
        if (d.flash > 0f) drawRoundRect(d.tint.copy(alpha = d.flash * 0.3f * settle), topLeft, s, corner)
    }

    /** What a woken screen shows: the OpenFlux mark with a turning arc, a few rows of light, a line of traffic. */
    private fun DrawScope.ui(d: Device, u: Universe, o: Offset, s: Size, corner: CornerRadius, a: Float, kept: PanelBrushes) {
        val t = u.time
        val tint = d.tint
        val wide = s.width > s.height * 1.2f
        // Its backdrop is made again only while the picture opens (its alpha moves), not every frame after.
        val backdrop = kept.screen?.takeIf { abs(kept.screenAlpha - a) < 0.02f }
            ?: Brush.verticalGradient(listOf(Color(0.01f, 0.07f, 0.15f, a), Color(0.0f, 0.02f, 0.06f, a)), o.y, o.y + s.height).also { kept.screen = it; kept.screenAlpha = a }
        drawRoundRect(backdrop, o, s, corner)
        val unit = s.height
        val logo = if (wide) Offset(o.x + unit * 0.22f, o.y + unit * 0.28f) else Offset(o.x + s.width / 2, o.y + unit * 0.24f)
        val lr = unit * 0.075f
        drawCircle(tint.copy(alpha = 0.18f * a), lr * 2.2f, logo)
        drawCircle(tint.copy(alpha = 0.85f * a), lr, logo, style = Stroke(unit * 0.012f))
        drawArc(Color.White.copy(alpha = 0.9f * a), (t * 140f + d.seed * 60f) % 360f, 70f, false, Offset(logo.x - lr, logo.y - lr), Size(lr * 2, lr * 2), style = Stroke(unit * 0.016f, cap = StrokeCap.Round))
        drawCircle(Color.White.copy(alpha = a), unit * 0.018f, logo)
        val x0 = if (wide) o.x + unit * 0.44f else o.x + s.width * 0.1f
        val room = if (wide) s.width - unit * 0.56f else s.width * 0.8f
        for (i in 0 until 4) {
            val y = if (wide) o.y + unit * (0.2f + i * 0.13f) else o.y + unit * (0.44f + i * 0.1f)
            val hsh = (sin(d.seed * 12.9898f + i * 78.233f) * 43758.547f).let { it - kotlin.math.floor(it) }
            val w = room * (0.35f + 0.6f * hsh)
            val shimmer = 0.5f + 0.5f * sin(t * 1.7f + i * 1.3f + d.seed * 9f)
            drawRoundRect(lerp(tint, Color.White, 0.25f).copy(alpha = (0.18f + 0.22f * shimmer) * a), Offset(x0, y), Size(w, unit * 0.045f), CornerRadius(unit * 0.022f))
        }
        val wy = o.y + unit * 0.84f
        val left = o.x + s.width * 0.08f
        val right = o.x + s.width * 0.92f
        for (k in 0..24) {
            val x = left + (right - left) * k / 24f
            val cx = (x - o.x) / unit
            val y = wy + unit * 0.045f * sin(cx * 15f - t * 3.2f + d.seed * 5f) * sin(cx * 3.3f + t * 0.8f)
            wavePoints[k] = Offset(x, y)
        }
        strips.line(wavePoints, 25, unit * 0.03f, tint.copy(alpha = 0.25f * a))
        strips.line(wavePoints, 25, unit * 0.012f, tint.copy(alpha = a))
        strips.flush(this)
    }

}

private val GOLD = Color(1f, 0.72f, 0.32f)
private val BLUE = Color(0.25f, 0.78f, 1f)
private val KEY = Color(0xFF14171D)

/** A laptop's keys and touchpad on its [w]×[h] deck, as rectangles (x0, y0, x1, y1 each): made once per panel. */
private fun keyboard(w: Float, h: Float): FloatArray {
    val out = ArrayList<Float>()
    val left = w * 0.06f
    val top = h * 0.08f
    val rows = intArrayOf(14, 14, 13, 12, 11)
    val rowH = h * 0.085f
    for ((ri, n) in rows.withIndex()) {
        val gap = w * 0.008f
        val kw = (w * 0.88f - gap * (n - 1)) / n
        for (k in 0 until n) {
            val x = left + k * (kw + gap)
            val y = top + ri * (rowH + gap)
            out += x; out += y; out += x + kw; out += y + rowH
        }
    }
    // The touchpad, in the keys' colour: close enough at this size.
    out += w * 0.34f; out += h * 0.62f; out += w * 0.66f; out += h * 0.92f
    return out.toFloatArray()
}
