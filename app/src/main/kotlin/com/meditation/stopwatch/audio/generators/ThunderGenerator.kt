package com.meditation.stopwatch.audio.generators

import com.meditation.stopwatch.audio.Biquad
import com.meditation.stopwatch.audio.FastRandom
import com.meditation.stopwatch.audio.Lfo
import com.meditation.stopwatch.audio.OnePole
import com.meditation.stopwatch.audio.Ramp
import com.meditation.stopwatch.audio.RenderContext
import com.meditation.stopwatch.audio.SoundGenerator
import com.meditation.stopwatch.audio.SoundId
import com.meditation.stopwatch.audio.dbToGain
import com.meditation.stopwatch.audio.panGains
import com.meditation.stopwatch.audio.softClip
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distant thunderstorms.
 *
 * Layers
 *  - A continuous, nearly inaudible (-45 dBFS) rumble bed – brown noise low-passed at ~55 Hz with a
 *    slow amplitude wander and a very slow pan drift – so the sound never feels switched off.
 *  - Events every 14–55 s (Poisson tail above a 14 s floor; the mean shrinks ~15 % with session
 *    intensity).  Each event is a 5–14 s rumble made of 2–5 overlapping sub-bursts.  A sub-burst is
 *    its own white-noise stream, integrated to brown, low-passed at 60–400 Hz, shaped by a one-pole
 *    attack + exponential decay, "rolled" by a smoothed random amplitude walk and panned with a slow
 *    drift across the field.  ~45 % of events open with a sharper crack: white noise low-passed at
 *    0.7–1.5 kHz with a 40–150 ms attack.
 *  - A sub-bass sine (30–70 Hz, gliding down slightly over the event) whose amplitude follows the
 *    smoothed sum of the burst envelopes.
 *
 * The first event is scheduled 5–12 s after the generator is (re)started so users hear that the
 * sound works.  A restart is detected either by [reset] or by a wall-clock gap between two render
 * calls (the engine may stop rendering silent sounds); wall-clock only affects event scheduling.
 *
 * Levels: a rumble peaks around -20 dBFS RMS; [softClip] guards the sum of overlapping bursts, but
 * nominal peaks stay under ~0.45.
 */
class ThunderGenerator : SoundGenerator {
    override val id: SoundId = SoundId.THUNDER

    private var sr = 48_000
    private var initialised = false
    private val rng = FastRandom(0x7A11D3A5C0FFEE01L)
    private val pan = FloatArray(2)

    // ---- Distant bed --------------------------------------------------------------------------
    private val bedBrown = OnePole()
    private val bedLp = OnePole()
    private var bedNorm = 0f
    private val bedLfoA = Lfo(0.031f)
    private val bedLfoB = Lfo(0.0073f, 0.41f)
    private val bedPanLfo = Lfo(0.017f, 0.2f)
    private val bedL = Ramp()
    private val bedR = Ramp()

    // ---- Sub-bass ("magic circle" sine oscillator: s += k*c; c -= k*s) ------------------------
    private var subS = 0f
    private var subC = 1f
    private var subK = 0f
    private var subF0 = 45f
    private var subGlide = 0.2f
    private var subAmp = 0f
    private var subAge = 0
    private var subGlideSamples = 1
    /** Smoothed loudness envelope of all bursts (per-sample one-pole) driving the sub level. */
    private val subEnv = OnePole()
    private val subGain = Ramp()

    // ---- Bursts -------------------------------------------------------------------------------
    private class Burst {
        var active = false
        /** Samples until the burst begins (bursts are staggered inside one event). */
        var startIn = 0
        var age = 0
        var attackSamples = 0
        var attackK = 0f
        var decayK = 0f
        var env = 0f
        /** Amplitude including the filter normalisation. */
        var amp = 0f
        var useBrown = true
        val brown = OnePole()
        val lp = Biquad()
        var pan = 0f
        /** Pan drift per sample. */
        var panVel = 0f
        val gl = Ramp()
        val gr = Ramp()
        // "Rolling": a random target refreshed every 100–450 ms, smoothed by a ~1.2 Hz one-pole.
        var rollDepth = 0f
        var rollTarget = 0f
        var rollIn = 0
        val rollLp = OnePole()
    }
    private val bursts = Array(MAX_BURSTS) { Burst() }

