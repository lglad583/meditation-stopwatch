package com.meditation.stopwatch.visuals

object Glsl {
    /** Full-screen triangle; no vertex buffer needed (uses gl_VertexID). */
    val VERTEX = """
        #version 300 es
        out vec2 vUv;
        void main() {
            vec2 v = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            vUv = v;
            gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * Prepended to every movement body.  Declares the uniform contract and a toolbox of helpers,
     * then a main() that calls scene() and finishes the frame.
     */
    val PRELUDE = """
        #version 300 es
        precision highp float;
        precision highp int;

        uniform vec2  uResolution;   // render-target size in pixels
        uniform float uTime;         // animation clock in seconds – always flowing, even when paused
        uniform float uElapsed;      // session stopwatch seconds (frozen when paused, 0 when idle)
        uniform float uBreath;       // lung fullness 0..1, smooth
        uniform float uBreathPhase;  // 0..1 position inside the breath cycle
        uniform float uBreathVel;    // d(fullness)/dt, 1/s  (>0 inhale, <0 exhale)
        uniform float uIntensity;    // 0..1 session intensity (IntensityCurve)
        uniform float uRunning;      // 1.0 while the stopwatch runs, else 0.0
        uniform float uSeed;         // 0..1000 random per session – vary palettes / layouts
        uniform float uFade;         // 0..1 opacity of this pass (movement crossfade)
        uniform float uAspect;       // width / height

        in vec2 vUv;
        out vec4 fragColor;

        const float PI  = 3.14159265359;
        const float TAU = 6.28318530718;

