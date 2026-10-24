package io.openflux.app.cosmic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.openflux.app.components.ConnectLook
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Writes Home's scene as Lottie: moon.json (off) and sun.json (connected), into LOTTIE_OUT.
 *
 * Lottie has no 3D and no shaders, so the scene is recorded, not redrawn: the same [Universe] runs, the same
 * camera looks at it, and every device panel, link, comet and orbit goes into the file as a flat shape with its
 * place, light and visibility keyed ten times a second. The body is the scene's own shader, rendered as a turn
 * of pictures; the corona likewise. Devices and links loop on a precomp a third (moon) or a half (sun) of the
 * body's turn, crossfading over their last second into their first.
 *
 * Not shipped: the app draws the scene; the recording is for the GPU bench (SwitchBench) only.
 * Run: LOTTIE_OUT=build/lottie ./gradlew :shared:jvmTest --tests '*LottieSceneExport*'
 */
class LottieSceneExport {
    @Test
    fun export() {
        val out = System.getenv("LOTTIE_OUT") ?: return
        val images = runBlocking { loadCosmicImages() } ?: error("no scene images")
        File(out).mkdirs()
        for (on in listOf(false, true)) {
            val json = Exporter(images, on).build()
            val file = File(out, if (on) "sun.json" else "moon.json")
            file.writeText(json.toString())
            println("lottie: ${file.name} ${file.length() / 1024} KB")
        }
    }
}

private const val W = 560f
private const val H = 500f
private const val FPS = 30
private const val SAMPLE_FPS = 10
private const val STEP = FPS / SAMPLE_FPS
private const val SEAM = 1f
/** One dp of Home in the composition's units (Home is about 410 dp wide, the composition 560). */
private const val DP = 1.35f

// ---- JSON ----

private fun n(v: Float, digits: Int = 2): JsonPrimitive {
    val k = 10.0.pow(digits)
    val r = round(v * k) / k
    return if (r == floor(r) && abs(r) < 1e9) JsonPrimitive(r.toLong()) else JsonPrimitive(r)
}
private fun arr(vararg v: Float, digits: Int = 2) = JsonArray(v.map { n(it, digits) })
private fun obj(vararg p: Pair<String, Any?>): JsonObject = JsonObject(p.filter { it.second != null }.associate { (k, v) -> k to json(v) })
private fun json(v: Any?): JsonElement = when (v) {
    is JsonElement -> v
    is Float -> n(v)
    is Double -> n(v.toFloat())
    is Int -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { json(it) })
    else -> error("json: $v")
}
private fun st(vararg v: Float, digits: Int = 2): JsonObject = obj("a" to 0, "k" to (if (v.size == 1) n(v[0], digits) else arr(*v, digits = digits)))

/** A keyed value: samples at frames, kept only where a straight line between kept ones would miss by more than [eps]. */
private class Track(val dims: Int, val eps: Float, val digits: Int = 2, val hold: Boolean = false) {
    val frames = ArrayList<Float>()
    val values = ArrayList<FloatArray>()
    /** A jump into this sample: the value before it holds instead of sliding into it. */
    val jumps = ArrayList<Boolean>()

    fun add(frame: Float, vararg v: Float, jump: Boolean = false) { frames += frame; values += v; jumps += jump }

    val constant: Boolean get() = values.all { v -> v.indices.all { abs(v[it] - values[0][it]) <= eps } }

    fun json(): JsonObject {
        if (values.isEmpty()) error("empty track")
        if (constant) return st(*values[0], digits = digits)
        val keep = ArrayList<Int>()
        if (hold) {
            for (i in values.indices) if (i == 0 || !values[i].contentEquals(values[i - 1])) keep += i
        } else {
            // Straight runs between jumps; the key before a jump holds.
            fun simplify(from: Int, to: Int) {
                keep += from
                var a = from
                while (a < to) {
                    var b = a + 1
                    while (b + 1 <= to && fits(a, b + 1)) b++
                    keep += b
                    a = b
                }
            }
            var start = 0
            for (j in 1 until values.size) if (jumps[j]) { simplify(start, j - 1); start = j }
            simplify(start, values.size - 1)
        }
        val keys = keep.mapIndexed { ki, i ->
            val last = ki == keep.size - 1
            val holdHere = hold || (!last && jumps[keep[ki + 1]])
            val v = values[i]
            val s = if (dims == 1) JsonArray(listOf(n(v[0], digits))) else arr(*v, digits = digits)
            when {
                last -> obj("t" to n(frames[i]), "s" to s)
                holdHere -> obj("t" to n(frames[i]), "s" to s, "h" to 1)
                else -> obj("t" to n(frames[i]), "s" to s, "o" to obj("x" to listOf(0), "y" to listOf(0)), "i" to obj("x" to listOf(1), "y" to listOf(1)))
            }
        }
        return obj("a" to 1, "k" to keys)
    }

    private fun fits(a: Int, b: Int): Boolean {
        val fa = frames[a]; val fb = frames[b]
        for (i in a + 1 until b) {
            val t = (frames[i] - fa) / (fb - fa)
            for (d in 0 until dims) {
                val lin = values[a][d] + (values[b][d] - values[a][d]) * t
                if (abs(lin - values[i][d]) > eps) return false
            }
        }
        return true
    }
}

private fun transform(p: JsonObject = st(0f, 0f), r: JsonObject = st(0f), s: JsonObject = st(100f, 100f), o: JsonObject = st(100f), a: JsonObject = st(0f, 0f)) =
    obj("ty" to "tr", "p" to p, "a" to a, "s" to s, "r" to r, "o" to o, "sk" to st(0f), "sa" to st(0f))
