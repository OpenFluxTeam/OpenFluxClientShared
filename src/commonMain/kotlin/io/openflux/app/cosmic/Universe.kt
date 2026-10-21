package io.openflux.app.cosmic

import androidx.compose.ui.graphics.Color
import io.openflux.app.components.ConnectLook
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

// Everything in the scene that moves, without drawing it: the body's heat, the devices, their orbits, the
// links and their traffic, the prominences. One [update] per frame; [show] turns the scene to a state of the
// connection, and each part follows on its own ramp, so any change can turn back half-way without a jump.

const val BODY_RADIUS = 1.5f

enum class DeviceKind { Phone, Tablet, Laptop, Monitor }

enum class Face { Body, Screen, Keys, Dark }

/** A flat rectangle of a device model, in the device's own frame: corners counter-clockwise seen from outside. */
class Panel(val corners: Array<V3>, val normal: V3, val face: Face, val material: Material, val round: Float, val aspect: Float, val inset: Float = 0f)

enum class Material(val color: Color, val metal: Float) {
    Silver(Color(0.58f, 0.62f, 0.68f), 0.75f),
    Graphite(Color(0.16f, 0.18f, 0.22f), 0.7f),
    Bezel(Color(0.03f, 0.035f, 0.045f), 0.3f),
}

class Ring(val radius: Float, val speed: Float, val tilt: M3)

class Device(
    val kind: DeviceKind,
    val panels: List<Panel>,
    /** The screen's centre, slightly in front of it: where links attach. */
    val anchorLocal: V3,
    val tint: Color,
    val seed: Float,
    val home: V3,
    val waves: FloatArray,
    val spin: V3,
    val tilt0: V3,
    val ring: Int,
    val phase: Float,
    val bob: Float,
    val lean: Float,
    val faceTilt: Float,
) {
    val power = Ramp(0f)
    val order = Ramp(0f)
    var flash = 0f
    val position = V3()
    val rotation = M3()
    val anchor = V3()
    var depth = 0f
}

class Link(
    val a: Int,
    /** -1: a spoke into the Sun. */
    val b: Int,
    val born: Float,
    val drawTime: Float,
    val dir: Int,
    val seed: Float,
    val speed: Float,
    val count: Int,
    val bend: Float,
) {
    var retireAt = Float.POSITIVE_INFINITY
    val phases = FloatArray(3) { 1f }
    val key: String = if (a < b) "$a-$b" else "$b-$a"
    val gold get() = b == -1
}

class Loop(var angle: Float, var width: Float, var height: Float, var born: Float, var life: Float)

class Universe(seed: Int = 42) {
    private val random = Rng(seed)
    private fun r() = random.next()

    val heat = Ramp(0f)
    val red = Ramp(0f)
    var shockColdness = 0f
        private set
    var look = ConnectLook.Off
        private set
    var time = 0f
        private set
    var spin = -1.2f
        private set
    /** How much traffic the connection carries, 0..1: faster and more comets. */
    var traffic = 0.5f

    val rings = listOf(
        Ring(2.75f, 0.2f, M3.euler(1.05f, 0f, 0.32f)),
        Ring(3.75f, 0.14f, M3.euler(1.18f, 0f, -0.28f)),
        Ring(4.8f, 0.1f, M3.euler(1.0f, 0f, 0.08f)),
    )

    val devices: List<Device>
    val links = ArrayList<Link>()
    val loops = Array(4) { Loop(0f, 0.2f, 0.2f, -1f, 6f) }
    private var networkOn = false
    private var nextPlan = 0f
    private var nextChurn = 0f

