package com.meditation.stopwatch.audio

import android.util.Log

/**
 * The pure mixing core behind [AudioEngine]: owns one lazily created [SoundGenerator] per [SoundId],
 * smooths every sound's gain, sums the generators into one interleaved stereo block and soft-clips
 * the result.  It knows nothing about AudioTrack, threads or settings, so it can be unit-tested on
 * the JVM with a fake generator.
 *
 * Single-threaded by design: every method is called from the audio thread only.  Allocation-free
 * after construction, except for the one-off creation of a generator the first time its sound is
 * turned up (generators are cheap; the engine discards the whole mixer on stop()).
 *
 * @param sampleRate output rate; handed to every generator's [SoundGenerator.reset] exactly once.
 * @param maxFrames  the largest block [mix] will ever be asked for (sizes the scratch buffer).
 */
internal class Mixer(private val sampleRate: Int, maxFrames: Int) {

    /** Per-block modulation context; the owner fills it in before each [mix] call. */
    val context: RenderContext = RenderContext().also { it.sampleRate = sampleRate }

    private val ids = SoundId.entries
    private val generators = arrayOfNulls<SoundGenerator>(ids.size)

    /** A generator that threw, or produced non-finite output, is muted for the rest of this run. */
    private val broken = BooleanArray(ids.size)

    /** Smoothed linear gain of each sound as of the end of the previous block. */
    private val gains = FloatArray(ids.size)

    /** One generator's output before it is scaled and summed into the caller's block. */
    private val scratch = FloatArray(maxFrames * 2)

    /**
     * True when at least one sound would be heard: its target gain is non-negligible, or it is
     * still fading out.  Used by the engine to decide whether to wake a parked AudioTrack.
     */
    fun anyAudible(targets: FloatArray): Boolean {
        for (i in 0 until ids.size) {
            if (!broken[i] && (targets[i] >= SILENCE || gains[i] >= SILENCE)) return true
        }
        return false
    }

    /**
     * Renders one block.  [out] receives `frames` interleaved stereo samples (written, not added).
     * [targets] holds the desired linear gain per sound (indexed by [SoundId.ordinal]).
     *
     * Gains move towards their targets linearly across the block, with the per-block change capped
     * so that a full 0..1 swing takes [RAMP_SECONDS]: click-free and slow enough that slider drags
     * and audio-focus ducking sound like fades, not steps.  A sound whose gain and target are both
     * below [SILENCE] is skipped entirely, and its generator is not even created.
     *
     * @return true if anything was rendered; false means [out] is all zeros.
     */
    fun mix(out: FloatArray, frames: Int, targets: FloatArray): Boolean {
        val n = frames * 2
        out.fill(0f, 0, n)
        val maxStep = (frames / (RAMP_SECONDS * sampleRate)).toFloat()
        var audible = false

        for (i in 0 until ids.size) {
            val g0 = gains[i]
            val target = if (broken[i]) 0f else targets[i]
            if (g0 < SILENCE && target < SILENCE) {
                gains[i] = 0f
                continue
            }
            val gen = generators[i] ?: create(i) ?: continue
            try {
                gen.render(scratch, frames, context)
            } catch (t: Throwable) {
                disable(i, "threw in render()", t)
                continue
            }
            // A blown-up IIR filter yields NaN/inf persistently, so probing the last frame is enough
            // to catch it within one block; a generator is nominally within -1..1, so anything beyond
            // +-1e4 is a bug rather than loud content.
            val probe = scratch[n - 2] + scratch[n - 1]
            if (probe.isNaN() || probe > 1e4f || probe < -1e4f) {
                disable(i, "produced non-finite output", null)
                continue
            }

            val g1 = g0 + (target - g0).coerceIn(-maxStep, maxStep)
            val step = (g1 - g0) / frames
            var g = g0
            var j = 0
            while (j < n) {
                out[j] += scratch[j] * g
                out[j + 1] += scratch[j + 1] * g
                g += step
                j += 2
            }
            gains[i] = g1 // store the exact end value so float drift never accumulates across blocks
            audible = true
        }

        if (audible) {
            for (j in 0 until n) {
                val y = softClip(out[j])
                out[j] = if (y.isNaN()) 0f else y // belt and braces: never hand NaN to the DAC
            }
        }
        return audible
    }

    private fun create(i: Int): SoundGenerator? {
        return try {
            SoundRegistry.create(ids[i]).also {
                it.reset(sampleRate)
                generators[i] = it
            }
        } catch (t: Throwable) {
            disable(i, "failed to initialise", t)
            null
        }
    }

    private fun disable(i: Int, why: String, cause: Throwable?) {
        broken[i] = true
        gains[i] = 0f
        generators[i] = null
        val msg = "Sound ${ids[i]} $why; muting it until the engine restarts"
        if (cause != null) Log.e(TAG, msg, cause) else Log.e(TAG, msg)
    }

    companion object {
        private const val TAG = "Mixer"

        /** Gains below this are treated as silence: the generator is skipped, not rendered. */
        const val SILENCE = 1e-4f

        /** Time for a gain to travel the full 0..1 range. */
        const val RAMP_SECONDS = 0.2
    }
}