        // ---------- hashing & noise ----------
        float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
        float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
        vec2  hash22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
        vec3  hash33(vec3 p3) { p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yxz + 33.33); return fract((p3.xxy + p3.yxx) * p3.zyx); }

        // value noise, 2D, smooth (quintic)
        float vnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
            float a = hash21(i), b = hash21(i + vec2(1, 0)), c = hash21(i + vec2(0, 1)), d = hash21(i + vec2(1, 1));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }
        // gradient noise, 2D, returns -1..1
        float gnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            float a = dot(hash22(i) * 2.0 - 1.0, f);
            float b = dot(hash22(i + vec2(1, 0)) * 2.0 - 1.0, f - vec2(1, 0));
            float c = dot(hash22(i + vec2(0, 1)) * 2.0 - 1.0, f - vec2(0, 1));
            float d = dot(hash22(i + vec2(1, 1)) * 2.0 - 1.0, f - vec2(1, 1));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }
        // 3D value noise (p.z often = time) – smooth
        float vnoise3(vec3 p) {
            vec3 i = floor(p), f = fract(p);
            vec3 u = f * f * (3.0 - 2.0 * f);
            float n000 = hash33(i).x,                 n100 = hash33(i + vec3(1,0,0)).x;
            float n010 = hash33(i + vec3(0,1,0)).x,   n110 = hash33(i + vec3(1,1,0)).x;
            float n001 = hash33(i + vec3(0,0,1)).x,   n101 = hash33(i + vec3(1,0,1)).x;
            float n011 = hash33(i + vec3(0,1,1)).x,   n111 = hash33(i + vec3(1,1,1)).x;
            return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
                       mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
        }
        mat2 rot2(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
        const mat2 FBM_M = mat2(0.8, 0.6, -0.6, 0.8);
        float fbm(vec2 p, int oct) {
            float v = 0.0, a = 0.5;
            for (int i = 0; i < 8; i++) {
                if (i >= oct) break;
                v += a * gnoise(p);
                p = FBM_M * p * 2.02 + 17.3;
                a *= 0.5;
            }
            return v;
        }
        float fbm3(vec3 p, int oct) {
            float v = 0.0, a = 0.5;
            for (int i = 0; i < 8; i++) {
                if (i >= oct) break;
                v += a * (vnoise3(p) * 2.0 - 1.0);
                p = p * 2.03 + 11.7;
                a *= 0.5;
            }
            return v;
        }

        // ---------- colour ----------
        // Inigo Quilez cosine palette
        vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) { return a + b * cos(TAU * (c * t + d)); }
        // A few curated calm palettes, selected by k in 0..1 (mixes continuously between neighbours)
        vec3 calmPalette(float t, float k) {
            vec3 p0 = palette(t, vec3(0.50, 0.45, 0.55), vec3(0.45, 0.35, 0.40), vec3(1.0, 1.0, 1.0), vec3(0.00, 0.15, 0.20)); // rose / violet
            vec3 p1 = palette(t, vec3(0.40, 0.55, 0.60), vec3(0.35, 0.40, 0.45), vec3(1.0, 1.0, 0.5), vec3(0.30, 0.20, 0.20)); // teal / sand
            vec3 p2 = palette(t, vec3(0.45, 0.40, 0.60), vec3(0.40, 0.45, 0.40), vec3(1.0, 0.7, 0.4), vec3(0.00, 0.15, 0.20)); // indigo / amber
            vec3 p3 = palette(t, vec3(0.55, 0.50, 0.50), vec3(0.45, 0.50, 0.50), vec3(1.0, 1.0, 1.0), vec3(0.10, 0.20, 0.30)); // aurora
            float s = fract(k) * 4.0;
            vec3 c = mix(p0, p1, smoothstep(0.0, 1.0, s));
            c = mix(c, p2, smoothstep(1.0, 2.0, s));
            c = mix(c, p3, smoothstep(2.0, 3.0, s));
            c = mix(c, p0, smoothstep(3.0, 4.0, s));
            return c;
        }
        vec3 hsv2rgb(vec3 c) { vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0/3.0, 1.0/3.0)) * 6.0 - 3.0); return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y); }
        float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
        vec3 saturate3(vec3 c, float s) { float l = luma(c); return mix(vec3(l), c, s); }

        // ---------- geometry ----------
        vec2 cmul(vec2 a, vec2 b) { return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x); }
        vec2 cdiv(vec2 a, vec2 b) { float d = dot(b, b); return vec2(dot(a, b), a.y * b.x - a.x * b.y) / d; }
        // fold the plane into n mirrored wedges (kaleidoscope); n may be fractional and animate smoothly
        vec2 kaleido(vec2 p, float n) {
            float a = atan(p.y, p.x);
            float seg = TAU / max(n, 1.0);
            a = mod(a, seg);
            a = abs(a - seg * 0.5);
            return length(p) * vec2(cos(a), sin(a));
        }
        // toroidal / polar helpers
        vec2 toPolar(vec2 p) { return vec2(length(p), atan(p.y, p.x)); }
        float sdCircle(vec2 p, float r) { return length(p) - r; }
        float smin(float a, float b, float k) { float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0); return mix(b, a, h) - k * h * (1.0 - h); }

        // ---------- breath helpers ----------
        // a gentle -1..1 oscillation locked to the breath, positive at full lungs
        float breathWave() { return uBreath * 2.0 - 1.0; }
        // a very slow drift 0..1 with period ~90 s, for wandering palettes
        float slowDrift(float offset) { return 0.5 + 0.5 * sin(uTime * TAU / 90.0 + offset); }

        // ---------- finishing ----------
        vec3 tonemap(vec3 c) { c = max(c, 0.0); return c / (1.0 + c); }              // Reinhard, gentle
        float vignette(vec2 uv, float k) { vec2 q = uv * (1.0 - uv.yx); return pow(q.x * q.y * 15.0, k); }
        // film grain, sub-pixel and tiny – hides banding on OLED
        float grain(vec2 uv) { return (hash21(uv * uResolution.xy + fract(uTime) * 1013.0) - 0.5) * (1.5 / 255.0); }

        vec3 scene(vec2 p, vec2 uv);

        void main() {
            vec2 uv = vUv;
            vec2 p = (uv - 0.5) * 2.0;
            p.x *= uAspect;
            vec3 c = scene(p, uv);
            // vividness: the whole picture gains contrast and colour as the session deepens
            c *= 1.4 + 1.1 * uIntensity;
            c = saturate3(c, 1.1 + 0.3 * uIntensity);
            c = tonemap(c);
            c *= mix(1.0, vignette(uv, 0.25), 0.35);
            c += grain(uv);
            fragColor = vec4(clamp(c, 0.0, 1.0), uFade);
        }
    """.trimIndent()

    /** Compose a complete fragment shader from a movement body. */
    fun fragment(m: Movement): String = PRELUDE + "\n\n// ---- movement: " + m.name + " ----\n" + m.body
}
