package com.meditation.stopwatch.audio.generators

import com.meditation.stopwatch.audio.FastRandom
import com.meditation.stopwatch.audio.Lfo
import com.meditation.stopwatch.audio.OnePole
import com.meditation.stopwatch.audio.Ramp
import com.meditation.stopwatch.audio.RenderContext
import com.meditation.stopwatch.audio.SoundGenerator
import com.meditation.stopwatch.audio.SoundId
import com.meditation.stopwatch.audio.panGains
import kotlin.math.exp

/**
 * A Tibetan singing bowl drone, built additively.
 *
 * Model: a bowl rings at inharmonic modal frequencies (ratios ~1 : 2.71 : 5.15 : 8.2 : 11.9) and,
 * because a real bowl is never perfectly round, every mode is split into two very close frequencies.
 * Their interference is the characteristic slow wobble, so each partial here is a PAIR of slightly
 * detuned sines (beating 0.3–1.4 Hz, different per partial, with unequal amplitudes so the wobble
 * never fully nulls).  The fundamental is chosen near G3 (190–204 Hz) once per [reset].
 *
 * The whole drone swells with the breath: gain = 0.3 + 0.7 * fullness, smoothed by a block-rate
 * one-pole (120 ms) and then linearly interpolated per sample, so neither a breath step nor the
 * running/idle switch can click.  Idle, a 12 s sine LFO takes the place of the breath.
 *
 * Every 45–110 s a gentle "strike" brightens the sound: upper partials (plus two strike-only modes at
 * ratios 15.4 and 19.7) get an extra amplitude that rises over 30 ms and decays with a 1.9 s time
 * constant (inaudible after ~8 s).  The strike weights are multiplied by an envelope that starts and
 * ends at zero, so no discontinuity can occur.
 *
 * Stereo: each partial has its own pan position that rotates slowly (41 s) around a small spread
 * (wider for upper partials); the pan gains are ramped per sample.
 *
 * Cost: 14 phase accumulators and 14 polynomial sines per frame, ~150 flops – well under 1 % of a
 * core.
 */
class SingingBowlGenerator : SoundGenerator {
    override val id: SoundId = SoundId.SINGING_BOWL

    private companion object {
        const val SEED = 0x626F776C5F5F3031L
        const val NP = 7
        /** Modal frequency ratios; the last two only ring during a strike. */
        val RATIO = floatArrayOf(1f, 2.71f, 5.15f, 8.2f, 11.9f, 15.4f, 19.7f)
        /** Steady-state amplitude of each partial relative to the fundamental. */
        val DRONE_AMP = floatArrayOf(1f, 0.5f, 0.25f, 0.12f, 0.06f, 0f, 0f)
        /** Extra amplitude (relative to the fundamental) each partial gets at the peak of a strike. */
        val STRIKE_AMP = floatArrayOf(0.12f, 0.30f, 0.28f, 0.20f, 0.12f, 0.07f, 0.04f)
        /** Master level: ~-17 dBFS RMS at full lungs, ~-21 dBFS averaged over a breath. */
        const val LEVEL = 0.2f
        const val SWELL_FLOOR = 0.3f
        const val SWELL_TC_SEC = 0.12f
        const val IDLE_LFO_HZ = 1f / 12f
        const val ROT_HZ = 1f / 41f
        const val STRIKE_ATTACK_SEC = 0.03f
        const val STRIKE_TAU_SEC = 1.9f
        const val PI_F = 3.14159265f
        const val HALF_PI = 1.57079633f
        const val TWO_PI = 6.28318531f
    }

    private var sr = 48_000
    private var rng = FastRandom(SEED)

    // Oscillator state, one pair (A/B) per partial.  Phases are radians in [-PI, PI).
    private val phA = FloatArray(NP)
    private val phB = FloatArray(NP)
    private val incA = FloatArray(NP)
    private val incB = FloatArray(NP)
    /** Amplitude of the B sine relative to the A sine of the same pair. */
    private val ampB = FloatArray(NP)

    // Per-partial stereo gains, linearly ramped across each block.
    private val gL = FloatArray(NP)
    private val gR = FloatArray(NP)
    private val dL = FloatArray(NP)
    private val dR = FloatArray(NP)
    private val spread = FloatArray(NP)
    private val panLfo = Array(NP) { Lfo(ROT_HZ, it.toFloat() / NP) }
    private val pan2 = FloatArray(2)

    // Breath swell.
    private val swell = OnePole()
    private val gain = Ramp(0f)
    private val idle = Lfo(IDLE_LFO_HZ)

    // Strike.
    private var strikeCountdown = 0
    private var strikeEnv = 0f
    private var strikeAttackLeft = 0
    private var strikeAttackStep = 0f
    private var strikeDecay = 1f
    private val strikeBoost = FloatArray(NP)

