package com.meditation.stopwatch.audio

/**
 * Per-buffer context handed to every generator.  Values are sampled once per render call; generators
 * should interpolate internally if they need smoother modulation.
 */
class RenderContext {
    var sampleRate: Int = 48_000
    /** Seconds since the session started (0 while idle). */
    var elapsedSec: Double = 0.0
    /** Guided breath: lung fullness 0..1 – use it to make sounds swell with the inhale. */
    var breathFullness: Float = 0f
    /** 0..1 position inside the breath cycle. */
    var breathPhase: Float = 0f
    /** Session intensity 0..1 (see IntensityCurve). */
    var intensity: Float = 0f
    /** True while the stopwatch is running. */
    var running: Boolean = false
}

/**
 * A procedural stereo sound source.
 *
 * Contract:
 *  - [render] must WRITE (not add) `frames` interleaved stereo float samples (L,R,L,R,...) into
 *    [out], starting at index 0, nominally within -1..1.  The mixer applies the user volume.
 *  - It must be allocation-free and cheap: budget is well under 1% of one CPU core per generator.
 *  - It must never click: any parameter change should be smoothed internally.
 *  - It must be deterministic given its own RNG state (no java.util.Random shared across threads).
 *  - [reset] is called once from the audio thread before the first render and whenever the sample
 *    rate changes; allocate scratch buffers there.
 */
interface SoundGenerator {
    val id: SoundId
    fun reset(sampleRate: Int)
    fun render(out: FloatArray, frames: Int, ctx: RenderContext)
}

/** Small, fast xorshift RNG so generators never share java.util.Random across threads. */
class FastRandom(seed: Long = 0x9E3779B97F4A7C15uL.toLong()) {
    private var s: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed
    fun nextLong(): Long { var x = s; x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); s = x; return x }
    /** Uniform in [0,1). */
    fun nextFloat(): Float = ((nextLong() ushr 40).toInt() and 0xFFFFFF) / 16777216f
    /** Uniform in [-1,1). */
    fun nextSigned(): Float = nextFloat() * 2f - 1f
    /** Approximately Gaussian (sum of 4 uniforms), unit variance-ish. */
    fun nextGaussian(): Float = (nextFloat() + nextFloat() + nextFloat() + nextFloat() - 2f) * 1.7320508f
}
