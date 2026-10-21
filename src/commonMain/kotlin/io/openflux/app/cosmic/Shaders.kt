package io.openflux.app.cosmic

// The scene's pixel shaders, in the subset SkSL and AGSL share. Ported from the web prototype
// (cosmic-button/src/body.js): the same noise, the same burning change from the Moon to the Sun.

private const val NOISE = """
float3 mod289v3(float3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float4 mod289v4(float4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
float4 permute4(float4 x) { return mod289v4(((x * 34.0) + 1.0) * x); }
float4 invSqrt4(float4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(float3 v) {
  float2 C = float2(1.0 / 6.0, 1.0 / 3.0);
  float4 D = float4(0.0, 0.5, 1.0, 2.0);
  float3 i = floor(v + dot(v, C.yyy));
  float3 x0 = v - i + dot(i, C.xxx);
  float3 g = step(x0.yzx, x0.xyz);
  float3 l = 1.0 - g;
  float3 i1 = min(g.xyz, l.zxy);
  float3 i2 = max(g.xyz, l.zxy);
  float3 x1 = x0 - i1 + C.xxx;
  float3 x2 = x0 - i2 + C.yyy;
  float3 x3 = x0 - D.yyy;
  i = mod289v3(i);
  float4 p = permute4(permute4(permute4(
    i.z + float4(0.0, i1.z, i2.z, 1.0))
    + i.y + float4(0.0, i1.y, i2.y, 1.0))
    + i.x + float4(0.0, i1.x, i2.x, 1.0));
  float n_ = 0.142857142857;
  float3 ns = n_ * D.wyz - D.xzx;
  float4 j = p - 49.0 * floor(p * ns.z * ns.z);
  float4 x_ = floor(j * ns.z);
  float4 y_ = floor(j - 7.0 * x_);
  float4 x = x_ * ns.x + ns.yyyy;
  float4 y = y_ * ns.x + ns.yyyy;
  float4 h = 1.0 - abs(x) - abs(y);
  float4 b0 = float4(x.xy, y.xy);
  float4 b1 = float4(x.zw, y.zw);
  float4 s0 = floor(b0) * 2.0 + 1.0;
  float4 s1 = floor(b1) * 2.0 + 1.0;
  float4 sh = -step(h, float4(0.0));
  float4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
  float4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
  float3 p0 = float3(a0.xy, h.x);
  float3 p1 = float3(a0.zw, h.y);
  float3 p2 = float3(a1.xy, h.z);
  float3 p3 = float3(a1.zw, h.w);
  float4 norm = invSqrt4(float4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
  float4 m = max(0.6 - float4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
  m = m * m;
  return 42.0 * dot(m * m, float4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

float fbm3(float3 p) {
  float a = 0.5;
  float s = 0.0;
  for (int i = 0; i < 3; i++) { s += a * snoise(p); p *= 2.03; a *= 0.5; }
  return s;
}
"""

/**
 * The body: a disc that is a sphere. Each pixel inside the circle is a point of the sphere facing the viewer;
 * the rows turn it into the body's own frame (its spin and its tilted axis), where the Moon's map and the Sun's
 * surface are read. [uHeat] 0 is the Moon, 1 the Sun; between, the crust burns away; [uRed] is a failure.
 */
