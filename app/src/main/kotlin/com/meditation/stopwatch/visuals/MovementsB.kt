package com.meditation.stopwatch.visuals

/**
 * Movements 5–8: the deeper half of the programme, normally reached once the session intensity is
 * already around 0.5–1.0.  They still obey every rule in [Movement] (near-black at zero intensity
 * with only a breathing glow, breath-locked motion, no strobing) because the programme loops.
 *
 * Every body is a GLSL ES 3.00 fragment shader body appended to [Glsl.PRELUDE]; each has been
 * validated with tools/validate-shader.sh.  Shared conventions:
 *  - Iteration / slice / octave counts are *fractional* and driven by uIntensity: the last partial
 *    step is blended in with a 0..1 weight so that nothing ever changes by a visible step.
 *  - Loop bounds are compile-time constants with an early `break`, as mobile GPUs require.
 *  - All motion faster than the breath is either sub-pixel (grain, dither) or absent.
 */
object MovementsB {

    /**
     * 5 – Descent.  An endless log-polar tunnel whose walls carry a kaleidoscopic IFS ornament.
     * The inhale draws the walls inward, the exhale (plus a slow constant drift) pushes them
     * outward; twist and chromatic aberration grow with intensity; fog swallows the far end.
     */
    private val DESCENT = """
        // Descent: an endless log-polar tunnel.
        // Screen space is mapped to (depth = log radius, angle), so a pattern of fixed size in that space
        // appears as perspective-correct rings receding to the vanishing point.  The wall ornament is a
        // kaleidoscopic IFS evaluated on seamless, mirrored (triangle-wave) tunnel coordinates.  The walls
        // are pulled inward while inhaling and slide outward during the exhale, with a net forward drift.

        // Kaleidoscopic IFS orbit traps in wall space.  Returns (cell trap, vein trap).
        // 'iters' is fractional: the last partial iteration is blended in through 'w' so the iteration
        // count can follow uIntensity without a visible step.
        vec2 tunnelTrap(vec2 q, float iters, vec2 off, mat2 m, float sc) {
            float trapC = 4.0, trapV = 4.0;
            for (int i = 0; i < 12; i++) {
                float w = clamp(iters - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                q = abs(q) - off;
                q = m * q;
                q *= sc;
                trapC = min(trapC, mix(4.0, length(q), w));
                trapV = min(trapV, mix(4.0, abs(q.y), w));
            }
            return vec2(trapC, trapV);
        }

        // One wall sample at tunnel depth 'dc'.  Returns (cell glow, vein glow, cell trap for colouring).
        vec3 wallSample(float dc, float ang01, float warp, float iters, vec2 off, mat2 m, float sc) {
            // mirrored, continuous tiling along the tunnel (period 1/0.7 in log space)
            float dep01 = abs(fract(dc * 0.7) - 0.5) * 2.0;
            vec2 q = vec2(dep01, ang01) - 0.5;
            q += warp * vec2(0.35, 0.25);
            vec2 t = tunnelTrap(q, iters, off, m, sc);
            float cell = 1.0 - smoothstep(0.0, 0.9, t.x);
            float vein = exp(-t.y * 9.0);
            return vec3(cell, vein, t.x);
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float bw = breathWave();
            float seedK = fract(uSeed * 0.0173);
            // number of mirrored wedges is an integer fixed per session: fractional counts would leave a seam
            float n = floor(5.0 + 3.99 * hash11(uSeed + 7.7));

            float r = length(p);
            float u = log(max(r, 1e-4));
            float v = atan(p.y, p.x + 1e-6);      // +1e-6: atan(0, 0) is undefined on some GPUs

            // twist (grows with intensity, slowly reversing) and a very slow global rotation
            float twist = (0.15 + 0.85 * I) * sin(uTime * TAU / 140.0 + uSeed * 0.11);
            v += twist * u + uTime * 0.015;

            // breath-locked drift in log space: inhale pulls the walls inward, time pushes them outward
            float drift = uTime * 0.02 - (0.22 + 0.10 * I) * uBreath;
            float depth = u - drift;

            // seamless angular coordinate (triangle wave: mirrored, continuous at every wrap)
            float ang01 = abs(fract(v * n / TAU) - 0.5) * 2.0;
            // low-frequency organic variation along the tunnel, periodic in angle by construction
            float warp = vnoise3(vec3(depth * 0.35, cos(v) * 0.9, sin(v) * 0.9)) - 0.5;

            // IFS parameters: glacial drift plus a breath-sized tremor
            vec2 off = vec2(0.62 + 0.12 * sin(uTime * 0.021 + uSeed * 0.31),
                            0.48 + 0.12 * cos(uTime * 0.017 + uSeed * 0.53)) + 0.03 * bw;
            float ang = 0.55 + 0.45 * sin(uTime * 0.013 + uSeed * 0.07) + 0.04 * bw;
            mat2 m = rot2(ang);
            float sc = 1.22 + 0.12 * I;
            float iters = 5.0 + 7.0 * I;

            // chromatic aberration: the red and blue walls sit at slightly different depths
            float ca = 0.004 + 0.035 * I;
            vec3 sR = wallSample(depth + ca, ang01, warp, iters, off, m, sc);
            vec3 sG = wallSample(depth,      ang01, warp, iters, off, m, sc);
            vec3 sB = wallSample(depth - ca, ang01, warp, iters, off, m, sc);
            vec3 lumCell = vec3(sR.x, sG.x, sB.x);
            vec3 lumVein = vec3(sR.y, sG.y, sB.y);

            float idx = sG.z * 1.6 + depth * 0.08 + slowDrift(uSeed * 0.02) * 0.5;
            vec3 wallCol = calmPalette(idx, seedK);
            vec3 veinCol = calmPalette(idx + 0.33, seedK) * 0.7 + 0.4;
            vec3 col = wallCol * lumCell * 0.55 + veinCol * lumVein * 0.9;

            // fog: the far tunnel (small radius) sinks into darkness; intensity lets us see deeper
            float vis = 1.0 - exp(-r * (3.0 + 3.0 * I));
            col *= vis;
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the breathing light at the end of the tunnel: the only thing visible at zero intensity
            vec3 fogCol = calmPalette(idx * 0.2 + 0.1, seedK);
            col += fogCol * (0.02 + 0.06 * uBreath) * exp(-r * r * 6.0) * (1.0 + 2.0 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 6 – Nebula.  A slow drift through volumetric cloud slices (8 + 16·intensity, fractional)
     * over a star field.  Filaments come from the zero-set of the same fbm3 that gives density.
     * The inhale lowers the density threshold and raises the glow, so the clouds swell and light up.
     */
    private val NEBULA = """
        // Nebula: a slow flight through volumetric cloud slices, with a star field behind.
        // Density is fbm3 of a drifting 3D position.  The zero-set of the same noise (1 - |n|) yields thin
        // ridged filaments for free.  The slice count follows intensity with a fractional last slice, so
        // the march deepens without a visible step.  A single 2D fbm pass adds fine grain after the march.

