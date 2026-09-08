package com.meditation.stopwatch.visuals

/**
 * Movements 1–4: the opening half of the programme.  Movement 1 is also what the renderer shows
 * while the stopwatch is idle, so it must be almost nothing at zero intensity: a single breathing
 * light.  All four obey every rule in [Movement] (near-black at zero intensity, breath-locked
 * motion, no strobing) and share the conventions of [MovementsB]:
 *  - Iteration / layer / octave counts are *fractional* and driven by uIntensity: the last partial
 *    step is blended in with a 0..1 weight so nothing ever changes by a visible step.
 *  - Loop bounds are compile-time constants with an early `break`, as mobile GPUs require.
 *  - All motion faster than the breath is either sub-pixel (grain, dither) or absent.
 *
 * Every body has been validated with tools/validate-shader.sh.
 */
object MovementsA {

    /**
     * 1 – Threshold.  A single breathing light in the dark.  As intensity rises, a slow haze
     * condenses around it and faint rings ripple outward from it, one ring per breath.
     */
    private val THRESHOLD = """
        // Threshold: the opening.  A soft core of light whose radius follows lung fullness; at zero
        // intensity that is all there is.  Intensity lets a domain-warped haze condense around the light
        // and sends slow rings outward from it, each ring born on an inhale.

        // fbm with a fractional octave count so the haze can deepen with intensity without steps
        float fbmF(vec2 p, float oct) {
            float v = 0.0, a = 0.5;
            for (int i = 0; i < 6; i++) {
                float w = clamp(oct - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                v += a * w * gnoise(p);
                p = FBM_M * p * 2.02 + 17.3;
                a *= 0.5;
            }
            return v;
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0137);
            float bw = breathWave();
            float r = length(p);

            // a very slow drift of the whole field, so the light is never nailed to one pixel
            vec2 c = vec2(sin(uTime * 0.006 + uSeed * 0.3), cos(uTime * 0.0045 + uSeed * 0.7)) * 0.05 * I;
            vec2 d = p - c;
            float rd = length(d);

            // the core: radius follows the breath; intensity lets it grow
            float rad = 0.16 + 0.09 * uBreath + 0.12 * I;
            float core = exp(-rd * rd / (rad * rad));
            float pal = slowDrift(uSeed * 0.02) * 0.3;
            vec3 cCore = calmPalette(pal + 0.15, seedK) * 0.5 + 0.5;
            vec3 cHaze = calmPalette(pal + 0.55, seedK);
            vec3 cRing = calmPalette(pal + 0.85, seedK) * 0.6 + 0.4;

            // haze: two chained fbm warps, drifting glacially, breathing in scale
            float oct = 2.5 + 3.0 * I;
            vec2 q = d * (1.4 - 0.06 * bw) + vec2(uTime * 0.008, -uTime * 0.006);
            vec2 w = vec2(fbmF(q + vec2(3.1, 1.7) + uTime * 0.004, oct),
                          fbmF(q + vec2(7.3, 5.9) - uTime * 0.003, oct));
            float haze = fbmF(q + (0.8 + 1.2 * I) * w, oct);
            haze = smoothstep(-0.25, 0.55, haze + 0.1 * uBreath);
            haze *= exp(-rd * (0.6 - 0.3 * I));               // the haze clings to the light

            // rings: one born on each inhale, drifting outward slowly; they need intensity to be seen
            float k = 9.0 + 7.0 * I;
            float phase = rd * k - uTime * 0.25 - 0.6 * uBreath;
            float ring = 0.5 + 0.5 * cos(phase);
            ring = pow(ring, 6.0 + 6.0 * I);
            ring *= exp(-rd * 1.0) * smoothstep(0.08, 0.35, rd);

            vec3 col = cHaze * haze * (2.0 * I * (0.6 + 0.4 * uBreath));
            col += cRing * ring * (1.4 * I * (0.6 + 0.4 * uBreath));
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the breathing core is the only light at zero intensity
            col += cCore * core * (0.04 + 0.10 * uBreath) * (1.0 + 4.0 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 2 – Bloom.  Nested translucent petals opening on the inhale.  Layer count (3 + 5·intensity)
     * and petal count are fractional: petal shapes are a blend of two neighbouring integer
     * harmonics, so the flower gains petals continuously.
     */
    private val BLOOM = """
        // Bloom: a flower of nested translucent petal rings.  Each layer is a polar curve
        // r = base * (1 + depth * cos(n * a)); a fractional petal count is handled by blending the
        // cos(n) and cos(n + 1) curves, so petals can grow in number without a visible step.  Layers open
        // outward on the inhale and turn slowly in alternating directions.