val BODY_SHADER = """
uniform float2 uCenter;
uniform float uRadius;
uniform float3 uRow0;
uniform float3 uRow1;
uniform float3 uRow2;
uniform float uTime;
uniform float uHeat;
uniform float uHover;
uniform float uRed;
uniform float2 uMoonSize;
uniform float2 uHeightSize;
uniform float2 uSunSize;
uniform shader uMoon;
uniform shader uHeight;
uniform shader uSun;
$NOISE

float3 toBody(float3 v) { return float3(dot(uRow0, v), dot(uRow1, v), dot(uRow2, v)); }
float3 toView(float3 v) { return uRow0 * v.x + uRow1 * v.y + uRow2 * v.z; }

float2 equirect(float3 p) {
  return float2(0.5 + atan(p.x, p.z) / 6.2831853, 0.5 - asin(clamp(p.y, -1.0, 1.0)) / 3.1415927);
}
float3 moonAt(float2 uv) { return uMoon.eval(float2(fract(uv.x), clamp(uv.y, 0.002, 0.998)) * uMoonSize).rgb; }
float heightAt(float2 uv) { return uHeight.eval(float2(fract(uv.x), clamp(uv.y, 0.002, 0.998)) * uHeightSize).r; }
float3 sunAt(float2 uv) { return uSun.eval(clamp(uv, 0.001, 0.999) * uSunSize).rgb; }
float3 triplanar(float3 p) {
  float3 w = pow(abs(p), float3(5.0));
  w /= (w.x + w.y + w.z);
  return sunAt(p.zy * 0.5 + 0.5) * w.x + sunAt(p.xz * 0.5 + 0.5) * w.y + sunAt(p.xy * 0.5 + 0.5) * w.z;
}

half4 main(float2 fragCoord) {
  float2 q = (fragCoord - uCenter) / uRadius;
  q.y = -q.y;
  float r = length(q);
  if (r > 1.0 + 2.0 / uRadius) { return half4(0.0); }
  float cover = clamp((1.0 - r) * uRadius + 0.5, 0.0, 1.0);
  float2 qc = q / max(r, 1.0);
  float3 N = float3(qc, sqrt(max(0.0, 1.0 - dot(qc, qc))));
  float mu = N.z;
  float3 p = normalize(toBody(N));
  float h = uHeat;
  // Only what shows is worked out: the field ([0, 0.92]) has burned through everywhere past h = 0.85, and nothing
  // has burned before h = 0.03. The noise is most of the cost.
  bool showMoon = h < 0.85 || uRed > 0.0;
  bool showSun = h > 0.03;

  // The Moon: NASA LRO colour, LOLA relief, lit from the upper left, a cold rim.
  float3 albedo = float3(0.0);
  float3 moon = float3(0.0);
  if (showMoon) {
    float2 uv = equirect(p);
    albedo = moonAt(uv);
    float h0 = heightAt(uv);
    float hE = heightAt(uv + float2(1.0 / uHeightSize.x, 0.0));
    float hN = heightAt(uv - float2(0.0, 1.0 / uHeightSize.y));
    float3 east = normalize(cross(float3(0.0, 1.0, 0.0), p) + float3(0.00001, 0.0, 0.0));
    float3 north = cross(p, east);
    float3 nb = toView(normalize(p - (east * (hE - h0) + north * (hN - h0)) * 2.6));
    float3 L = normalize(float3(-0.55, 0.38, 0.74));
    float lam = max(dot(nb, L), 0.0) * smoothstep(-0.08, 0.3, dot(N, L));
    moon = albedo * (0.07 + 1.15 * lam) * float3(0.86, 0.93, 1.06);
    moon += float3(0.30, 0.50, 0.95) * pow(1.0 - mu, 3.0) * 0.45;
  }

  // The Sun: the SDO surface laid round the sphere, flowing and boiling.
  float3 sun = float3(0.0);
  if (showSun) {
    float3 w3 = p * 1.7 + float3(0.0, uTime * 0.02, uTime * 0.013);
    float3 warp = float3(snoise(w3), snoise(w3 + 17.1), snoise(w3 + 31.7));
    float3 s = triplanar(normalize(p + warp * 0.05));
    s *= 0.82 + 0.36 * fbm3(p * 22.0 + float3(uTime * 0.25));
    float l = dot(s, float3(0.3, 0.55, 0.15));
    float3 ramp = mix(float3(0.7, 0.12, 0.02), float3(1.0, 0.48, 0.09), smoothstep(0.08, 0.42, l));
    ramp = mix(ramp, float3(1.0, 0.9, 0.66), smoothstep(0.5, 0.95, l));
    sun = mix(s, ramp, 0.5) * (0.95 + 1.5 * smoothstep(0.55, 1.0, l));
    sun *= mix(0.45, 1.0, pow(mu, 0.45));
    sun += float3(1.0, 0.42, 0.08) * pow(1.0 - mu, 3.0) * 0.8;
  }

  // The change: the dark seas and the noise decide where the crust gives way first.
  float dark = 1.0 - smoothstep(0.08, 0.45, dot(albedo, float3(0.333)));
  float field = h < 0.85 ? 0.0 : 1.0;
  if (h > 0.0 && h < 0.85) {
    float n = fbm3(p * 2.6 + 3.7) * 0.5 + 0.5;
    field = clamp(n * 0.62 + dark * 0.38, 0.0, 1.0) * 0.92;
  }
  float thr = 1.0 - h * 1.3;
  float burned = smoothstep(thr - 0.035, thr + 0.035, field);
  float front = (1.0 - smoothstep(0.0, 0.06, abs(field - thr))) * smoothstep(0.0, 0.02, h) * (1.0 - smoothstep(0.97, 1.0, h));
  float warm = smoothstep(0.0, 0.5, h);
  float3 moonHot = moon * mix(float3(1.0), float3(1.3, 0.62, 0.42), warm);
  if (showMoon && warm > 0.0) moonHot += float3(0.95, 0.22, 0.03) * dark * warm * 0.45 * (0.65 + 0.35 * snoise(p * 6.0 + uTime * 0.6));
  float cracks = 0.0;
  if (h > 0.02 && burned < 1.0) cracks = pow(1.0 - abs(snoise(p * 7.0 + 1.3)), 12.0) * smoothstep(0.02, 0.3, h) * (1.0 - burned);
  float3 col = mix(moonHot, sun, burned);
  col += float3(1.0, 0.62, 0.22) * front * 2.4;
  col += float3(1.0, 0.42, 0.08) * cracks * 1.8;
  // A failure: the crust flares red and dims.
  float3 hurt = moon * float3(0.75, 0.42, 0.4) + float3(0.9, 0.08, 0.04) * (cracks * 2.0 + pow(1.0 - mu, 2.0) * 0.6);
  col = mix(col, hurt, uRed);
  col *= 1.0 + 0.2 * uHover;
  col = 1.0 - exp(-col * 1.25);
  return half4(half3(col * cover), half(cover));
}
"""