    // ---- Scheduling ---------------------------------------------------------------------------
    private var nextEventIn = 0
    private var armed = false
    private var lastRenderNanos = 0L

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        initialised = true
        armed = false
        bedBrown.setCutoff(BED_BROWN_HZ, sr); bedBrown.set(0f)
        bedLp.setCutoff(BED_LP_HZ, sr); bedLp.set(0f)
        bedNorm = dbToGain(BED_DB) / (brownRms(bedBrown.k) * sqrt(lpFraction(BED_LP_HZ, BED_BROWN_HZ)))
        bedL.set(0f); bedR.set(0f)
        subS = 0f; subC = 1f; subK = 0f; subAmp = 0f; subAge = 0
        subEnv.setCutoff(SUB_ENV_HZ, sr); subEnv.set(0f)
        subGain.set(0f)
        for (b in bursts) {
            b.active = false; b.env = 0f
            b.gl.set(0f); b.gr.set(0f)
            b.rollLp.setCutoff(ROLL_HZ, sr); b.rollLp.set(0f)
            b.lp.reset()
        }
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        if (!initialised || ctx.sampleRate != sr) reset(ctx.sampleRate)
        if (frames <= 0) return
        val n = frames

        // (Re)start detection: first render after reset, or a gap in render calls.
        val now = System.nanoTime()
        if (!armed || now - lastRenderNanos > REENABLE_GAP_NS) {
            scheduleFirst()
            armed = true
        }
        lastRenderNanos = now

        // ---- Block-rate scheduling and parameter updates ----
        nextEventIn -= n
        if (nextEventIn <= 0) {
            spawnEvent()
            nextEventIn = nextInterval(ctx.intensity)
        }

        val bedMod = 1f + 0.35f * bedLfoA.nextBlock(n, sr) + 0.2f * bedLfoB.nextBlock(n, sr)
        panGains(0.35f * bedPanLfo.nextBlock(n, sr), pan)
        val bg = bedNorm * bedMod * (1f + 0.1f * ctx.intensity)
        bedL.target(bg * pan[0], n)
        bedR.target(bg * pan[1], n)

        if (subAmp > 0f) {
            val prog = (subAge.toFloat() / subGlideSamples).coerceAtMost(1f)
            val f = subF0 * (1f - subGlide * prog)
            subK = 2f * sin((PI * f / sr).toFloat())
            subAge += n
        }
        subGain.target(subAmp * (1f + 0.1f * ctx.intensity), n)

        for (b in bursts) {
            if (!b.active) continue
            b.pan = (b.pan + b.panVel * n).coerceIn(-1f, 1f)
            panGains(b.pan, pan)
            b.gl.target(b.amp * pan[0], n)
            b.gr.target(b.amp * pan[1], n)
        }