private fun group(name: String, items: List<JsonObject>, tr: JsonObject = transform()) = obj("ty" to "gr", "nm" to name, "it" to items + tr)
private fun rect(w: Float, h: Float, r: Float, x: Float = 0f, y: Float = 0f) = obj("ty" to "rc", "p" to st(x, y), "s" to st(w, h), "r" to st(r), "d" to 1)
private fun ellipse(d: Float, x: Float = 0f, y: Float = 0f) = obj("ty" to "el", "p" to st(x, y), "s" to st(d, d), "d" to 1)
private fun fill(c: Color, o: Float = 100f) = obj("ty" to "fl", "c" to st(c.red, c.green, c.blue, 1f, digits = 3), "o" to st(o), "r" to 1)
private fun fillKeyed(c: JsonObject, o: JsonObject = st(100f)) = obj("ty" to "fl", "c" to c, "o" to o, "r" to 1)
private fun stroke(c: Color, width: Float, o: Float = 100f, dashes: List<JsonObject>? = null) =
    obj("ty" to "st", "c" to st(c.red, c.green, c.blue, 1f, digits = 3), "o" to st(o), "w" to st(width), "lc" to 2, "lj" to 2, "ml" to 4, "d" to dashes)

/** A gradient fill: [stops] are (offset, colour with its alpha); radial from [s] out to [e], or linear. */
private fun gradient(stops: List<Pair<Float, Color>>, s: Offset, e: Offset, radial: Boolean, o: JsonObject = st(100f)): JsonObject {
    val k = ArrayList<Float>()
    for ((off, c) in stops) { k += off; k += c.red; k += c.green; k += c.blue }
    for ((off, c) in stops) { k += off; k += c.alpha }
    return obj(
        "ty" to "gf", "t" to (if (radial) 2 else 1), "o" to o, "r" to 1,
        "s" to st(s.x, s.y), "e" to st(e.x, e.y), "h" to st(0f), "a" to st(0f),
        "g" to obj("p" to stops.size, "k" to st(*k.toFloatArray(), digits = 3)),
    )
}

private fun pathValue(pts: List<Offset>, closed: Boolean = false): JsonObject = obj(
    "c" to closed,
    "v" to pts.map { arr(it.x, it.y, digits = 1) },
    "i" to pts.map { arr(0f, 0f) },
    "o" to pts.map { arr(0f, 0f) },
)

/** Every [step]-th point of a sampled curve as a smooth bezier through them (Catmull-Rom tangents). */
private fun smoothPath(pts: List<Offset>, step: Int): JsonObject {
    val idx = (pts.indices step step).toMutableList().also { if (it.last() != pts.size - 1) it += pts.size - 1 }
    fun d(i: Int): Offset {
        val a = pts[(i - 1).coerceAtLeast(0)]; val b = pts[(i + 1).coerceAtMost(pts.size - 1)]
        val span = ((i + 1).coerceAtMost(pts.size - 1) - (i - 1).coerceAtLeast(0)).toFloat()
        return Offset((b.x - a.x) / span * step / 3f, (b.y - a.y) / span * step / 3f)
    }
    return obj(
        "c" to false,
        "v" to idx.map { arr(pts[it].x, pts[it].y, digits = 1) },
        "i" to idx.map { val t = d(it); arr(-t.x, -t.y, digits = 1) },
        "o" to idx.map { val t = d(it); arr(t.x, t.y, digits = 1) },
    )
}

/** A circle as a closed bezier path, for masks. */
private fun circlePath(c: Offset, r: Float): JsonObject {
    val k = 0.5523f * r
    return obj(
        "c" to true,
        "v" to listOf(arr(c.x, c.y - r), arr(c.x + r, c.y), arr(c.x, c.y + r), arr(c.x - r, c.y)),
        "i" to listOf(arr(-k, 0f), arr(0f, -k), arr(k, 0f), arr(0f, k)),
        "o" to listOf(arr(k, 0f), arr(0f, k), arr(-k, 0f), arr(0f, -k)),
    )
}

private fun shapeLayer(ind: Int, name: String, shapes: List<JsonObject>, ip: Float, op: Float, o: JsonObject = st(100f), masks: List<JsonObject>? = null) = obj(
    "ddd" to 0, "ind" to ind, "ty" to 4, "nm" to name, "sr" to 1, "ip" to n(ip), "op" to n(op), "st" to 0, "bm" to 0,
    "ks" to obj("o" to o, "r" to st(0f), "p" to st(0f, 0f, 0f), "a" to st(0f, 0f, 0f), "s" to st(100f, 100f, 100f)),
    "shapes" to shapes,
    "hasMask" to (if (masks != null) true else null), "masksProperties" to masks,
)

// ---- Two-by-two maps as rotate · scale · rotate, which Lottie's nested groups can carry without skew ----

private class Affine(val c: Offset, val outer: Float, val sx: Float, val sy: Float, val inner: Float)

/** The flat map closest to a projected quad (top-left, top-right, bottom-right, bottom-left) for a [w]×[h] drawing centred at 0. */
private fun affineOf(tl: Offset, tr: Offset, br: Offset, bl: Offset, w: Float, h: Float, prev: Affine?): Affine {
    val exx = ((tr.x - tl.x) + (br.x - bl.x)) / 2 / w; val exy = ((tr.y - tl.y) + (br.y - bl.y)) / 2 / w
    val eyx = ((bl.x - tl.x) + (br.x - tr.x)) / 2 / h; val eyy = ((bl.y - tl.y) + (br.y - tr.y)) / 2 / h
    val a = exx; val b = eyx; val c = exy; val d = eyy
    val e = (a + d) / 2; val f = (a - d) / 2; val g = (c + b) / 2; val hh = (c - b) / 2
    val q = sqrt(e * e + hh * hh); val r = sqrt(f * f + g * g)
    val sx = q + r; val sy = q - r
    val a1 = atan2(g, f); val a2 = atan2(hh, e)
    var inner = ((a2 - a1) / 2) * 180f / PI.toFloat()
    var outer = ((a2 + a1) / 2) * 180f / PI.toFloat()
    if (prev != null) {
        // The same map turns up with either angle a half-turn on, if both are; keep each near where it was.
        var best = Float.MAX_VALUE; var bo = outer; var bi = inner
        for (ko in -8..8) for (ki in -8..8) {
            if ((ko + ki) % 2 != 0) continue
            val o2 = outer + ko * 180f; val i2 = inner + ki * 180f
            val cost = abs(o2 - prev.outer) + abs(i2 - prev.inner)
            if (cost < best) { best = cost; bo = o2; bi = i2 }
        }
        outer = bo; inner = bi
    }
    val center = Offset((tl.x + tr.x + br.x + bl.x) / 4, (tl.y + tr.y + br.y + bl.y) / 4)
    return Affine(center, outer, sx, sy, inner)
}

