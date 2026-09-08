package com.meditation.stopwatch.visuals

/**
 * One "movement" of the visual composition: a full-screen GLSL ES 3.00 fragment shader body.
 *
 * [body] is appended after [Glsl.PRELUDE] and must define
 *     vec3 scene(vec2 p, vec2 uv)
 * where  p  = centred, aspect-corrected coordinates (y in -1..1, x scaled by aspect)
 *        uv = raw 0..1 screen coordinates.
 * Return linear-ish RGB in roughly 0..1 (the prelude tonemaps, vignettes and applies uFade).
 *
 * All uniforms and helper functions listed in [Glsl.PRELUDE] are available.
 * Design rules for every movement:
 *   - At uIntensity == 0 the scene must be almost black: only a faint, breathing glow (uBreath).
 *   - Motion must be slow.  The dominant rhythm is uBreath / uBreathPhase (one cycle = one breath).
 *   - uIntensity raises: iteration counts, colour saturation, fold/kaleidoscope order, warp depth,
 *     chromatic aberration, particle counts – gradually, never with visible steps.
 *   - Avoid strobing: nothing should change faster than ~0.5 Hz except sub-pixel shimmer.
 */
data class Movement(val name: String, val body: String)