        // A grid of tiny stars.  'thr' lowers with intensity so the sky fills gradually; twinkle < 0.3 Hz.
        vec3 starLayer(vec2 sp, float scale, float thr, float salt) {
            vec2 g = sp * scale;
            vec2 cell = floor(g);
            vec2 f = fract(g);
            float h = hash21(cell + salt);
            vec2 pos = hash22(cell + salt * 1.7) * 0.7 + 0.15;   // keep the star away from cell edges
            float d = length(f - pos);
            float on = smoothstep(thr - 0.02, thr + 0.01, h);
            float tw = 0.75 + 0.25 * sin(uTime * (0.8 + 1.0 * hash11(h * 91.7)) + h * TAU);
            float star = exp(-d * d * 260.0) * on * tw;
            vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.9, 0.75), hash11(h * 37.1));
            return star * tint;
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0193 + 0.5);
            float t = uTime;

            // camera: slow drift forward with a gentle sway and roll
            vec3 ro = vec3(sin(t * 0.017 + uSeed) * 0.6, cos(t * 0.013 + uSeed * 0.7) * 0.4, t * 0.035);
            vec2 pr = rot2(0.08 * sin(t * 0.009 + uSeed * 0.3)) * p;
            vec3 rd = normalize(vec3(pr * 0.8, 1.4));

            // two-tone cloud palette plus a pale filament colour
            float pal = slowDrift(uSeed * 0.3) * 0.5;
            vec3 cA = calmPalette(pal + 0.05, seedK);
            vec3 cB = calmPalette(pal + 0.42, seedK);
            vec3 cF = calmPalette(pal + 0.80, seedK) * 0.5 + 0.5;

            float slices = 8.0 + 16.0 * I;
            float tNear = 0.5, tFar = 4.5;
            float dt = (tFar - tNear) / slices;
            // inhale: clouds swell (lower threshold) and glow
            float thr = 0.12 - 0.12 * uBreath - 0.08 * I;
            float glowB = 0.65 + 0.5 * uBreath;
            float filGain = 0.6 * (0.3 + 0.7 * I);
            // static per-pixel start jitter hides slice banding (sub-pixel, not animated)
            float jit = hash21(uv * uResolution.xy) * 0.9;

            vec3 acc = vec3(0.0);
            float alpha = 0.0;
            for (int i = 0; i < 24; i++) {
                float w = clamp(slices - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                float tt = tNear + (float(i) + jit) * dt;
                vec3 q = ro + rd * tt;
                float n = fbm3(q * 0.6, 2);                              // about -0.75..0.75
                float dens = smoothstep(thr, thr + 0.55, n) * w;
                float rid = max(1.0 - abs(n) * 6.0, 0.0);
                float fil = rid * rid * rid * w;                          // membranes on the zero-set of n
                float shade = 0.45 + 0.55 * exp(-(tt - tNear) * 0.35);    // nearer slices are lit more
                vec3 cc = mix(cA, cB, smoothstep(-0.3, 0.4, n + 0.15 * sin(tt * 0.7 + uSeed)));
                vec3 c = cc * (shade * glowB * dens) + cF * (fil * filGain);
                float a = dens * dt * (0.55 + 0.35 * I);
                acc += (1.0 - alpha) * c * dt;
                alpha += (1.0 - alpha) * a;
                if (alpha > 0.985) break;
            }

            // fine 2D grain over the accumulated volume (one cheap pass instead of extra octaves per slice)
            float det = fbm(pr * 5.0 + vec2(t * 0.02, -t * 0.013), 3);
            acc *= 0.85 + 0.35 * det;

            // stars behind the clouds; they are far away, so their parallax is tiny
            vec2 sp = rd.xy / max(rd.z, 0.2) + ro.xy * 0.03;
            float starThr = 0.986 - 0.025 * I;
            vec3 stars = starLayer(sp, 26.0, starThr, 0.0) + starLayer(sp + 3.7, 47.0, starThr + 0.004, 5.0) * 0.6;
            stars *= (1.0 - alpha);

            vec3 col = acc * 1.6 + stars * 0.8;
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the nebula's heart breathes even at zero intensity, wandering slowly across the sky
            vec2 heart = vec2(sin(t * 0.007 + uSeed * 0.2), cos(t * 0.005 + uSeed * 0.4)) * 0.35;
            vec2 hd = p - heart;
            col += cA * (0.02 + 0.06 * uBreath) * exp(-dot(hd, hd) * 2.5) * (1.0 + 1.5 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 7 – Cathedral.  A bilaterally symmetric kaleidoscopic IFS (6 + 8·intensity folds, fractional)
     * lit by orbit traps: origin, unit circle and axis.  The whole figure turns as one rigid body,
     * opens slightly on full lungs, and its parameters wander on multi-minute time scales.
     */
    private val CATHEDRAL = """
        // Cathedral: a bilaterally symmetric kaleidoscopic IFS.  Each iteration folds the plane and
        // rotates it, so arches nest inside arches.  Orbit traps (closest approach to the origin, to a
        // unit circle and to the axis) light the structure; deeper iterations fade in with intensity, so
        // the architecture rises out of the dark one layer at a time.

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0311 + 0.25);
            float bw = breathWave();
            float t = uTime;

            // slow global rotation, then the nave axis: the whole figure turns as a rigid symmetric body
            vec2 q = rot2(t * 0.012 + uSeed * 0.01) * p;
            q /= 1.0 + 0.05 * uBreath;                          // full lungs: the nave opens
            q *= 1.15;
            q.x = abs(q.x);

            // IFS parameters: glacial drift plus a breath-sized tremor
            vec2 off = vec2(0.95 + 0.18 * sin(t * 0.0071 + uSeed * 0.13),
                            0.55 + 0.18 * cos(t * 0.0053 + uSeed * 0.29)) + 0.025 * bw;
            float ang = 0.62 + 0.40 * sin(t * 0.0043 + uSeed * 0.07) + 0.02 * bw;
            float sc = 1.18 + 0.10 * sin(t * 0.0037 + uSeed * 0.41);
            float iters = 6.0 + 8.0 * I;
            mat2 m = rot2(ang);

            // orbit traps; the fractional last iteration is blended in through 'w'
            float tO = 8.0, tC = 8.0, tL = 8.0;
            for (int i = 0; i < 14; i++) {
                float w = clamp(iters - float(i), 0.0, 1.0);
                if (w <= 0.0) break;
                q = abs(q) - off;
                q = m * q;
                q *= sc;
                float l = length(q);
                tO = min(tO, mix(8.0, l, w));
                tC = min(tC, mix(8.0, abs(l - 1.0), w));
                tL = min(tL, mix(8.0, abs(q.x), w));
            }

            float glow = exp(-tO * 2.2);          // light pooling where orbits approach the origin
            float arch = exp(-tC * 12.0);         // thin arcs: the circle trap
            float rib  = exp(-tL * 16.0);         // thin ribs: the axis trap

            float idx = tO * 0.9 + tC * 0.5 + slowDrift(uSeed * 0.02) * 0.4;
            vec3 stone = calmPalette(idx, seedK);
            vec3 light = calmPalette(idx + 0.4, seedK) * 0.6 + 0.4;
            vec3 col = stone * (glow * 0.45) + light * (arch * 0.7 + rib * 0.5);
            col = saturate3(col, 0.55 + 0.6 * I);
            col *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // a dim rose window breathes at the crossing: the only light at zero intensity
            col += calmPalette(idx * 0.3 + 0.2, seedK) * (0.018 + 0.05 * uBreath) * exp(-dot(p, p) * 2.5) * (1.0 + 1.5 * I);
            return col;
        }
    """.trimIndent()

    /**
     * 8 – Tide.  Ink in still water: three chained fbm domain warps advected by the breath and
     * stirred by a bounded, reversing vortex about a wandering centre.  A second sample of the
     * chain estimates the field gradient, which turns the ink boundary into a thin isoline.
     */
    private val TIDE = """
        // Tide: ink diffusing in still water.  Three chained fbm domain warps (Quilez) are advected by
        // the breath and stirred by a slow, bounded vortex around a wandering centre.  A second sample of
        // the whole chain approximates the field gradient, which turns the ink boundary into a thin
        // screen-space isoline (distance ~ |f - level| / |grad f|).

        // fbm with a fractional octave count so warp depth can follow intensity without steps
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

        // The warped field.  Returns (f, |q|, |r|) so the warp layers can pick the ink colours.
        // The breath advection is applied more to the outer layers than the inner, so the layers slide
        // against each other and the inhale reads as flow rather than as the picture translating.
        vec3 inkField(vec2 P, vec2 adv, float oct, float amp, float t) {
            vec2 q = vec2(fbmF(P + adv + vec2(t * 0.7, t * 0.3), oct),
                          fbmF(P + adv + vec2(5.2, 1.3) - vec2(t * 0.4, t * 0.6), oct));
            vec2 r = vec2(fbmF(P + 0.5 * adv + amp * q + vec2(1.7, 9.2) + vec2(t * 0.5, -t * 0.2), oct),
                          fbmF(P + 0.5 * adv + amp * q + vec2(8.3, 2.8) - vec2(t * 0.3, t * 0.5), oct));
            float f = fbmF(P + amp * r, oct);
            return vec3(f, length(q), length(r));
        }

        vec3 scene(vec2 p, vec2 uv) {
            float I = uIntensity;
            float seedK = fract(uSeed * 0.0271 + 0.75);
            float bw = breathWave();
            float t = uTime * 0.03;

            // wandering vortex centre; the swirl angle is bounded (it reverses over ~80 s) and breath-led
            vec2 c = vec2(sin(uTime * 0.011 + uSeed * 0.17) * 0.55, cos(uTime * 0.008 + uSeed * 0.31) * 0.4);
            vec2 d = p - c;
            float rr = dot(d, d);
            float swirl = (0.35 + 1.4 * I) * (0.7 * sin(uTime * TAU / 80.0 + uSeed * 0.05) + 0.3 * bw);
            float angv = swirl / (0.35 + rr * 2.0);
            vec2 P = (c + rot2(angv) * d) * 1.3;

            // breath advection along a slowly turning direction
            float da = uTime * 0.02 + uSeed * 0.09;
            vec2 adv = vec2(cos(da), sin(da)) * uBreath * (0.12 + 0.12 * I);

            float oct = 3.5 + 1.5 * I;
            float amp = 2.2 + 2.0 * I;
            vec3 F = inkField(P, adv, oct, amp, t);

            // second sample a couple of pixels away (diagonal), for the gradient magnitude
            float px = 2.0 / uResolution.y;                 // one pixel in p units
            float eps = 2.0 * px * 1.3;                     // P is p scaled by 1.3
            vec3 F2 = inkField(P + vec2(eps, eps), adv, oct, amp, t);
            float grad = abs(F2.x - F.x) / eps;             // |df/ds| per unit p, along the diagonal
            float f = F.x;

            // ink boundary as a constant-width isoline: distance ~ |f - level| / |grad f|
            float level = -0.05;
            float sd = abs(f - level) / max(grad, 0.4);
            float edge = 1.0 - smoothstep(0.0, 2.5 * px, sd);

            // four luminous inks chosen by the warp layers
            float slow = slowDrift(uSeed * 0.03) * 0.3;
            vec3 c1 = calmPalette(slow + 0.00, seedK);
            vec3 c2 = calmPalette(slow + 0.28, seedK);
            vec3 c3 = calmPalette(slow + 0.55, seedK);
            vec3 c4 = calmPalette(slow + 0.80, seedK);
            vec3 col = mix(c1, c2, smoothstep(-0.5, 0.5, f));
            col = mix(col, c3, clamp(F.y * 1.6, 0.0, 1.0));
            col = mix(col, c4, clamp(F.z * 1.4 - 0.2, 0.0, 1.0));

            // dark water, luminous ink; the inhale brightens the ink a little
            float ink = smoothstep(-0.25, 0.45, f + 0.08 * uBreath);
            float lum = ink * ink * (0.5 + 0.5 * ink);
            vec3 outc = col * (lum * 0.9);
            outc += (c4 * 0.5 + 0.5) * (edge * (0.2 + 0.6 * I) * (0.6 + 0.4 * uBreath));
            outc = saturate3(outc, 0.5 + 0.7 * I);
            outc *= smoothstep(0.0, 0.25, I) * (0.6 + 0.4 * I);

            // the vortex centre breathes faintly: the only light at zero intensity
            outc += calmPalette(slow + 0.1, seedK) * (0.018 + 0.05 * uBreath) * exp(-rr * 1.5) * (1.0 + 1.5 * I);
            return outc;
        }
    """.trimIndent()

    /** The four movements, in programme order (5..8). */
    val list: List<Movement> = listOf(
        Movement("Descent", DESCENT),
        Movement("Nebula", NEBULA),
        Movement("Cathedral", CATHEDRAL),
        Movement("Tide", TIDE),
    )
}