        // petal modulation for a fractional petal count n at angle a
        float petal(float a, float n) {
            float n0 = floor(n);
            float f = n - n0;
            return mix(cos(n0 * a), cos((n0 + 1.0) * a), f);
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0171 + 0.3);
            float bw = breathWave();
            float t = uTime;
            float r = length(p);
            float pal = slowDrift(uSeed * 0.05) * 0.4;

            // a slow, gentle domain warp so the flower is never perfectly rigid
            vec2 pw = p + 0.05 * I * vec2(gnoise(p * 1.5 + t * 0.01), gnoise(p * 1.5 - t * 0.008 + 4.3));

            float layers = 3.0 + 5.0 * I;
            float petals = 4.0 + 2.5 * I + 0.5 * sin(t * 0.007 + uSeed * 0.2);
            float open = 0.82 + 0.16 * uBreath;              // the whole flower opens on the inhale

            vec3 col = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float w = clamp(layers - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                float fi = float(i);
                float dir = (mod(fi, 2.0) < 1.0) ? 1.0 : -1.0;
                float ang = dir * (t * 0.012 + fi * 0.37) + uSeed * 0.01 + 0.03 * bw * dir;
                vec2 q = rot2(ang) * pw;
                float a = atan(q.y, q.x + 1e-6);
                float n = petals + fi * 0.5;
                float base = (0.18 + 0.11 * fi) * open;
                float depth = 0.22 + 0.10 * I;
                float rk = base * (1.0 + depth * petal(a, n));
                float sd = length(q) - rk;
                // a soft translucent fill inside the petal edge plus a thin bright rim
                float fill = smoothstep(0.02, -0.25, sd) * 0.18;
                float rim = exp(-abs(sd) * (24.0 + 20.0 * I)) * 0.9;
                vec3 c = calmPalette(pal + fi * 0.09 + 0.02 * a, seedK);
                col += (fill + rim) * c * w * (0.55 + 0.45 * exp(-fi * 0.18));
            }

