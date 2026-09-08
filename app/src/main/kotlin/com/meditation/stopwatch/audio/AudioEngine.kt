package com.meditation.stopwatch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.meditation.stopwatch.SettingsRepository
import com.meditation.stopwatch.session.Breath
import com.meditation.stopwatch.session.IntensityCurve
import com.meditation.stopwatch.session.SessionClock
import kotlin.math.floor
import kotlin.math.max

/**
 * Real-time synthesis engine: one float-PCM [AudioTrack] fed by a dedicated mixing thread.
 *
 * Lifecycle: [start] from Activity.onStart, [stop] from Activity.onStop.  Both are idempotent,
 * synchronised and safe to call from the main thread.  [stop] joins the mixing thread; that returns
 * within milliseconds because pausing the track from the caller unblocks a WRITE_BLOCKING write and
 * Thread.interrupt() wakes the parked sleep.
 *
 * Every block of [BLOCK_FRAMES] frames the thread samples the session clock, the guided-breath model
 * and the intensity curve into a [RenderContext], reads the user volumes from [SettingsRepository]
 * (a non-suspending StateFlow), and lets [Mixer] render and sum the sounds.  Sounds play only
 * while the stopwatch is running; idle and paused sessions are silent (and the track is parked).
 *
 * Power: once every sound has been silent for more than [PARK_AFTER_SECONDS] the track is paused
 * and the thread polls the settings every [PARK_POLL_MS] instead of streaming zeros, so a muted app
 * costs nothing.  Audio-focus loss ducks to silence through the same gain ramps (nothing is torn
 * down), which then parks the track the same way; regaining focus wakes it.
 */
