package com.meditation.stopwatch.session

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI observes. Everything else is derived from [elapsedMs] via [Breath] and [IntensityCurve]. */
data class SessionState(
    val running: Boolean = false,
    val everStarted: Boolean = false,
    val elapsedMs: Long = 0L,
    val breath: BreathState = BreathState.IDLE,
    val intensity: Float = 0f,
)

/**
 * The single source of truth for session time.  Thread-safe and allocation-free on the hot path so
 * the GL thread and the audio thread can call [elapsedSec] every frame / buffer.
 *
 * The Compose UI collects [state], which is refreshed by [tick] from a coroutine at a modest rate.
 */
class SessionClock {
    @Volatile private var running = false
    @Volatile private var everStarted = false
    @Volatile private var accumulatedNanos = 0L
    @Volatile private var startedAtNanos = 0L
    /** Randomness for this session; redrawn whenever a session starts from zero. See [SessionSeed]. */
    @Volatile var seed: Long = SessionSeed.fresh()
        private set

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isRunning: Boolean get() = running
    val hasStarted: Boolean get() = everStarted

    fun elapsedNanos(): Long {
        val base = accumulatedNanos
        return if (running) base + (SystemClock.elapsedRealtimeNanos() - startedAtNanos) else base
    }

    fun elapsedSec(): Double = elapsedNanos() / 1_000_000_000.0

    fun start() {
        if (running) return
        if (!everStarted) seed = SessionSeed.fresh()   // a new sit, a new piece
        startedAtNanos = SystemClock.elapsedRealtimeNanos()
        running = true
        everStarted = true
        tick()
    }

    fun pause() {
        if (!running) return
        accumulatedNanos += SystemClock.elapsedRealtimeNanos() - startedAtNanos
        running = false
        tick()
    }

    fun toggle() = if (running) pause() else start()

    fun reset() {
        running = false
        everStarted = false
        accumulatedNanos = 0L
        tick()
    }

    /** Publish a fresh [SessionState]. Call from a UI-side coroutine loop (e.g. every 33 ms). */
    fun tick() {
        val ms = elapsedNanos() / 1_000_000L
        val sec = ms / 1000.0
        _state.value = SessionState(
            running = running,
            everStarted = everStarted,
            elapsedMs = ms,
            breath = Breath.stateAt(sec),
            intensity = IntensityCurve.at(sec),
        )
    }
}