    override fun reset(sampleRate: Int) {
        sr = sampleRate
        rng = FastRandom(SEED xor System.nanoTime())
        val f0 = 190f + 14f * rng.nextFloat()
        val radPerHz = TWO_PI / sr
        for (k in 0 until NP) {
            val f = f0 * RATIO[k]
            val beat = 0.3f + 1.1f * rng.nextFloat()
            incA[k] = (f - beat * 0.5f) * radPerHz
            incB[k] = (f + beat * 0.5f) * radPerHz
            phA[k] = rng.nextFloat() * TWO_PI - PI_F
            phB[k] = rng.nextFloat() * TWO_PI - PI_F
            ampB[k] = 0.55f + 0.35f * rng.nextFloat()
            spread[k] = 0.12f + 0.09f * k
            panGains(0f, pan2)
            gL[k] = pan2[0]; gR[k] = pan2[1]
            dL[k] = 0f; dR[k] = 0f
            strikeBoost[k] = 0f
        }
        swell.set(0f)          // rises smoothly on the first blocks: a built-in fade-in
        gain.set(0f)
        strikeEnv = 0f
        strikeAttackLeft = 0
        strikeAttackStep = 0f
        strikeDecay = exp(-1f / (STRIKE_TAU_SEC * sr))
        strikeCountdown = ((15f + 30f * rng.nextFloat()) * sr).toInt()
    }

    override fun render(out: FloatArray, frames: Int, ctx: RenderContext) {
        // ---- block-rate control ----
        val fullness = if (ctx.running) ctx.breathFullness else 0.5f + 0.5f * idle.nextBlock(frames, sr)
        swell.k = 1f - exp(-frames / (SWELL_TC_SEC * sr))
        gain.target(swell.process(SWELL_FLOOR + (1f - SWELL_FLOOR) * fullness) * LEVEL, frames)

        val spreadScale = 1f + 0.15f * ctx.intensity   // intensity: slightly wider image
        val invFrames = 1f / frames
        for (k in 0 until NP) {
            panGains(spread[k] * spreadScale * panLfo[k].nextBlock(frames, sr), pan2)
            dL[k] = (pan2[0] - gL[k]) * invFrames
            dR[k] = (pan2[1] - gR[k]) * invFrames
        }

        strikeCountdown -= frames
        if (strikeCountdown <= 0) triggerStrike(ctx.intensity)

        // ---- per sample ----
        val phA = phA; val phB = phB; val incA = incA; val incB = incB; val ampB = ampB
        val gL = gL; val gR = gR; val dL = dL; val dR = dR
        val droneAmp = DRONE_AMP; val boost = strikeBoost
        var env = strikeEnv
        var i = 0
        for (n in 0 until frames) {
            if (strikeAttackLeft > 0) {
                env += strikeAttackStep
                strikeAttackLeft--
            } else {
                env *= strikeDecay
                if (env < 1e-6f) env = 0f   // keep the tail out of denormal territory
            }
            var l = 0f
            var r = 0f
            for (k in 0 until NP) {
                var a = phA[k] + incA[k]; if (a >= PI_F) a -= TWO_PI; phA[k] = a
                var b = phB[k] + incB[k]; if (b >= PI_F) b -= TWO_PI; phB[k] = b
                val s = (fastSin(a) + ampB[k] * fastSin(b)) * (droneAmp[k] + boost[k] * env)
                val gl = gL[k] + dL[k]; gL[k] = gl
                val gr = gR[k] + dR[k]; gR[k] = gr
                l += s * gl
                r += s * gr
            }
            val g = gain.next()
            out[i] = l * g
            out[i + 1] = r * g
            i += 2
        }
        strikeEnv = env
    }

    private fun triggerStrike(intensity: Float) {
        val strength = (0.6f + 0.4f * rng.nextFloat()) * (1f + 0.15f * intensity)
        for (k in 0 until NP) strikeBoost[k] = STRIKE_AMP[k] * strength * (0.8f + 0.4f * rng.nextFloat())
        val attack = (STRIKE_ATTACK_SEC * sr).toInt().coerceAtLeast(1)
        strikeAttackLeft = attack
        strikeAttackStep = (1f - strikeEnv) / attack
        strikeCountdown = ((45f + 65f * rng.nextFloat()) * sr).toInt()
    }

    /** Odd 9th-order polynomial sine for x in [-PI, PI); |error| < 4e-6 (about -108 dB). */
    private fun fastSin(x: Float): Float {
        var t = x
        if (t > HALF_PI) t = PI_F - t else if (t < -HALF_PI) t = -PI_F - t
        val t2 = t * t
        return t * (1f + t2 * (-0.16666667f + t2 * (0.008333333f + t2 * (-1.9841270e-4f + t2 * 2.7557319e-6f))))
    }
}