    init {
        val kinds = listOf(
            DeviceKind.Phone, DeviceKind.Laptop, DeviceKind.Tablet, DeviceKind.Monitor, DeviceKind.Phone, DeviceKind.Tablet,
            DeviceKind.Laptop, DeviceKind.Phone, DeviceKind.Monitor, DeviceKind.Phone, DeviceKind.Tablet, DeviceKind.Laptop,
            DeviceKind.Monitor, DeviceKind.Phone, DeviceKind.Tablet, DeviceKind.Phone,
        )
        val ringOf = intArrayOf(0, 1, 2, 1, 0, 2, 2, 1, 2, 0, 1, 1, 2, 0, 2, 1)
        val perRing = IntArray(3).also { c -> ringOf.forEach { c[it]++ } }
        val seen = IntArray(3)
        val tints = listOf(Color(0.3f, 0.82f, 1f), Color(0.4f, 0.66f, 1f), Color(0.25f, 0.95f, 0.88f), Color(1f, 0.76f, 0.4f))
        devices = kinds.mapIndexed { i, kind ->
            val (panels, anchor, faceTilt) = model(kind)
            val ring = ringOf[i]
            val slot = seen[ring]++
            val a = r() * 2 * PI.toFloat()
            val rad = 2.8f + r() * 2.9f
            Device(
                kind = kind,
                panels = panels,
                anchorLocal = anchor,
                tint = tints[i % tints.size],
                seed = r() * 10,
                home = V3(cos(a) * rad, (r() - 0.5f) * 5.4f, sin(a) * rad * 0.55f),
                waves = FloatArray(9) { k -> when (k % 3) { 0 -> 0.04f + r() * 0.1f; 1 -> r() * 6.28f; else -> 0.3f + r() * 0.5f } },
                spin = V3((r() - 0.5f) * 0.5f, (r() - 0.5f) * 0.6f, (r() - 0.5f) * 0.4f),
                tilt0 = V3(r() * 6.28f, r() * 6.28f, r() * 6.28f),
                ring = ring,
                phase = slot.toFloat() / perRing[ring] * 2 * PI.toFloat() + r() * 0.4f,
                bob = r() * 6.28f,
                lean = (r() - 0.5f) * 1.1f,
                faceTilt = faceTilt,
            )
        }
        loops.forEachIndexed { i, l -> renew(l, i * 1.7f) }
    }

    private fun renew(l: Loop, delay: Float) {
        l.angle = r() * 2 * PI.toFloat()
        l.width = 0.12f + r() * 0.22f
        l.height = 0.12f + r() * 0.4f
        l.life = 5f + r() * 5f
        l.born = time + delay
    }

    /** Puts the scene straight into [next], with no transition: Home opening on a connection already running. */
    fun jump(next: ConnectLook, camera: Camera) {
        look = next
        val on = next == ConnectLook.On
        val busy = next == ConnectLook.Busy
        heat.snap(if (on) 1f else if (busy) 0.3f else 0f)
        red.snap(if (next == ConnectLook.Error) 1f else 0f)
        for (d in devices) {
            d.power.snap(if (on) 1f else if (busy) 0.16f else 0f)
            d.order.snap(if (on) 1f else if (busy) 0.12f else 0f)
        }
        moveDevices(time, 0f, camera, false)
        links.clear()
        networkOn = on
        if (on) {
            // Links already drawn: born a while ago.
            repeat(8) { plan(time - 5f) }
            nextPlan = time + 0.9f
            nextChurn = time + 5f
        }
    }

    /** Turns the scene to [next]: the Moon, the Moon catching fire, the Sun with its network, or a failure. */
    fun show(next: ConnectLook, reduced: Boolean) {
        if (next == look) return
        val from = look
        look = next
        val now = time
        val sp = if (reduced) 0.45f else 1f
        when (next) {
            ConnectLook.Off -> {
                disableNetwork()
                devices.forEach {
                    it.power.to(0f, now, (0.3f + r() * 0.6f) * sp, 0.6f * sp)
                    it.order.to(0f, now, (0.6f + r() * 0.6f) * sp, 2.6f * sp)
                }
                heat.to(0f, now, (if (from == ConnectLook.On) 0.85f else 0.1f) * sp, 2.2f * sp)
                red.to(0f, now, 0f, 0.6f * sp)
                if (from == ConnectLook.On) pulse(now + 2.1f * sp, cold = true)
            }
            ConnectLook.Busy -> {
                // The crust cracks and glows and stays so until the connection is there; screens show a waking line.
                disableNetwork()
                heat.to(0.3f, now, 0.05f, 1.3f * sp)
                red.to(0f, now, 0f, 0.4f * sp)
                devices.forEach {
                    it.power.to(0.16f, now, (0.3f + it.position.length() * 0.12f + r() * 0.3f) * sp, 0.6f * sp, ::easeOut)
                    it.order.to(0.12f, now, 0.4f * sp, 2f * sp)
                }
            }
            ConnectLook.On -> {
                heat.to(1f, now, 0.05f, (if (from == ConnectLook.Busy) 2.0f else 2.8f) * sp)
                red.to(0f, now, 0f, 0.4f * sp)
                devices.forEach {
                    val delay = (0.45f + it.position.length() * 0.12f + r() * 0.35f) * sp
                    it.power.to(1f, now, delay, 0.95f * sp, ::easeOut)
                    it.order.to(1f, now, delay + 0.2f * sp, 2.6f * sp)
                }
                pulse(now + 1.3f * sp, cold = false)
                networkOn = true
                nextPlan = now + 1.0f * sp
                nextChurn = now + 5f
            }
            ConnectLook.Error -> {
                disableNetwork()
                heat.to(0f, now, 0.2f, 1.4f * sp)
                red.snap(0f)
                red.to(1f, now, 0f, 0.25f, ::easeOut)
                devices.forEach {
                    it.power.to(0f, now, r() * 0.3f, 0.4f)
                    it.order.to(0f, now, 0.3f, 2.2f * sp)
                }
            }
        }
    }