/** Tracks for one flat thing placed by an [Affine] each sample; [wrap] puts content into the three nested groups. */
private class Placement {
    val p = Track(2, 0.2f, 1)
    val outer = Track(1, 0.25f, 1)
    val s = Track(2, 0.25f, 1)
    val inner = Track(1, 0.25f, 1)
    var last: Affine? = null

    fun add(frame: Float, a: Affine) {
        p.add(frame, a.c.x, a.c.y); outer.add(frame, a.outer); s.add(frame, a.sx * 100, a.sy * 100); inner.add(frame, a.inner)
        last = a
    }

    fun wrap(name: String, content: List<JsonObject>, opacity: JsonObject): JsonObject =
        group(name, listOf(group("scale", listOf(group("turn", content, transform(r = inner.json()))), transform(s = s.json()))), transform(p = p.json(), r = outer.json(), o = opacity))
}

// ---- The recording ----

private class PanelSnap(val tl: Offset, val tr: Offset, val br: Offset, val bl: Offset, val visible: Boolean, val color: Color, val depth: Float)
private class DeviceSnap(val panels: List<PanelSnap>, val behind: Boolean, val depth: Float, val power: Float, val flash: Float)
private class LinkSnap(val link: Link, val curve: List<Offset>, val drawn: Float, val gone: Float, val heads: List<Float>)
private class Sample(val t: Float, val devices: List<DeviceSnap>, val links: List<LinkSnap>)

private class Exporter(private val images: CosmicImages, private val on: Boolean) {
    private val cam = Camera().also { aimCamera(it, Size(W, H), 0f, reduced = true) }
    private val center = cam.project(V3(0f, 0f, 0f))
    private val radius = bodyRadiusPx(cam)
    private val bodyDepth = cam.depth(V3(0f, 0f, 0f))
    private val heat = if (on) 1f else 0f
    /** The body's turn: [Universe.update] spins it 0.1 + 0.06 × heat radians a second. */
    private val period = (2 * PI / (0.1 + 0.06 * heat)).toFloat()
    private val loop = period / (if (on) 2 else 3)
    private val total = round(period * FPS)
    private val loopFrames = round(loop * FPS)

    fun build(): JsonObject {
        val assets = ArrayList<JsonObject>()
        val samples = record()
        assets += obj("id" to "devices", "w" to W.toInt(), "h" to H.toInt(), "fr" to FPS, "layers" to deviceComp(samples))
        val (bodyLayers, bodyImages) = bodyComp()
        assets += bodyImages
        assets += obj("id" to "body", "w" to W.toInt(), "h" to H.toInt(), "fr" to FPS, "layers" to bodyLayers)
        val (coronaLayers, coronaImages) = coronaComp()
        assets += coronaImages
        assets += obj("id" to "corona", "w" to W.toInt(), "h" to H.toInt(), "fr" to FPS, "layers" to coronaLayers)

        val layers = ArrayList<JsonObject>()
        var ind = 1
        layers += precompLayer(ind++, "devices", timeRemap())
        if (on) layers += orbitLayer(ind++, near = true)
        layers += precompLayer(ind++, "body", null, masks = listOf(obj("inv" to false, "mode" to "a", "o" to st(100f), "x" to st(0f), "pt" to obj("a" to 0, "k" to circlePath(center, radius - 0.6f)))))
        layers += precompLayer(ind++, "corona", null)
        if (on) layers += orbitLayer(ind++, near = false)
        return obj(
            "v" to "5.7.4", "fr" to FPS, "ip" to 0, "op" to n(total), "w" to W.toInt(), "h" to H.toInt(),
            "nm" to if (on) "sun" else "moon", "ddd" to 0, "assets" to assets, "layers" to layers,
        )
    }

    private fun precompLayer(ind: Int, ref: String, tm: JsonObject?, masks: List<JsonObject>? = null) = obj(
        "ddd" to 0, "ind" to ind, "ty" to 0, "nm" to ref, "refId" to ref, "sr" to 1, "ip" to 0, "op" to n(total), "st" to 0, "bm" to 0,
        "w" to W.toInt(), "h" to H.toInt(), "tm" to tm,
        "ks" to obj("o" to st(100f), "r" to st(0f), "p" to st(0f, 0f, 0f), "a" to st(0f, 0f, 0f), "s" to st(100f, 100f, 100f)),
        "hasMask" to (if (masks != null) true else null), "masksProperties" to masks,
    )

    /** The devices' precomp runs [loop] seconds and starts again, so many times in the body's turn. */
    private fun timeRemap(): JsonObject {
        val keys = ArrayList<JsonObject>()
        val times = (total / loopFrames).toInt()
        for (k in 0 until times) {
            val f0 = k * loopFrames
            val f1 = (k + 1) * loopFrames - 0.05f
            keys += obj("t" to n(f0), "s" to listOf(0), "o" to obj("x" to listOf(0), "y" to listOf(0)), "i" to obj("x" to listOf(1), "y" to listOf(1)))
            keys += obj("t" to n(f1), "s" to listOf(n(loop - 0.05f / FPS, 3)), "h" to 1)
        }
        keys += obj("t" to n(total), "s" to listOf(0))
        return obj("a" to 1, "k" to keys)
    }

    // ---- Recording the universe ----