        // ---- Per-sample ----
        var i = 0
        for (f in 0 until n) {
            val bed = bedLp.process(bedBrown.process(rng.nextSigned()))
            var l = bed * bedL.next()
            var r = bed * bedR.next()
            var envSum = 0f
            for (b in bursts) {
                if (!b.active) continue
                if (b.startIn > 0) { b.startIn--; continue }
                if (b.age < b.attackSamples) {
                    b.env += b.attackK * (1f - b.env)
                } else {
                    b.env *= b.decayK
                    if (b.env < END_ENV) { b.active = false; b.env = 0f; continue }
                }
                b.age++
                if (--b.rollIn <= 0) {
                    b.rollTarget = rng.nextSigned()
                    b.rollIn = (sr * (0.1f + 0.35f * rng.nextFloat())).toInt()
                }
                val roll = 1f + b.rollDepth * b.rollLp.process(b.rollTarget)
                val src = if (b.useBrown) b.brown.process(rng.nextSigned()) else rng.nextSigned()
                val e = b.env * roll
                val s = b.lp.process(src) * e
                envSum += e
                l += s * b.gl.next()
                r += s * b.gr.next()
            }
            // Sub-bass follows the (clamped) summed loudness envelope.
            subS += subK * subC
            subC -= subK * subS
            val se = subEnv.process(if (envSum > 1f) 1f else envSum)
            val sub = subS * se * subGain.next()
            l += sub
            r += sub
            out[i] = softClip(l)
            out[i + 1] = softClip(r)
            i += 2
        }
    }

    // ---- Event construction -------------------------------------------------------------------

    private fun scheduleFirst() {
        nextEventIn = ((5f + 7f * rng.nextFloat()) * sr).toInt()
    }

    /** Poisson-style interval: 14 s floor plus an exponential tail, capped at 55 s. */
    private fun nextInterval(intensity: Float): Int {
        val mean = MEAN_INTERVAL * (1f - 0.15f * intensity)
        val extra = (mean - MIN_INTERVAL) * -ln(1f - rng.nextFloat())
        return ((MIN_INTERVAL + extra).coerceAtMost(MAX_INTERVAL) * sr).toInt()
    }

    private fun freeBurst(): Burst? {
        for (b in bursts) if (!b.active) return b
        return null
    }

    private fun spawnEvent() {
        val duration = 5f + 9f * rng.nextFloat()
        val count = 2 + (rng.nextFloat() * 4f).toInt()
        val crack = rng.nextFloat() < CRACK_PROB
        val basePan = 0.7f * rng.nextSigned()
        val drift = 0.8f * rng.nextSigned() / duration           // pan units per second
        val loud = 0.85f + 0.3f * rng.nextFloat()                  // per-event loudness
        val nyq = sr * 0.5f
        var spawned = 0
        for (k in 0 until count) {
            val b = freeBurst() ?: break
            val first = k == 0
            val startSec = if (first) 0f else rng.nextFloat() * duration * 0.55f
            b.active = true
            b.startIn = (startSec * sr).toInt()
            b.age = 0
            b.env = 0f
            b.lp.reset()
            if (first && crack) {
                // Sharper opening crack: white noise, higher cutoff, fast attack, short decay.
                val fc = 700f + 800f * rng.nextFloat()
                b.useBrown = false
                b.lp.lowpass(fc, 0.7f, sr)
                val attack = 0.04f + 0.11f * rng.nextFloat()
                val decay = 0.35f + 0.6f * rng.nextFloat()
                b.attackSamples = (attack * sr).toInt().coerceAtLeast(1)
                b.attackK = 1f - exp(-2.3f / b.attackSamples)
                b.decayK = exp(-1f / (decay * sr))
                // White noise through a 2nd-order lowpass: noise bandwidth ~1.11*fc.
                val norm = 1f / (WHITE_RMS * sqrt(1.11f * fc / nyq))
                b.amp = (0.03f + 0.03f * rng.nextFloat()) * loud * norm
                b.rollDepth = 0.25f
            } else {
                val fb = 30f + 15f * rng.nextFloat()
                val fc = 60f + 340f * rng.nextFloat() * rng.nextFloat()   // skewed towards low cutoffs
                b.useBrown = true
                b.brown.setCutoff(fb, sr); b.brown.set(0f)
                b.lp.lowpass(fc, 0.75f, sr)
                val attack = 0.3f + 1.7f * rng.nextFloat()
                val decay = (duration - startSec) * (0.22f + 0.15f * rng.nextFloat())
                b.attackSamples = (attack * sr).toInt().coerceAtLeast(1)
                b.attackK = 1f - exp(-2.3f / b.attackSamples)
                b.decayK = exp(-1f / (decay * sr))
                val norm = 1f / (brownRms(b.brown.k) * sqrt(lpFraction(fc, fb)))
                b.amp = (0.03f + 0.035f * rng.nextFloat()) * loud * norm
                b.rollDepth = 0.35f + 0.25f * rng.nextFloat()
            }
            b.pan = (basePan + 0.3f * rng.nextSigned()).coerceIn(-1f, 1f)
            b.panVel = (drift + 0.15f * rng.nextSigned() / duration) / sr
            b.rollTarget = 0f
            b.rollLp.set(0f)
            b.rollIn = 1
            b.gl.set(0f); b.gr.set(0f)
            spawned++
        }
        if (spawned > 0) {
            // Only re-pitch the sub when the previous one has died away (avoids an audible jump).
            if (subEnv.y < 0.05f) {
                subF0 = 30f + 40f * rng.nextFloat()
                subGlide = 0.12f + 0.18f * rng.nextFloat()
                subAge = 0
                subGlideSamples = (duration * sr).toInt().coerceAtLeast(1)
            }
            subAmp = (0.06f + 0.05f * rng.nextFloat()) * loud
        }
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /** RMS of white noise (uniform, RMS [WHITE_RMS]) after a one-pole lowpass with coefficient [k]. */
    private fun brownRms(k: Float): Float = WHITE_RMS * sqrt(k / (2f - k))

    /** Fraction of brown-noise energy (corner [fb]) that survives a lowpass at [fc]. */
    private fun lpFraction(fc: Float, fb: Float): Float = (2f / PI.toFloat()) * atan(fc / fb)

    private companion object {
        const val MAX_BURSTS = 6
        const val WHITE_RMS = 0.5773503f
        const val BED_DB = -45f
        const val BED_BROWN_HZ = 35f
        const val BED_LP_HZ = 55f
        const val ROLL_HZ = 1.2f
        const val SUB_ENV_HZ = 1.5f
        const val END_ENV = 2e-4f
        const val CRACK_PROB = 0.45f
        const val MIN_INTERVAL = 14f
        const val MAX_INTERVAL = 55f
        const val MEAN_INTERVAL = 30f
        const val REENABLE_GAP_NS = 1_500_000_000L
    }
}