    /** A ring of light goes out from the body at [at]: warm when it lights, cold when it goes out. */
    private fun pulse(at: Float, cold: Boolean) {
        shockColdness = if (cold) 1f else 0f
        shockStart = at
    }

    private var shockStart = Float.POSITIVE_INFINITY

    /** 0..1 while the ring of light goes out; 1 when there is none. */
    val shockProgress: Float get() = if (time < shockStart) 1f else ((time - shockStart) / 1.4f).coerceIn(0f, 1f)

    private fun disableNetwork() {
        networkOn = false
        for (l in links) if (l.retireAt == Float.POSITIVE_INFINITY) l.retireAt = time + r() * 0.4f
    }

    fun update(dt: Float, camera: Camera, reduced: Boolean) {
        time += dt
        val t = time
        heat.update(t)
        red.update(t)
        val h = heat.value
        spin += dt * (if (reduced) 0.03f else 0.1f + 0.06f * h)
        moveDevices(t, dt, camera, reduced)
        if (networkOn && t >= nextPlan) {
            plan(t)
            nextPlan = t + 0.9f
        }
        if (networkOn && t >= nextChurn) {
            val live = links.filter { it.retireAt == Float.POSITIVE_INFINITY && it.b != -1 }
            if (live.size > 6) live[(r() * live.size).toInt().coerceAtMost(live.size - 1)].retireAt = t
            nextChurn = t + 3f + r() * 3f
        }
        links.removeAll { t - it.retireAt > RETIRE_TIME }
        arrivals(t)
        loops.forEachIndexed { i, l ->
            if ((t - l.born) / l.life > 1f) renew(l, r() * 2f)
        }
    }

    private val tmp = V3()
    private val qa = FloatArray(4)
    private val qb = FloatArray(4)
    private val chaos = M3()
    private val facing = M3()

    private fun moveDevices(t: Float, dt: Float, camera: Camera, reduced: Boolean) {
        val tm = t * if (reduced) 0.3f else 1f
        for (d in devices) {
            d.power.update(t)
            val order = d.order.update(t)
            // Free: round its home on slow sines, never into the body.
            var fx = d.home.x + d.waves[2] * sin(tm * d.waves[0] * 6.28f + d.waves[1])
            var fy = d.home.y + d.waves[5] * sin(tm * d.waves[3] * 6.28f + d.waves[4])
            var fz = d.home.z + d.waves[8] * sin(tm * d.waves[6] * 6.28f + d.waves[7])
            val len = sqrt(fx * fx + fy * fy + fz * fz)
            if (len < 2.6f) { fx *= 2.6f / len; fy *= 2.6f / len; fz *= 2.6f / len }
            // Ordered: on its ring, all one way, bobbing a little.
            val ring = rings[d.ring]
            val ang = d.phase + tm * ring.speed
            tmp.set(cos(ang) * ring.radius, 0.1f * sin(tm * 0.8f + d.bob), sin(ang) * ring.radius)
            val o = ring.tilt.apply(tmp, V3())
            d.position.set(fx + (o.x - fx) * order, fy + (o.y - fy) * order, fz + (o.z - fz) * order)

            // Free it tumbles; ordered it turns its screen to the viewer with its own lean.
            M3.euler(d.tilt0.x + tm * d.spin.x, d.tilt0.y + tm * d.spin.y, d.tilt0.z + tm * d.spin.z, chaos)
            lookAt(d.position, camera.eye, facing)
            facing.times(M3.euler(-d.faceTilt + 0.22f * sin(ang * 1.3f + d.bob), d.lean + 0.25f * sin(ang + d.bob), 0.1f * sin(tm * 0.5f + d.bob)), facing)
            toQuat(chaos, qa)
            toQuat(facing, qb)
            slerp(qa, qb, order, qa)
            fromQuat(qa, d.rotation)
            d.rotation.apply(d.anchorLocal, d.anchor)
            d.anchor.set(d.anchor.x + d.position.x, d.anchor.y + d.position.y, d.anchor.z + d.position.z)
            d.depth = camera.depth(d.position)
            d.flash = (d.flash - dt * 3.2f).coerceAtLeast(0f)
        }
    }