    private fun record(): List<Sample> {
        val u = Universe()
        u.jump(if (on) ConnectLook.On else ConnectLook.Off, cam)
        val dt = 1f / FPS
        // Let the network grow and the comets get going before the loop starts.
        repeat(FPS * 6) { u.update(dt, cam, false) }
        val count = round((loop + SEAM) * SAMPLE_FPS).toInt()
        val out = ArrayList<Sample>()
        for (i in 0..count) {
            out += snap(u, i.toFloat() / SAMPLE_FPS)
            repeat(STEP) { u.update(dt, cam, false) }
        }
        return out
    }

    private val tmp = V3()
    private val tmp2 = V3()
    private val world = Array(4) { V3() }

    private fun snap(u: Universe, t: Float): Sample {
        val devices = u.devices.map { d ->
            val panels = d.panels.map { panel ->
                for (k in 0..3) {
                    d.rotation.apply(panel.corners[k], world[k])
                    world[k].set(world[k].x + d.position.x, world[k].y + d.position.y, world[k].z + d.position.z)
                }
                d.rotation.apply(panel.normal, tmp2)
                tmp.set((world[0].x + world[2].x) / 2, (world[0].y + world[2].y) / 2, (world[0].z + world[2].z) / 2)
                val visible = cam.faces(tmp, tmp2)
                val depth = cam.depth(tmp)
                val color = lit(panel, tmp, tmp2, heat)
                // Top-left is the fourth corner, as the scene draws its panels.
                PanelSnap(cam.project(world[3]), cam.project(world[2]), cam.project(world[1]), cam.project(world[0]), visible, color, depth)
            }
            DeviceSnap(panels, d.depth > bodyDepth, d.depth, d.power.value, d.flash)
        }
        val links = u.links.mapNotNull { l ->
            val tt = u.time
            if (tt < l.born) return@mapNotNull null
            val drawn = easeOut(((tt - l.born) / l.drawTime).coerceIn(0f, 1f))
            val gone = if (l.retireAt == Float.POSITIVE_INFINITY) 0f else easeOut(((tt - l.retireAt) / Universe.RETIRE_TIME).coerceIn(0f, 1f))
            if (gone >= 1f) return@mapNotNull null
            val heads = if (drawn >= 1f && gone == 0f) (0 until l.count).map { k -> u.pulsePhase(l, k).let { if (l.dir > 0) it else 1 - it } } else emptyList()
            LinkSnap(l, curve(u, l), drawn, gone, heads)
        }
        return Sample(t, devices, links)
    }

