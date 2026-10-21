package io.openflux.app.cosmic

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
 * Draws a [Universe] as the camera sees it, back to front: space, the far halves of the orbits, the devices
 * behind the body, its corona, the body, the ring of light, the near halves of the orbits, the links and their
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
        space(u, h)
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

    // ---- Space ----

    private fun DrawScope.space(u: Universe, h: Float) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF060A16), Color(0xFF02040A))))
        val m = max(size.width, size.height)
        val blobs = listOf(
            Triple(Offset(0.18f, 0.22f), lerp(Color(0x2E2A3C8A), Color(0x2A0E6E78), h), 0.62f),
            Triple(Offset(0.86f, 0.72f), lerp(Color(0x24203A7A), Color(0x22A25C14), h), 0.58f),
            Triple(Offset(0.62f, 0.08f), lerp(Color(0x161C2E66), Color(0x1A1C8C9E), h), 0.45f),
        )
        for ((pos, color, r) in blobs) {
            val c = Offset(pos.x * size.width, pos.y * size.height)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), c, r * m), r * m, c)
        }
        // The Sun's light on the space round it.
        if (h > 0.01f) {
            val c = center
            drawCircle(Brush.radialGradient(listOf(Color(1f, 0.6f, 0.22f, 0.22f * h), Color.Transparent), c, m * 0.45f), m * 0.45f, c)
        }
        val t = u.time
        val bright = 1f - 0.35f * h
        for (s in u.stars) {
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.6f + s.phase * 0.3f) + s.phase * 40f))
            val a = (tw * bright * (0.35f + 0.65f * s.depth)).coerceIn(0f, 1f)
            val color = if (s.warm) Color(1f, 0.92f, 0.82f, a) else Color(0.78f, 0.86f, 1f, a)
            drawCircle(color, s.size * density * 0.6f, Offset((s.x * 0.5f + 0.5f) * size.width, (s.y * 0.5f + 0.5f) * size.height))
        }
    }

    // ---- The orbits, faint, with light running along them ----

    private fun DrawScope.orbits(u: Universe, cam: Camera, bodyDepth: Float, behind: Boolean) {
        val show = u.devices.sumOf { it.order.value.toDouble() }.toFloat() / u.devices.size
        if (show < 0.02f) return
        val n = 120
        for (ring in u.rings) {
            val path = Path()
            var open = false
            for (k in 0..n) {
                val a = k.toFloat() / n * 2 * PI.toFloat()
                tmp.set(cos(a) * ring.radius, 0f, sin(a) * ring.radius)
                ring.tilt.apply(tmp, tmp2)
                val isBehind = cam.depth(tmp2) > bodyDepth
                val p = cam.project(tmp2)
                if (isBehind == behind) {
                    if (open) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                    open = true
                } else open = false
            }
            val color = Color(0.45f, 0.78f, 1f, 0.22f * show)
            val dp = density
            drawPath(path, color, style = Stroke(1f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * dp, 7f * dp), -u.time * 14f * dp)))
        }
    }

    // ---- The body ----

    private fun DrawScope.coronaLayer(u: Universe, center: Offset, radius: Float, h: Float, hover: Float) {
        val span = radius * 3.6f
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
            drawRect(shader.brush(), Offset(center.x - span, center.y - span), Size(span * 2, span * 2))
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

    private val curve = Array(25) { Offset.Zero }

    private fun DrawScope.links(u: Universe, cam: Camera, dp: Float) {
        val t = u.time
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
            val base = if (l.gold) Color(1f, 0.72f, 0.32f) else Color(0.25f, 0.78f, 1f)
            val path = polyline(gone, drawn)
            drawPath(path, base.copy(alpha = 0.09f), style = Stroke(6f * dp, cap = StrokeCap.Round))
            drawPath(path, base.copy(alpha = 0.6f), style = Stroke(1.4f * dp, cap = StrokeCap.Round))
            if (drawn < 1f) glowDot(at(drawn), Color(0.85f, 0.96f, 1f), 6f * dp, 1f)
            if (drawn >= 1f && gone == 0f) {
                for (k in 0 until l.count) {
                    val ph = u.pulsePhase(l, k)
                    val head = if (l.dir > 0) ph else 1 - ph
                    for (j in 9 downTo 1) {
                        val s = head - l.dir * j * 0.011f
                        if (s < 0f || s > 1f) continue
                        val f = 1f - j / 10f
                        drawCircle(lerp(base, Color.White, 0.3f).copy(alpha = f.pow(1.5f) * 0.75f), (0.6f + 1.6f * f) * dp, at(s))
                    }
                    glowDot(at(head), lerp(base, Color.White, 0.55f), 7f * dp, 1f)
                }
            }
        }
    }

    private fun polyline(from: Float, to: Float): Path {
        val path = Path()
        val n = curve.size - 1
        val start = at(from)
        path.moveTo(start.x, start.y)
        val i0 = (from * n).toInt() + 1
        val i1 = (to * n).toInt()
        for (i in i0..min(i1, n)) path.lineTo(curve[i].x, curve[i].y)
        val end = at(to)
        path.lineTo(end.x, end.y)
        return path
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

    private fun DrawScope.glowDot(p: Offset, color: Color, r: Float, a: Float) {
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * a), color.copy(alpha = 0.12f * a), Color.Transparent), p, r * 2.2f), r * 2.2f, p)
        drawCircle(Color.White.copy(alpha = 0.95f * a), r * 0.28f, p)
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
        withTransform({
            transform(matrix)
            scale(1f / w, 1f / hh, Offset.Zero)
        }) {
            drawRoundRect(color, Offset.Zero, Size(w, hh), CornerRadius(r))
            // A metal sheen across the face.
            drawRoundRect(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.0f), Color.White.copy(alpha = 0.07f + 0.05f * h), Color.White.copy(alpha = 0f)), Offset(0f, 0f), Offset(w, hh)),
                Offset.Zero, Size(w, hh), CornerRadius(r),
            )
            when (panel.face) {
                Face.Screen -> {
                    val m = 0.035f * min(w, hh)
                    screen(d, u, Offset(m, m), Size(w - 2 * m, hh - 2 * m), (r - m).coerceAtLeast(1f), h)
                }
                Face.Keys -> keys(w, hh)
                else -> Unit
            }
        }
    }

    private fun DrawScope.halo(d: Device, panel: Panel, cam: Camera) {
        // Drawn in the screen plane but outside it: what the glow of the screen puts on the space round it.
        val worldW = 100f * panel.aspect
        val worldH = 100f
        val p = d.power.value
        place(d, panel, cam)
        val a = p * p * (0.6f + 0.5f * d.flash)
        withTransform({
            transform(matrix)
            scale(1f / worldW, 1f / worldH, Offset.Zero)
        }) {
            for ((grow, alpha) in listOf(16f to 0.05f, 9f to 0.08f, 4f to 0.12f)) {
                drawRoundRect(d.tint.copy(alpha = alpha * a), Offset(-grow, -grow), Size(worldW + 2 * grow, worldH + 2 * grow), CornerRadius(grow + 6f))
            }
        }
    }

    private fun DrawScope.screen(d: Device, u: Universe, topLeft: Offset, s: Size, r: Float, h: Float) {
        val corner = CornerRadius(r)
        // Glass that is off: black, with a sheen of whatever light falls on it.
        drawRoundRect(Color(0xFF030408), topLeft, s, corner)
        drawRoundRect(
            Brush.linearGradient(
                0.35f to Color.Transparent, 0.5f to lerp(Color(0.5f, 0.6f, 0.8f), Color(1f, 0.75f, 0.45f), h).copy(alpha = 0.06f + 0.08f * h), 0.62f to Color.Transparent,
                start = topLeft, end = Offset(topLeft.x + s.width, topLeft.y + s.height),
            ),
            topLeft, s, corner,
        )
        val p = d.power.value
        if (p <= 0.001f) return
        // Waking up as an old tube does: a line, the picture opening from it, a flash settling; asleep, the reverse.
        val cx = topLeft.x + s.width / 2
        val cy = topLeft.y + s.height / 2
        val halfW = smooth(0f, 0.3f, p) * s.width / 2
        val halfH = s.height * (0.006f + (0.5f - 0.006f) * smooth(0.25f, 0.65f, p))
        val settle = smooth(0.55f, 1f, p)
        clipRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH) {
            if (settle > 0f) ui(d, u, topLeft, s, corner, settle)
            if (settle < 1f) drawRect(lerp(d.tint, Color(0.85f, 0.96f, 1f), 0.5f).copy(alpha = (1f - settle) * 0.85f), topLeft, s)
        }
        if (d.flash > 0f) drawRoundRect(d.tint.copy(alpha = d.flash * 0.3f * settle), topLeft, s, corner)
    }

    /** What a woken screen shows: the OpenFlux mark with a turning arc, a few rows of light, a line of traffic. */
    private fun DrawScope.ui(d: Device, u: Universe, o: Offset, s: Size, corner: CornerRadius, a: Float) {
        val t = u.time
        val tint = d.tint
        val wide = s.width > s.height * 1.2f
        drawRoundRect(Brush.verticalGradient(listOf(Color(0.01f, 0.07f, 0.15f, a), Color(0.0f, 0.02f, 0.06f, a)), o.y, o.y + s.height), o, s, corner)
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
        val wave = Path()
        val wy = o.y + unit * 0.84f
        val left = o.x + s.width * 0.08f
        val right = o.x + s.width * 0.92f
        for (k in 0..24) {
            val x = left + (right - left) * k / 24f
            val cx = (x - o.x) / unit
            val y = wy + unit * 0.045f * sin(cx * 15f - t * 3.2f + d.seed * 5f) * sin(cx * 3.3f + t * 0.8f)
            if (k == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        drawPath(wave, tint.copy(alpha = 0.95f * a), style = Stroke(unit * 0.014f, cap = StrokeCap.Round))
        drawPath(wave, tint.copy(alpha = 0.2f * a), style = Stroke(unit * 0.05f, cap = StrokeCap.Round))
    }

    private fun DrawScope.keys(w: Float, h: Float) {
        val key = Color(0xFF14171D)
        val left = w * 0.06f
        val top = h * 0.08f
        val rows = intArrayOf(14, 14, 13, 12, 11)
        val rowH = h * 0.085f
        for ((ri, n) in rows.withIndex()) {
            val gap = w * 0.008f
            val kw = (w * 0.88f - gap * (n - 1)) / n
            for (k in 0 until n) {
                drawRoundRect(key, Offset(left + k * (kw + gap), top + ri * (rowH + gap)), Size(kw, rowH), CornerRadius(rowH * 0.2f))
            }
        }
        drawRoundRect(Color(0xFF111318), Offset(w * 0.34f, h * 0.62f), Size(w * 0.32f, h * 0.3f), CornerRadius(h * 0.04f))
    }
}