    /** Which links the network wants: spokes into the Sun first, then each awake device to its two nearest awake ones. */
    private fun plan(now: Float) {
        val awake = devices.indices.filter { devices[it].power.value > 0.85f }
        val want = LinkedHashMap<String, Pair<Int, Int>>()
        awake.sortedBy { devices[it].anchor.length() }.take(5).forEach { want[key(it, -1)] = it to -1 }
        for (i in awake) {
            awake.filter { it != i }
                .map { it to dist(devices[i].anchor, devices[it].anchor) }
                .filter { it.second < 4.2f }
                .sortedBy { it.second }
                .take(2)
                .forEach { (j, _) -> want[key(i, j)] = i to j }
        }
        var removed = 0
        for (l in links) {
            if (removed >= 2) break
            if (l.retireAt != Float.POSITIVE_INFINITY || want.containsKey(l.key)) continue
            val far = l.b != -1 && dist(devices[l.a].anchor, devices[l.b].anchor) > 5f
            if (far || r() < 0.35f) { l.retireAt = now; removed++ }
        }
        val have = links.filter { it.retireAt == Float.POSITIVE_INFINITY }.map { it.key }.toSet()
        var added = 0
        for ((k, ab) in want) {
            if (added >= 5 || links.size >= MAX_LINKS) break
            if (k in have) continue
            val spoke = ab.second == -1
            links += Link(
                a = ab.first, b = ab.second, born = now + r() * 0.6f, drawTime = 0.55f + r() * 0.4f,
                dir = if (spoke) (if (r() > 0.35f) 1 else -1) else (if (r() > 0.5f) 1 else -1),
                seed = r(), speed = (if (spoke) 0.42f else 0.3f) + r() * 0.35f,
                count = if (spoke) 3 else 1 + (r() * 3).toInt().coerceAtMost(2),
                bend = (if (r() > 0.5f) 1f else -1f) * (0.1f + r() * 0.12f),
            )
            added++
        }
    }

    /** Where a link's comets are, 0..1 from its start, for traffic speed [scale]. */
    fun pulsePhase(l: Link, k: Int): Float {
        val ph = time * l.speed * trafficSpeed + l.seed + k.toFloat() / l.count
        return ph - floor(ph)
    }

    val trafficSpeed: Float get() = 0.6f + 0.9f * traffic

    /** A comet reaching a screen makes it blink. */
    private fun arrivals(t: Float) {
        for (l in links) {
            if (t - l.born < l.drawTime || l.retireAt != Float.POSITIVE_INFINITY) continue
            for (k in 0 until l.count) {
                val ph = pulsePhase(l, k)
                if (ph < l.phases[k]) {
                    val target = if (l.dir > 0) l.b else l.a
                    if (target >= 0) devices[target].flash = 1f
                }
                l.phases[k] = ph
            }
        }
    }

    private fun key(a: Int, b: Int) = if (a < b) "$a-$b" else "$b-$a"
    private fun dist(a: V3, b: V3): Float {
        val x = a.x - b.x; val y = a.y - b.y; val z = a.z - b.z
        return sqrt(x * x + y * y + z * z)
    }

    companion object {
        const val MAX_LINKS = 30
        const val RETIRE_TIME = 0.55f
    }
}