    /** The scene's light on a panel (CosmicRenderer.lit): the body's light, a cold rim, a little shine on metal. */
    private fun lit(panel: Panel, c: V3, normal: V3, h: Float): Color {
        val dist = c.length().coerceAtLeast(0.5f)
        val lx = -c.x / dist; val ly = -c.y / dist; val lz = -c.z / dist
        val diffuse = max(0f, normal.x * lx + normal.y * ly + normal.z * lz)
        val near = (10f / (dist * dist)).coerceIn(0.35f, 1.4f)
        val light = lerp(Color(0.62f, 0.74f, 1f), Color(1f, 0.8f, 0.55f), h)
        val strength = (0.8f + 0.5f * h) * near
        val rim = max(0f, normal.x * -0.55f + normal.y * 0.73f + normal.z * -0.37f) * 0.5f * (1f - 0.5f * h)
        var vx = cam.eye.x - c.x; var vy = cam.eye.y - c.y; var vz = cam.eye.z - c.z
        val vl = sqrt(vx * vx + vy * vy + vz * vz); vx /= vl; vy /= vl; vz /= vl
        var hx = lx + vx; var hy = ly + vy; var hz = lz + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f); hx /= hl; hy /= hl; hz /= hl
        val spec = max(0f, normal.x * hx + normal.y * hy + normal.z * hz).pow(26f) * panel.material.metal * 0.55f * strength
        val base = panel.material.color
        val amb = 0.2f
        return Color(
            (base.red * (amb + diffuse * strength * light.red + rim * 0.6f) + spec * light.red).coerceIn(0f, 1f),
            (base.green * (amb + diffuse * strength * light.green + rim * 0.7f) + spec * light.green).coerceIn(0f, 1f),
            (base.blue * (amb + diffuse * strength * light.blue + rim) + spec * light.blue).coerceIn(0f, 1f),
        )
    }

    /** A link's bend, as CosmicRenderer.links draws it, projected: 13 points from its start to its end. */
    private fun curve(u: Universe, l: Link): List<Offset> {
        val a = u.devices[l.a].anchor
        val b = if (l.b == -1) {
            val len = a.length()
            V3(a.x / len * BODY_RADIUS * 0.98f, a.y / len * BODY_RADIUS * 0.98f, a.z / len * BODY_RADIUS * 0.98f)
        } else u.devices[l.b].anchor
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
        return (0..12).map { j ->
            val s = j / 12f
            val w = 1 - s
            cam.project(tmp.set(w * w * a.x + 2 * w * s * cx + s * s * b.x, w * w * a.y + 2 * w * s * cy + s * s * b.y, w * w * a.z + 2 * w * s * cz + s * s * b.z))
        }
    }

    // ---- The devices' precomp ----

    private fun deviceComp(samples: List<Sample>): List<JsonObject> {
        val seamSamples = round(SEAM * SAMPLE_FPS).toInt()
        val main = samples.subList(seamSamples, samples.size)
        val head = samples.subList(0, seamSamples + 1)
        val fadeAt = loopFrames - SEAM * FPS
        val layers = ArrayList<JsonObject>()
        var ind = 1
        // The loop's last second: the first second again, fading in over what fades out.
        val inKeys = Track(1, 0f).apply { add(fadeAt, 0f); add(loopFrames, 100f) }.json()
        val outKeys = Track(1, 0f).apply { add(0f, 100f); add(fadeAt, 100f); add(loopFrames, 0f) }.json()
        for ((part, offset, opacity) in listOf(Triple(head, fadeAt, inKeys), Triple(main, 0f, outKeys))) {
            val order = samples[0].devices.indices.sortedBy { i -> part.map { it.devices[i].depth }.average() }
            val near = order.filter { i -> part.map { it.devices[i].depth }.average() <= bodyDepth }
            val far = order - near.toSet()
            for (i in near) layers += deviceLayer(ind++, i, part, offset, opacity)
            if (on) layers += linkLayers(part, offset, opacity).map { it.second(ind++) }
            for (i in far) layers += deviceLayer(ind++, i, part, offset, opacity)
        }
        return layers
    }

    private fun frameOf(s: Sample, part: List<Sample>, offset: Float) = offset + (s.t - part[0].t) * FPS

    private fun deviceLayer(ind: Int, index: Int, part: List<Sample>, offset: Float, opacity: JsonObject): JsonObject {
        val model = Universe().devices[index]
        val panelOrder = model.panels.indices.sortedBy { k -> part.map { it.devices[index].panels[k].depth }.average() }
        val shapes = ArrayList<JsonObject>()
        var halo: JsonObject? = null
        for (k in panelOrder) {
            val panel = model.panels[k]
            val w = 100f * panel.aspect
            val h = 100f
            val place = Placement()
            val vis = Track(1, 0f, hold = true)
            val color = Track(3, 0.012f, 3)
            for (s in part) {
                val p = s.devices[index].panels[k]
                val f = frameOf(s, part, offset)
                place.add(f, affineOf(p.tl, p.tr, p.br, p.bl, w, h, place.last))
                vis.add(f, if (p.visible) 100f else 0f)
                color.add(f, p.color.red, p.color.green, p.color.blue)
            }
            if (vis.values.all { it[0] == 0f }) continue
            val r = panel.round * min(w, h)
            val content = ArrayList<JsonObject>()
            when (panel.face) {
                Face.Screen -> content += screen(model, w, h, r, part, index, offset)
                Face.Keys -> content += keys(w, h)
                else -> Unit
            }
            content += group("sheen", listOf(rect(w, h, r), gradient(listOf(0f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = 0.07f + 0.05f * heat), 1f to Color.White.copy(alpha = 0f)), Offset(-w / 2, -h / 2), Offset(w / 2, h / 2), radial = false)))
            content += group("face", listOf(rect(w, h, r), fillKeyed(addAlpha(color.json()))))
            shapes += place.wrap("panel$k", content, vis.json())
            if (panel.face == Face.Screen && on) halo = place.wrap("halo", haloShapes(model, w, h), vis.json())
        }
        halo?.let { shapes += it }
        // Behind the body: the body's disc is cut out of it.
        val behind = Track(1, 0f, hold = true)
        for (s in part) behind.add(frameOf(s, part, offset), if (s.devices[index].behind) 100f else 0f)
        val masks = if (behind.values.any { it[0] > 0f }) listOf(
            obj("inv" to false, "mode" to "s", "o" to behind.json(), "x" to st(0f), "pt" to obj("a" to 0, "k" to circlePath(center, radius))),
        ) else null
        val end = frameOf(part.last(), part, offset)
        return shapeLayer(ind, "device$index ${model.kind}", shapes, offset, end + 1, opacity, masks)
    }

    /** A colour track's keys with the alpha Lottie wants on each. */
    private fun addAlpha(c: JsonObject): JsonObject {
        val k = c["k"]!!
        if (c["a"].toString() == "0") return obj("a" to 0, "k" to JsonArray((k as JsonArray) + n(1f)))
        return obj("a" to 1, "k" to JsonArray((k as JsonArray).map { key ->
            key as JsonObject
            JsonObject(key + ("s" to JsonArray((key["s"] as JsonArray) + n(1f))))
        }))
    }

    /** The glass, and once the device is awake the OpenFlux screen (CosmicRenderer.ui), in the panel's own frame. */
    private fun screen(d: Device, w: Float, h: Float, r: Float, part: List<Sample>, index: Int, offset: Float): List<JsonObject> {
        val m = 0.035f * min(w, h)
        val sw = w - 2 * m; val sh = h - 2 * m
        val rr = (r - m).coerceAtLeast(1f)
        val out = ArrayList<JsonObject>()
        val glass = listOf(
            group("sheen", listOf(rect(sw, sh, rr), gradient(
                listOf(0.35f to Color.Transparent, 0.5f to lerp(Color(0.5f, 0.6f, 0.8f), Color(1f, 0.75f, 0.45f), heat).copy(alpha = 0.06f + 0.08f * heat), 0.62f to Color.Transparent),
                Offset(-sw / 2, -sh / 2), Offset(sw / 2, sh / 2), radial = false,
            ))),
            group("black", listOf(rect(sw, sh, rr), fill(Color(0xFF030408)))),
        )
        if (on) {
            val flash = Track(1, 1.5f)
            for (s in part) flash.add(frameOf(s, part, offset), s.devices[index].flash * 30f)
            out += group("flash", listOf(rect(sw, sh, rr), fillKeyed(st(d.tint.red, d.tint.green, d.tint.blue, 1f, digits = 3), flash.json())))
            out += ui(d, sw, sh, rr)
        }
        out += group("glass", glass)
        return out
    }

    private fun ui(d: Device, sw: Float, sh: Float, rr: Float): List<JsonObject> {
        val tint = d.tint
        val wide = sw > sh * 1.2f
        val unit = sh
        val ox = -sw / 2; val oy = -sh / 2
        val items = ArrayList<JsonObject>()
        val logo = if (wide) Offset(ox + unit * 0.22f, oy + unit * 0.28f) else Offset(0f, oy + unit * 0.24f)
        val lr = unit * 0.075f
        // The wave of traffic along the bottom.
        val wy = oy + unit * 0.84f
        val left = ox + sw * 0.08f; val right = ox + sw * 0.92f
        val wave = (0..24).map { k ->
            val x = left + (right - left) * k / 24f
            val cx = (x - ox) / unit
            Offset(x, wy + unit * 0.045f * sin(cx * 15f + d.seed * 5f) * sin(cx * 3.3f))
        }
        val waveShape = obj("ty" to "sh", "ks" to obj("a" to 0, "k" to pathValue(wave)))
        items += group("wave", listOf(waveShape, stroke(tint, unit * 0.014f, 95f)))
        items += group("wave glow", listOf(waveShape, stroke(tint, unit * 0.05f, 20f)))
        // Rows of light, shimmering.
        val x0 = if (wide) ox + unit * 0.44f else ox + sw * 0.1f
        val room = if (wide) sw - unit * 0.56f else sw * 0.8f
        for (i in 0 until 4) {
            val y = if (wide) oy + unit * (0.2f + i * 0.13f) else oy + unit * (0.44f + i * 0.1f)
            val hsh = (sin(d.seed * 12.9898f + i * 78.233f) * 43758.547f).let { it - floor(it) }
            val rw = room * (0.35f + 0.6f * hsh)
            val shimmer = Track(1, 1f)
            // About 1.7 radians a second, so many times in the loop that it closes on itself.
            val period = loop / round(loop * 1.7f / (2 * PI.toFloat())).coerceAtLeast(1f)
            val steps = 8
            for (k in 0..steps) {
                val t = k * period / steps
                shimmer.add(t * FPS, (0.18f + 0.22f * (0.5f + 0.5f * sin(t * 2 * PI.toFloat() / period + i * 1.3f + d.seed * 9f))) * 100f)
            }
            val c = lerp(tint, Color.White, 0.25f)
            items += group("row$i", listOf(rect(rw, unit * 0.045f, unit * 0.022f, x0 + rw / 2, y + unit * 0.0225f), fillKeyed(st(c.red, c.green, c.blue, 1f, digits = 3), loopTrack(shimmer, period))))
        }
        // The mark: a glow, a ring, a turning arc, a dot.
        items += group("dot", listOf(ellipse(unit * 0.036f, logo.x, logo.y), fill(Color.White)))
        val arcTurn = obj("a" to 1, "k" to listOf(
            obj("t" to 0, "s" to listOf(n(d.seed * 60f)), "o" to obj("x" to listOf(0), "y" to listOf(0)), "i" to obj("x" to listOf(1), "y" to listOf(1))),
            obj("t" to n(loopFrames), "s" to listOf(n(d.seed * 60f + 360f * round(140f * loop / 360f)))),
        ))
        items += group("arc", listOf(
            ellipse(lr * 2),
            obj("ty" to "tm", "s" to st(0f), "e" to st(70f / 360f * 100f), "o" to st(0f), "m" to 1),
            stroke(Color.White, unit * 0.016f, 90f),
        ), transform(p = st(logo.x, logo.y), r = arcTurn))
        items += group("ring", listOf(ellipse(lr * 2, logo.x, logo.y), stroke(tint, unit * 0.012f, 85f)))
        items += group("logo glow", listOf(ellipse(lr * 4.4f, logo.x, logo.y), fill(tint, 18f)))
        items += group("screen", listOf(
            rect(sw, sh, rr),
            gradient(listOf(0f to Color(0.01f, 0.07f, 0.15f), 1f to Color(0.0f, 0.02f, 0.06f)), Offset(0f, oy), Offset(0f, oy + sh), radial = false),
        ))
        return items
    }

    /** A track that repeats every [period] seconds for the whole composition. */
    private fun loopTrack(one: Track, period: Float): JsonObject {
        val t = Track(1, 0f)
        var base = 0f
        while ((base + period) * FPS <= loopFrames + 0.5f) {
            for (i in 0 until one.frames.size - 1) t.add(base * FPS + one.frames[i], one.values[i][0])
            base += period
        }
        t.add(loopFrames, one.values.last()[0])
        return t.json()
    }

    private fun haloShapes(d: Device, w: Float, h: Float): List<JsonObject> =
        listOf(16f to 0.05f, 9f to 0.08f, 4f to 0.12f).reversed().map { (grow, alpha) ->
            group("glow$grow", listOf(rect(w + 2 * grow, h + 2 * grow, grow + 6f), fill(d.tint, alpha * 100f)))
        }

    private fun keys(w: Float, h: Float): List<JsonObject> {
        val items = ArrayList<JsonObject>()
        val left = w * 0.06f - w / 2
        val top = h * 0.08f - h / 2
        val rows = intArrayOf(14, 14, 13, 12, 11)
        val rowH = h * 0.085f
        for ((ri, cnt) in rows.withIndex()) {
            val gap = w * 0.008f
            val kw = (w * 0.88f - gap * (cnt - 1)) / cnt
            for (k in 0 until cnt) items += rect(kw, rowH, rowH * 0.2f, left + k * (kw + gap) + kw / 2, top + ri * (rowH + gap) + rowH / 2)
        }
        items += fill(Color(0xFF14171D))
        val pad = rect(w * 0.32f, h * 0.3f, h * 0.04f, w * 0.5f - w / 2, h * 0.77f - h / 2)
        return listOf(group("keys", items), group("pad", listOf(pad, fill(Color(0xFF111318)))))
    }

    // ---- Links and their traffic ----

    private fun linkLayers(part: List<Sample>, offset: Float, opacity: JsonObject): List<Pair<Link, (Int) -> JsonObject>> {
        val all = LinkedHashMap<Link, MutableList<Pair<Sample, LinkSnap>>>()
        for (s in part) for (l in s.links) all.getOrPut(l.link) { ArrayList() } += s to l
        return all.map { (link, snaps) ->
            link to { ind: Int ->
                val base = if (link.gold) Color(1f, 0.72f, 0.32f) else Color(0.25f, 0.78f, 1f)
                val shapeKeys = ArrayList<JsonObject>()
                val trimS = Track(1, 0.3f, 1)
                val trimE = Track(1, 0.3f, 1)
                for ((idx, sl) in snaps.withIndex()) {
                    val (s, l) = sl
                    val f = frameOf(s, part, offset)
                    trimS.add(f, l.gone * 100f); trimE.add(f, l.drawn * 100f)
                    // The line itself moves slowly: five keys a second, nine points, are enough.
                    if (idx % 2 != 0 && idx != snaps.size - 1) continue
                    shapeKeys += obj("t" to n(f), "s" to listOf(smoothPath(l.curve, 3)), "o" to obj("x" to listOf(0), "y" to listOf(0)), "i" to obj("x" to listOf(1), "y" to listOf(1)))
                }
                val path = obj("ty" to "sh", "ks" to if (shapeKeys.size == 1) obj("a" to 0, "k" to shapeKeys[0]["s"]!!.let { (it as JsonArray)[0] }) else obj("a" to 1, "k" to shapeKeys))
                val trim = obj("ty" to "tm", "s" to trimS.json(), "e" to trimE.json(), "o" to st(0f), "m" to 1)
                val shapes = ArrayList<JsonObject>()
                // Comets: a bright head with a glow and a short tail of fading dots.
                val maxHeads = link.count
                for (k in 0 until maxHeads) {
                    for (j in listOf(0, 3, 6)) {
                        val pos = Track(2, 0.4f, 1)
                        val vis = Track(1, 0f, hold = true)
                        for ((s, l) in snaps) {
                            val f = frameOf(s, part, offset)
                            val head = l.heads.getOrNull(k)
                            val at = if (head == null) null else (head - link.dir * j * 0.011f)
                            val shown = at != null && at in 0f..1f
                            val p = at(l.curve, (at ?: 0f).coerceIn(0f, 1f))
                            val prev = pos.values.lastOrNull()
                            val jump = prev != null && (abs(prev[0] - p.x) + abs(prev[1] - p.y)) > 30f
                            pos.add(f, p.x, p.y, jump = jump)
                            vis.add(f, if (shown) 100f else 0f)
                        }
                        if (vis.values.all { it[0] == 0f }) continue
                        val items = if (j == 0) listOf(
                            group("core", listOf(ellipse(7f * DP * 0.56f), fill(Color.White, 95f))),
                            group("glow", listOf(ellipse(7f * DP * 4.4f), gradient(listOf(0f to lerp(base, Color.White, 0.55f).copy(alpha = 0.55f), 0.5f to lerp(base, Color.White, 0.55f).copy(alpha = 0.12f), 1f to Color.Transparent), Offset.Zero, Offset(7f * DP * 2.2f, 0f), radial = true))),
                        ) else {
                            val fr = 1f - j / 10f
                            listOf(ellipse((0.6f + 1.6f * fr) * DP * 2), fill(lerp(base, Color.White, 0.3f), fr.pow(1.5f) * 75f))
                        }
                        shapes += group("comet$k-$j", items, transform(p = pos.json(), o = vis.json()))
                    }
                }
                shapes += group("line", listOf(path, trim, stroke(base, 1.4f * DP, 60f)))
                shapes += group("line glow", listOf(path, trim, stroke(base, 6f * DP, 9f)))
                val ip = frameOf(snaps.first().first, part, offset)
                val op = frameOf(snaps.last().first, part, offset) + STEP
                shapeLayer(ind, "link ${link.key}", shapes, ip, op, opacity)
            }
        }
    }

    private fun at(curve: List<Offset>, s: Float): Offset {
        val nn = curve.size - 1
        val x = s * nn
        val i = min(x.toInt(), nn - 1)
        val f = x - i
        val a = curve[i]; val b = curve[i + 1]
        return Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
    }

    // ---- Orbits: still, as the camera is; light runs along their dashes ----

    private fun orbitLayer(ind: Int, near: Boolean): JsonObject {
        val shapes = ArrayList<JsonObject>()
        val u = Universe()
        for ((ri, ring) in u.rings.withIndex()) {
            var run = ArrayList<Offset>()
            val runs = ArrayList<List<Offset>>()
            for (k in 0..120) {
                val a = k / 120f * 2 * PI.toFloat()
                tmp.set(cos(a) * ring.radius, 0f, sin(a) * ring.radius)
                ring.tilt.apply(tmp, tmp2)
                val isNear = cam.depth(tmp2) <= bodyDepth
                if (isNear == near) run += cam.project(tmp2) else { if (run.size > 1) runs += run; run = ArrayList() }
            }
            if (run.size > 1) runs += run
            val dashOffset = obj("a" to 1, "k" to listOf(
                obj("t" to 0, "s" to listOf(0), "o" to obj("x" to listOf(0), "y" to listOf(0)), "i" to obj("x" to listOf(1), "y" to listOf(1))),
                obj("t" to n(total), "s" to listOf(n(-9f * DP * round(14f * total / FPS / 9f)))),
            ))
            val dashes = listOf(
                obj("n" to "d", "nm" to "dash", "v" to st(2f * DP)),
                obj("n" to "g", "nm" to "gap", "v" to st(7f * DP)),
                obj("n" to "o", "nm" to "offset", "v" to dashOffset),
            )
            val paths = runs.map { obj("ty" to "sh", "ks" to obj("a" to 0, "k" to pathValue(it))) }
            shapes += group("ring$ri", paths + stroke(Color(0.45f, 0.78f, 1f), 1f * DP, 22f, dashes))
        }
        return shapeLayer(ind, if (near) "orbits near" else "orbits far", shapes, 0f, total)
    }

    // ---- The body and its corona: the scene's own shaders, rendered ----

    private fun encode(bmp: ImageBitmap, png: Boolean): String {
        val img = org.jetbrains.skia.Image.makeFromBitmap(bmp.asSkiaBitmap())
        val data = img.encodeToData(if (png) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, 84)!!.bytes
        return (if (png) "data:image/png;base64," else "data:image/jpeg;base64,") + Base64.getEncoder().encodeToString(data)
    }

    private fun draw(size: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
        val bmp = ImageBitmap(size, size)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(size.toFloat(), size.toFloat()), block)
        return bmp
    }

    /** A turn of the body as pictures: each holds while the next fades in over it. */
    private fun bodyComp(): Pair<List<JsonObject>, List<JsonObject>> {
        val count = 120
        val scale = 1.7f
        val r = radius * scale
        val size = (2 * (r + 3)).toInt()
        val shader = SceneShader(BODY_SHADER)
        val frame = M3()
        val rows = M3()
        val assets = ArrayList<JsonObject>()
        val layers = ArrayList<JsonObject>()
        val each = total / count
        for (i in 0..count) {
            val k = i % count
            val spin = -1.2f + k * 2 * PI.toFloat() / count
            val t = k * period / count
            if (i < count) {
                M3.rz(0.12f).times(M3.rx(0.18f), frame).times(M3.ry(spin), frame)
                cam.viewRows(rows).times(frame, rows).transpose(rows)
                shader.uniform("uCenter", size / 2f, size / 2f)
                shader.uniform("uRadius", r)
                shader.uniform("uRow0", rows.a, rows.b, rows.c)
                shader.uniform("uRow1", rows.d, rows.e, rows.f)
                shader.uniform("uRow2", rows.g, rows.h, rows.i)
                shader.uniform("uTime", 30f + t)
                shader.uniform("uHeat", heat)
                shader.uniform("uHover", 0f)
                shader.uniform("uRed", 0f)
                shader.uniform("uMoonSize", images.moon.width.toFloat(), images.moon.height.toFloat())
                shader.uniform("uHeightSize", images.height.width.toFloat(), images.height.height.toFloat())
                shader.uniform("uSunSize", images.sun.width.toFloat(), images.sun.height.toFloat())
                shader.image("uMoon", images.moon)
                shader.image("uHeight", images.height)
                shader.image("uSun", images.sun)
                val brush = shader.brush()
                val bmp = draw(size) {
                    drawRect(Color.Black)
                    drawCircle(brush, r + 2f, Offset(size / 2f, size / 2f))
                }
                assets += obj("id" to "b$k", "w" to size, "h" to size, "u" to "", "p" to encode(bmp, png = false), "e" to 1)
            }
            // Picture i shows from (i-1)·each, fading in, and holds till (i+1)·each; the last again is the first.
            val f0 = (i - 1) * each
            val fade = if (i == 0) st(100f) else Track(1, 0f).apply { add(f0, 0f); add(f0 + each, 100f) }.json()
            layers += obj(
                "ddd" to 0, "ind" to i + 1, "ty" to 2, "nm" to "body$i", "refId" to "b$k", "sr" to 1,
                "ip" to n(max(0f, f0)), "op" to n(min(total, (i + 1) * each + 1)), "st" to 0, "bm" to 0,
                "ks" to obj("o" to fade, "r" to st(0f), "p" to st(center.x, center.y, 0f), "a" to st(size / 2f, size / 2f, 0f), "s" to st(100f / scale, 100f / scale, 100f)),
            )
        }
        return layers.reversed() to assets
    }

    /** The corona as a few pictures crossfading over the turn (one for the Moon's still halo). */
    private fun coronaComp(): Pair<List<JsonObject>, List<JsonObject>> {
        val count = if (on) 8 else 1
        val scale = 0.8f
        val span = radius * (if (on) 3.6f else 2.6f)
        val size = (2 * span * scale).toInt()
        val shader = SceneShader(CORONA_SHADER)
        val u = Universe()
        u.jump(if (on) ConnectLook.On else ConnectLook.Off, cam)
        val assets = ArrayList<JsonObject>()
        val layers = ArrayList<JsonObject>()
        val each = total / count
        val dt = 1f / FPS
        for (i in 0..count) {
            val k = i % count
            if (i < count) {
                if (k > 0) repeat((each).toInt()) { u.update(dt, cam, false) }
                shader.uniform("uCenter", size / 2f, size / 2f)
                shader.uniform("uRadius", radius * scale)
                shader.uniform("uTime", u.time)
                shader.uniform("uHeat", heat)
                shader.uniform("uHover", 0f)
                shader.uniform("uRed", 0f)
                val l = u.loops
                fun life(j: Int): Float {
                    val age = (u.time - l[j].born) / l[j].life
                    return if (age < 0f || age > 1f) 0f else sin(PI.toFloat() * age) * heat
                }
                fun height(j: Int): Float = l[j].height * (0.55f + 0.45f * ((u.time - l[j].born) / l[j].life * 2.2f).coerceIn(0f, 1f))
                shader.uniform("uLoopAngle", l[0].angle, l[1].angle, l[2].angle, l[3].angle)
                shader.uniform("uLoopWidth", l[0].width, l[1].width, l[2].width, l[3].width)
                shader.uniform("uLoopHeight", height(0), height(1), height(2), height(3))
                shader.uniform("uLoopLife", life(0), life(1), life(2), life(3))
                val brush = shader.brush()
                val bmp = draw(size) { drawCircle(brush, span * scale, Offset(size / 2f, size / 2f)) }
                assets += obj("id" to "c$k", "w" to size, "h" to size, "u" to "", "p" to encode(bmp, png = true), "e" to 1)
            }
            if (count == 1 && i == 1) break
            val f0 = (i - 1) * each
            val fade = if (i == 0) st(100f) else Track(1, 0f).apply { add(f0, 0f); add(f0 + each, 100f) }.json()
            layers += obj(
                "ddd" to 0, "ind" to i + 1, "ty" to 2, "nm" to "corona$i", "refId" to "c$k", "sr" to 1,
                "ip" to n(max(0f, f0)), "op" to n(if (count == 1) total else min(total, (i + 1) * each + 1)), "st" to 0, "bm" to 0,
                "ks" to obj("o" to fade, "r" to st(0f), "p" to st(center.x, center.y, 0f), "a" to st(size / 2f, size / 2f, 0f), "s" to st(100f / scale, 100f / scale, 100f)),
            )
        }
        return layers.reversed() to assets
    }
}