/** The light round the body: a cold halo round the Moon; the Sun's corona, its rays and prominences. */
val CORONA_SHADER = """
uniform float2 uCenter;
uniform float uRadius;
uniform float uTime;
uniform float uHeat;
uniform float uHover;
uniform float uRed;
uniform float4 uLoopAngle;
uniform float4 uLoopWidth;
uniform float4 uLoopHeight;
uniform float4 uLoopLife;
$NOISE

half4 main(float2 fragCoord) {
  float2 c = (fragCoord - uCenter) / uRadius;
  c.y = -c.y;
  float r = length(c);
  if (r < 0.985) { return half4(0.0); }
  float a = atan(c.y, c.x);
  float d = max(r - 1.0, 0.0);
  float3 moonHalo = float3(0.45, 0.66, 1.0) * (exp(-d * 6.0) * 0.55 + exp(-d * 1.8) * 0.1);
  float heat = smoothstep(0.0, 1.0, uHeat);
  float3 sun = float3(0.0);
  // The noise is worked out only where it shows: none round the Moon, the swirl near the rim, the rays out to two radii.
  if (heat > 0.0) {
    float st = d < 1.3 ? fbm3(float3(cos(a) * 1.7, sin(a) * 1.7, uTime * 0.05)) : 0.0;
    float rays = 0.0;
    if (d < 2.0) rays = pow(max(0.0, snoise(float3(cos(a) * 5.0, sin(a) * 5.0, d * 1.2 - uTime * 0.22)) * 0.5 + 0.5), 3.0) * (1.0 - smoothstep(1.6, 2.0, d));
    float glow = exp(-d * 4.2) * (0.85 + 0.6 * st) + exp(-d * 1.4) * 0.12 + rays * exp(-d * 2.0) * 0.6;
    float3 tint = mix(float3(1.0, 0.4, 0.06), float3(1.0, 0.8, 0.5), exp(-d * 5.0));
    sun = tint * glow;
    float prom = 0.0;
    for (int i = 0; i < 4; i++) {
      float ang = i == 0 ? uLoopAngle.x : i == 1 ? uLoopAngle.y : i == 2 ? uLoopAngle.z : uLoopAngle.w;
      float wid = i == 0 ? uLoopWidth.x : i == 1 ? uLoopWidth.y : i == 2 ? uLoopWidth.z : uLoopWidth.w;
      float hei = i == 0 ? uLoopHeight.x : i == 1 ? uLoopHeight.y : i == 2 ? uLoopHeight.z : uLoopHeight.w;
      float life = i == 0 ? uLoopLife.x : i == 1 ? uLoopLife.y : i == 2 ? uLoopLife.z : uLoopLife.w;
      float da = atan(sin(a - ang), cos(a - ang));
      float x = da / wid;
      if (life > 0.0 && abs(x) < 1.0) {
        float y = hei * sqrt(1.0 - x * x);
        float th = 0.016 + 0.035 * (1.0 - abs(x));
        float dist = d - y;
        float f = exp(-dist * dist / (th * th));
        f *= f > 0.003 ? 0.55 + 0.45 * snoise(float3(x * 5.0, d * 12.0, uTime * 0.9 + float(i) * 7.0)) : 0.55;
        f += exp(-d * 22.0) * (1.0 - abs(x)) * 0.6;
        prom += f * life;
      }
    }
    sun += float3(1.0, 0.36, 0.06) * prom * 1.8;
  }
  float3 col = mix(moonHalo, sun, heat) * (1.0 + 0.35 * uHover);
  col = mix(col, float3(1.0, 0.18, 0.1) * exp(-d * 4.0) * 0.8, uRed * 0.7);
  col *= 1.0 - smoothstep(2.6, 3.6, r);
  col = 1.0 - exp(-col * 1.3);
  float alpha = max(col.r, max(col.g, col.b));
  return half4(half3(col), half(alpha));
}
"""