// ---- Device models: boxes of panels, in metres-ish units, front of the screen towards +z. ----

private fun box(
    cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float, material: Material,
    front: Face = Face.Body, top: Face = Face.Body, round: Float = 0.08f, frame: M3? = null, pivot: V3? = null,
): List<Panel> {
    val hw = w / 2; val hh = h / 2; val hd = d / 2
    fun p(x: Float, y: Float, z: Float): V3 {
        val v = V3(cx + x, cy + y, cz + z)
        if (frame == null || pivot == null) return v
        val rel = V3(v.x - pivot.x, v.y - pivot.y, v.z - pivot.z)
        val r = frame.apply(rel)
        return V3(r.x + pivot.x, r.y + pivot.y, r.z + pivot.z)
    }
    fun n(x: Float, y: Float, z: Float): V3 = frame?.apply(V3(x, y, z)) ?: V3(x, y, z)
    val roundFront = round.coerceAtMost(0.45f)
    return listOf(
        Panel(arrayOf(p(-hw, -hh, hd), p(hw, -hh, hd), p(hw, hh, hd), p(-hw, hh, hd)), n(0f, 0f, 1f), front, material, roundFront, w / h),
        Panel(arrayOf(p(hw, -hh, -hd), p(-hw, -hh, -hd), p(-hw, hh, -hd), p(hw, hh, -hd)), n(0f, 0f, -1f), Face.Body, material, roundFront, w / h),
        Panel(arrayOf(p(hw, -hh, hd), p(hw, -hh, -hd), p(hw, hh, -hd), p(hw, hh, hd)), n(1f, 0f, 0f), Face.Body, material, 0f, d / h),
        Panel(arrayOf(p(-hw, -hh, -hd), p(-hw, -hh, hd), p(-hw, hh, hd), p(-hw, hh, -hd)), n(-1f, 0f, 0f), Face.Body, material, 0f, d / h),
        Panel(arrayOf(p(-hw, hh, hd), p(hw, hh, hd), p(hw, hh, -hd), p(-hw, hh, -hd)), n(0f, 1f, 0f), top, material, if (top == Face.Keys) 0.04f else 0f, w / d),
        Panel(arrayOf(p(-hw, -hh, -hd), p(hw, -hh, -hd), p(hw, -hh, hd), p(-hw, -hh, hd)), n(0f, -1f, 0f), Face.Body, material, 0f, w / d),
    )
}

private const val DEVICE_SCALE = 1.3f

/** The model of [kind], scaled so the devices read well in Home's frame. */
private fun model(kind: DeviceKind): Triple<List<Panel>, V3, Float> = baseModel(kind).let { (panels, anchor, tilt) ->
    fun s(v: V3) = V3(v.x * DEVICE_SCALE, v.y * DEVICE_SCALE, v.z * DEVICE_SCALE)
    Triple(panels.map { Panel(it.corners.map(::s).toTypedArray(), it.normal, it.face, it.material, it.round, it.aspect) }, s(anchor), tilt)
}

private fun baseModel(kind: DeviceKind): Triple<List<Panel>, V3, Float> = when (kind) {
    DeviceKind.Phone -> Triple(
        box(0f, 0f, 0f, 0.4f, 0.82f, 0.045f, Material.Graphite, front = Face.Screen, round = 0.16f) +
            box(-0.1f, 0.27f, -0.03f, 0.15f, 0.15f, 0.018f, Material.Graphite, round = 0.3f),
        V3(0f, 0f, 0.03f), 0f,
    )
    DeviceKind.Tablet -> Triple(box(0f, 0f, 0f, 0.72f, 0.98f, 0.036f, Material.Silver, front = Face.Screen, round = 0.07f), V3(0f, 0f, 0.03f), 0f)
    DeviceKind.Laptop -> {
        val hinge = V3(0f, -0.34f, -0.4f)
        val lid = M3.rx(-0.28f)
        Triple(
            box(0f, -0.36f, 0f, 1.16f, 0.04f, 0.8f, Material.Silver, top = Face.Keys, round = 0.05f) +
                box(0f, -0.34f + 0.39f, -0.4f, 1.16f, 0.78f, 0.026f, Material.Silver, front = Face.Screen, round = 0.04f, frame = lid, pivot = hinge),
            lid.apply(V3(0f, 0.39f, 0.03f)).let { V3(it.x + hinge.x, it.y + hinge.y, it.z + hinge.z) }, -0.28f,
        )
    }
    DeviceKind.Monitor -> Triple(
        box(0f, 0.08f, 0f, 1.4f, 0.84f, 0.045f, Material.Graphite, front = Face.Screen, round = 0.04f) +
            box(0f, -0.46f, -0.06f, 0.09f, 0.44f, 0.05f, Material.Silver) +
            box(0f, -0.69f, 0f, 0.5f, 0.026f, 0.3f, Material.Silver),
        V3(0f, 0.08f, 0.03f), 0f,
    )
}