class AudioEngine(
    context: Context,
    private val clock: SessionClock,
    private val settings: SettingsRepository,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> onFocusChange(change) }

    /**
     * Serialises start()/stop() and guards [track] and [focusRequest].  The audio thread never takes
     * this lock (stop() joins while holding it), it only reads the volatile fields below.
     */
    private val lock = Any()
    @Volatile private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Cleared by stop(); the audio thread exits as soon as it sees false. */
    @Volatile private var running = false

    /** True from a successful start() until stop(), or until the audio thread gives up on its own. */
    @Volatile private var active = false

    /** Multiplier folded into every sound's target gain: 1 with focus, 0 when ducked to silence. */
    @Volatile private var focusGain = 0f

    /** Whether we own audio focus (a transient loss keeps this true: GAIN will follow). */
    @Volatile private var focusHeld = false

    /** True while the mixing thread is alive and an AudioTrack was created successfully. */
    val isActive: Boolean get() = active

    /**
     * Creates the AudioTrack, requests audio focus and launches the "audio-mix" thread.  Calling it
     * while already active is a no-op, except that it re-requests focus if focus was permanently
     * lost (so a second onStart after another app took over brings the sound back).
     */
    fun start() {
        synchronized(lock) {
            val existing = thread
            if (existing != null && existing.isAlive) {
                if (!focusHeld) requestFocusLocked()
                return
            }
            // A previous thread died on its own (e.g. the audio server went away): clean up first.
            if (existing != null) teardownLocked()

            val newTrack = createTrack() ?: return // logged; isActive stays false
            track = newTrack
            running = true
            requestFocusLocked()
            val worker = Thread(Runnable { runLoop(newTrack) }, "audio-mix")
            worker.isDaemon = true
            thread = worker
            active = true
            worker.start()
        }
    }

    /** Stops the thread (joining it), releases the AudioTrack and abandons audio focus. Idempotent. */
    fun stop() {
        synchronized(lock) {
            running = false
            teardownLocked()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle helpers (all called with [lock] held)
    // ---------------------------------------------------------------------------------------------

    private fun teardownLocked() {
        val worker = thread
        val oldTrack = track
        active = false
        if (worker != null) {
            worker.interrupt() // wakes Thread.sleep() in the parked loop
            if (oldTrack != null) {
                // AudioTrack.pause() interrupts a WRITE_BLOCKING write in progress on another thread
                // (it returns 0), which is what lets us join quickly.
                try { oldTrack.pause() } catch (e: IllegalStateException) { /* already released */ }
            }
            try {
                worker.join(JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) Log.w(TAG, "audio-mix did not exit within ${JOIN_TIMEOUT_MS} ms; releasing anyway")
        }
        thread = null
        track = null
        oldTrack?.release()
        abandonFocusLocked()
    }

    private fun requestFocusLocked() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusRequest = it }
        val result = try {
            audioManager.requestAudioFocus(request)
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus threw", e)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusHeld = true
            focusGain = 1f
        } else {
            // Typically a phone call or alarm owns focus.  Stay silent (and parked, hence cheap)
            // until focus is granted on a later start().
            focusHeld = false
            focusGain = 0f
            Log.w(TAG, "Audio focus not granted (result $result); staying silent")
        }
    }

    private fun abandonFocusLocked() {
        focusHeld = false
        focusGain = 0f
        val request = focusRequest ?: return
        try { audioManager.abandonAudioFocusRequest(request) } catch (e: Exception) { Log.w(TAG, "abandonAudioFocusRequest threw", e) }
    }

    /** Runs on the main thread. Only flips volatile gains; the audio thread does the fading. */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusGain = 1f }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusGain = DUCK_GAIN
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusGain = 0f
            AudioManager.AUDIOFOCUS_LOSS -> { focusHeld = false; focusGain = 0f }
            else -> Unit
        }
    }

    private fun createTrack(): AudioTrack? {
        val preferred = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it in 8_000..192_000 }
            ?: DEFAULT_SAMPLE_RATE
        return buildTrack(preferred)
            ?: if (preferred != DEFAULT_SAMPLE_RATE) buildTrack(DEFAULT_SAMPLE_RATE) else null
    }

    private fun buildTrack(sampleRate: Int): AudioTrack? {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        // getMinBufferSize returns a negative error code on failure; fall back to our own minimum.
        val bufferBytes = max(if (minBytes > 0) 4 * minBytes else 0, MIN_BUFFER_FRAMES * BYTES_PER_FRAME)
        val newTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Latency is irrelevant for ambient sound; let the HAL use its deep/low-power path.
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build()
        } catch (e: Exception) { // UnsupportedOperationException / IllegalArgumentException
            Log.e(TAG, "AudioTrack creation failed at $sampleRate Hz", e)
            return null
        }
        if (newTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack did not initialise at $sampleRate Hz (state ${newTrack.state})")
            newTrack.release()
            return null
        }
        Log.i(TAG, "AudioTrack ready: $sampleRate Hz, ${bufferBytes / BYTES_PER_FRAME}-frame buffer")
        return newTrack
    }

    // ---------------------------------------------------------------------------------------------
    // Audio thread
    // ---------------------------------------------------------------------------------------------

    private fun runLoop(out: AudioTrack) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.w(TAG, "Could not raise audio thread priority", e)
        }

        val sampleRate = out.sampleRate
        val mixer = Mixer(sampleRate, BLOCK_FRAMES)
        val ctx = mixer.context
        val block = FloatArray(BLOCK_FRAMES * 2)
        val targets = FloatArray(SoundId.entries.size)
        val parkAfterFrames = sampleRate.toLong() * PARK_AFTER_SECONDS
        var silentFrames = 0L
        // A fresh track is stopped with an empty buffer – exactly the parked state – so the first
        // audible demand goes through the same prime-then-play path as a wake-up.
        var parked = true

        try {
            while (running) {
                computeTargets(targets)

                if (parked) {
                    if (!mixer.anyAudible(targets)) {
                        sleepQuietly(PARK_POLL_MS)
                        continue
                    }
                    // Wake up: the buffer is empty (fresh or flushed), so prime it with one silent
                    // block – the first thing the sink plays is guaranteed silence – then start.
                    // The following blocks ramp every gain up from zero, so there is no click.
                    block.fill(0f)
                    if (!writeFully(out, block, block.size)) break
                    if (!running) break
                    out.play()
                    parked = false
                    silentFrames = 0L
                }

                updateContext(ctx)
                val audible = mixer.mix(block, BLOCK_FRAMES, targets)
                silentFrames = if (audible) 0L else silentFrames + BLOCK_FRAMES
                if (!writeFully(out, block, block.size)) break

                if (silentFrames > parkAfterFrames) {
                    // Only zeros for a while: stop the output stream so the DSP/DAC can power down.
                    // Everything still queued is silence, so discarding it is inaudible.
                    out.pause()
                    out.flush()
                    parked = true
                }
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "audio-mix thread died", t)
        } finally {
            // Guard against a timed-out join followed by a fresh start(): never clobber the new run.
            if (thread === Thread.currentThread()) active = false
        }
    }

    /**
     * Target linear gain per sound: `volume² × master × focus × dynamics`.  Squaring the slider
     * value gives a roughly perceptual (loudness-linear) response so the lower half of the slider
     * is useful.  [SoundDynamics] then lifts the whole mix as the session intensifies and drifts
     * every sound on its own slow tide, so the balance keeps evolving without any slider moving.
     */
    private fun computeTargets(out: FloatArray) {
        val s = settings.settings.value
        // Sounds play only while the stopwatch is running: idle and paused fade to silence through
        // the mixer's gain ramps, after which the engine parks the track.
        val gate = if (clock.isRunning) 1f else 0f
        val master = s.masterVolume.coerceIn(0f, 1f) * focusGain * gate
        val sec = clock.elapsedSec()
        val intensity = IntensityCurve.at(sec)
        val lift = SoundDynamics.lift(intensity)
        val seed = clock.seed
        val ids = SoundId.entries
        for (i in 0 until ids.size) {
            val v = s.volume(ids[i]).coerceIn(0f, 1f)
            out[i] = v * v * master * lift * SoundDynamics.drift(i, sec, intensity, seed)
        }
    }

    /** Samples the session models once per block. Identical maths to Breath.stateAt, minus the allocation. */
    private fun updateContext(ctx: RenderContext) {
        val sec = clock.elapsedSec()
        ctx.elapsedSec = sec
        ctx.running = clock.isRunning
        val cycles = Breath.cyclesAt(sec)
        val phase = cycles - floor(cycles)
        ctx.breathPhase = phase.toFloat()
        ctx.breathFullness = Breath.fullness(phase).toFloat()
        ctx.intensity = IntensityCurve.at(sec)
    }

    /**
     * Writes [count] floats, looping over partial writes.  A return of 0 means the write was
     * interrupted by a pause() from another thread (stop(), or our own park path – AudioTrack's
     * interrupt flag is one-shot and the retry succeeds).
     *
     * @return false if the engine is stopping or the track reported an error.
     */
    private fun writeFully(out: AudioTrack, data: FloatArray, count: Int): Boolean {
        var offset = 0
        while (offset < count) {
            if (!running) return false
            val written = out.write(data, offset, count - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed ($written); giving up until the next start()")
                return false
            }
            if (written == 0) sleepQuietly(2L)
            offset += written
        }
        return true
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // stop() interrupted us; the loop re-checks [running] and exits.
        }
    }

    private companion object {
        const val TAG = "AudioEngine"
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val BLOCK_FRAMES = 1024
        const val MIN_BUFFER_FRAMES = 8192
        const val BYTES_PER_FRAME = 2 * 4 // stereo float
        const val PARK_AFTER_SECONDS = 2L
        const val PARK_POLL_MS = 100L
        const val JOIN_TIMEOUT_MS = 2_000L
        /** Level while another app plays a short sound over us (LOSS_TRANSIENT_CAN_DUCK). */
        const val DUCK_GAIN = 0.25f
    }
}