            col *= 0.6;
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the flower's heart breathes at zero intensity
            float core = exp(-r * r / (0.05 + 0.02 * uBreath));
            col += (calmPalette(pal + 0.5, seedK) * 0.5 + 0.5) * core * (0.02 + 0.07 * uBreath) * (1.0 + 2.0 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 3 – Mandala.  A kaleidoscopic fold (an integer wedge count fixed per session) around a
     * radially symmetric IFS.  Orbit traps light concentric rings and spokes; the iteration depth
     * (3 + 7·intensity, fractional) adds finer tiers as the session deepens.  The wheel turns
     * slowly and breathes in scale.
     */
    private val MANDALA = """
        // Mandala: the plane is folded into n mirrored wedges (n is an integer fixed per session; a
        // fractional count would leave a seam), then an IFS of fold-rotate-scale steps runs on the wedge.
        // Orbit traps on rings (|length - 1|) and spokes (|y|) light the figure; deeper iterations fade in
        // with intensity, so tiers of ornament appear one at a time from the centre outward.

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0233 + 0.6);
            float bw = breathWave();
            float t = uTime;
            float r = length(p);

            float n = floor(6.0 + 5.99 * hash11(uSeed + 3.3));
            // slow rotation; the inhale swells the whole wheel slightly
            vec2 q = rot2(t * 0.01 + uSeed * 0.02 + 0.02 * bw) * p;
            q /= 1.0 + 0.06 * uBreath;
            q = kaleido(q, n);
            q *= 1.6;

            // IFS parameters wander on multi-minute scales
            vec2 off = vec2(0.72 + 0.16 * sin(t * 0.0061 + uSeed * 0.11),
                            0.42 + 0.14 * cos(t * 0.0047 + uSeed * 0.23)) + 0.02 * bw;
            float ang = 0.35 + 0.30 * sin(t * 0.0039 + uSeed * 0.05) + 0.015 * bw;
            float sc = 1.20 + 0.08 * sin(t * 0.0029 + uSeed * 0.37);
            mat2 m = rot2(ang);
            float iters = 3.0 + 7.0 * I;

            float tR = 8.0, tS = 8.0, tO = 8.0;
            for (int i = 0; i < 10; i++) {
                float w = clamp(iters - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                q = abs(q) - off;
                q = m * q;
                q *= sc;
                float l = length(q);
                tR = min(tR, mix(8.0, abs(l - 1.0), w));
                tS = min(tS, mix(8.0, abs(q.y), w));
                tO = min(tO, mix(8.0, l, w));
            }

            float rings = exp(-tR * (9.0 + 6.0 * I));
            float spokes = exp(-tS * (12.0 + 8.0 * I));
            float pool = exp(-tO * 2.0);

            // concentric breathing halo bands beneath the ornament
            float band = 0.5 + 0.5 * cos(r * (8.0 + 4.0 * I) - 0.8 * uBreath - t * 0.05);
            band = pow(band, 4.0) * exp(-r * 1.2);

            float idx = tO * 0.7 + tR * 0.4 + r * 0.15 + slowDrift(uSeed * 0.04) * 0.4;
            vec3 cOrn = calmPalette(idx, seedK) * 0.6 + 0.4;
            vec3 cPool = calmPalette(idx + 0.35, seedK);
            vec3 cBand = calmPalette(idx + 0.65, seedK);
            vec3 col = cOrn * (rings * 0.55 + spokes * 0.35) + cPool * pool * 0.35 + cBand * band * 0.25;
            col *= exp(-r * 0.5);                             // the wheel's edge sinks into darkness
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the hub breathes at zero intensity
            col += cPool * (0.02 + 0.06 * uBreath) * exp(-r * r * 4.0) * (1.0 + 2.0 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 4 – Lumen.  Interference of light from a few slowly orbiting point sources (3 + 5·intensity,
     * fractional).  Wavelength stretches on the inhale; chromatic aberration separates the three
     * channels' wavelengths as intensity grows, so the fringes turn iridescent.
     */
    private val LUMEN = """
        // Lumen: the interference pattern of coherent light from a handful of point sources drifting
        // on slow orbits.  The field is a weighted sum of radial sine waves; each colour channel sees a
        // slightly different wavelength (growing with intensity) so the fringes become iridescent.  The
        // source count is fractional: the newest source fades in with intensity.

        // the interference field for one wavelength scale
        float field(vec2 p, float k, float sources, float t) {
            float s = 0.0;
            for (int i = 0; i < 8; i++) {
                float w = clamp(sources - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                float fi = float(i);
                float h = hash11(fi * 13.7 + uSeed * 0.1);
                float orbit = 0.25 + 0.5 * h;
                float ang = t * (0.006 + 0.008 * h) * ((mod(fi, 2.0) < 1.0) ? 1.0 : -1.0) + fi * 2.1 + uSeed * 0.07;
                vec2 src = vec2(cos(ang), sin(ang)) * orbit;
                float d = length(p - src);
                s += w * sin(d * k - t * 0.35 + fi * 1.3) / (1.0 + d * 0.8);
            }
            return s;
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0191 + 0.85);
            float t = uTime;
            float r = length(p);

            // slow low-frequency warp of the medium
            vec2 pw = p + (0.06 + 0.1 * I) * vec2(gnoise(p * 1.2 + t * 0.007), gnoise(p * 1.2 - t * 0.005 + 9.1));

            float sources = 3.0 + 5.0 * I;
            float k = (16.0 + 10.0 * I) * (1.0 - 0.06 * uBreath);   // the inhale stretches the wavelength
            float ca = 0.02 + 0.10 * I;                              // chromatic aberration
            float fR = field(pw, k * (1.0 - ca), sources, t);
            float fG = field(pw, k, sources, t);
            float fB = field(pw, k * (1.0 + ca), sources, t);
            float norm = 1.0 / sqrt(sources);
            vec3 f = vec3(fR, fG, fB) * norm;                        // roughly -1..1 per channel

            // bright fringes where waves add; soft dark where they cancel
            vec3 fr = smoothstep(-0.2, 0.9, f);
            fr = fr * fr;
            float idx = fG * norm * 0.25 + r * 0.1 + slowDrift(uSeed * 0.03) * 0.4;
            vec3 tint = calmPalette(idx, seedK);
            vec3 col = fr * (tint * 0.7 + 0.3);
            col *= 0.6 * (0.7 + 0.3 * uBreath);
            col *= exp(-r * 0.6);
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // a soft lamp at the centre breathes at zero intensity
            col += (tint * 0.5 + 0.5) * (0.02 + 0.06 * uBreath) * exp(-r * r * 3.0) * (1.0 + 2.0 * I);
            return col;
        }
    """.trimIndent()

    /** The four movements, in programme order (1..4). */
    val list: List<Movement> = listOf(
        Movement("Threshold", THRESHOLD),
        Movement("Bloom", BLOOM),
        Movement("Mandala", MANDALA),
        Movement("Lumen", LUMEN),
    )
}