// ---- Rotations as quaternions, for turning smoothly from tumbling to facing the viewer. ----

/** A rotation whose +z points from [from] to [to], keeping world up as up. */
private fun lookAt(from: V3, to: V3, out: M3): M3 {
    var zx = to.x - from.x; var zy = to.y - from.y; var zz = to.z - from.z
    val zl = sqrt(zx * zx + zy * zy + zz * zz); zx /= zl; zy /= zl; zz /= zl
    // x = up × z
    var xx = zz; var xy = 0f; var xz = -zx
    val xl = sqrt(xx * xx + xz * xz).coerceAtLeast(1e-5f); xx /= xl; xz /= xl
    // y = z × x
    val yx = zy * xz - zz * xy; val yy = zz * xx - zx * xz; val yz = zx * xy - zy * xx
    // Columns are the local axes in world space.
    out.a = xx; out.b = yx; out.c = zx
    out.d = xy; out.e = yy; out.f = zy
    out.g = xz; out.h = yz; out.i = zz
    return out
}

private fun toQuat(m: M3, q: FloatArray) {
    val tr = m.a + m.e + m.i
    if (tr > 0) {
        val s = sqrt(tr + 1f) * 2
        q[3] = 0.25f * s; q[0] = (m.h - m.f) / s; q[1] = (m.c - m.g) / s; q[2] = (m.d - m.b) / s
    } else if (m.a > m.e && m.a > m.i) {
        val s = sqrt(1f + m.a - m.e - m.i) * 2
        q[3] = (m.h - m.f) / s; q[0] = 0.25f * s; q[1] = (m.b + m.d) / s; q[2] = (m.c + m.g) / s
    } else if (m.e > m.i) {
        val s = sqrt(1f + m.e - m.a - m.i) * 2
        q[3] = (m.c - m.g) / s; q[0] = (m.b + m.d) / s; q[1] = 0.25f * s; q[2] = (m.f + m.h) / s
    } else {
        val s = sqrt(1f + m.i - m.a - m.e) * 2
        q[3] = (m.d - m.b) / s; q[0] = (m.c + m.g) / s; q[1] = (m.f + m.h) / s; q[2] = 0.25f * s
    }
}

private fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
    var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
    var cos = a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw
    if (cos < 0) { cos = -cos; bx = -bx; by = -by; bz = -bz; bw = -bw }
    val k0: Float
    val k1: Float
    if (cos > 0.9995f) { k0 = 1 - t; k1 = t } else {
        val th = acos(cos.coerceIn(-1f, 1f))
        val s = sin(th)
        k0 = sin((1 - t) * th) / s
        k1 = sin(t * th) / s
    }
    val x = a[0] * k0 + bx * k1; val y = a[1] * k0 + by * k1; val z = a[2] * k0 + bz * k1; val w = a[3] * k0 + bw * k1
    val l = sqrt(x * x + y * y + z * z + w * w)
    out[0] = x / l; out[1] = y / l; out[2] = z / l; out[3] = w / l
}

private fun fromQuat(q: FloatArray, m: M3) {
    val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
    m.a = 1 - 2 * (y * y + z * z); m.b = 2 * (x * y - z * w); m.c = 2 * (x * z + y * w)
    m.d = 2 * (x * y + z * w); m.e = 1 - 2 * (x * x + z * z); m.f = 2 * (y * z - x * w)
    m.g = 2 * (x * z - y * w); m.h = 2 * (y * z + x * w); m.i = 1 - 2 * (x * x + y * y)
}
