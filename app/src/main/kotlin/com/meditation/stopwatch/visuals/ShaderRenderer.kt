package com.meditation.stopwatch.visuals

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.meditation.stopwatch.session.Breath
import com.meditation.stopwatch.session.IntensityCurve
import com.meditation.stopwatch.session.SessionSeed
import com.meditation.stopwatch.session.SessionClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer that draws [Movements.all] full screen, one program per movement.
 *
 * Programme: movement k is shown for [SEGMENT_SEC] of session time; the last [CROSSFADE_SEC] of
 * every segment blend into the next movement.  After the last movement the programme loops back to
 * index 1 (the opening, index 0, is only shown while idle and during the first segment).
 *
 * Time model:
 *   - uElapsed / uBreath / uIntensity are pure functions of [SessionClock.elapsedSec] (frozen while
 *     paused, 0 while idle).  While idle a 10 s sine drives the breath so the opening glow still
 *     breathes, and a short blend hides the discontinuity when the session starts or is reset.
 *   - uTime is integrated here from frame deltas, so it always flows – even on a paused screen –
 *     at 0.5 + 0.9 * intensity of real time.
 *
 * GL objects (VAO, programs, uniform locations) are (re)built in [onSurfaceCreated] and never
 * cached across contexts.  Compiling every movement up front would delay the first frame by a
 * second or more on mid-range GPUs, so only the opening movement is compiled synchronously; the
 * rest are compiled one per frame in the background (or on demand if the programme needs one
 * earlier).  Any movement whose shader fails to compile or link is replaced by a built-in glow so
 * the screen is never black.
 *
 * All methods run on the GL thread; [renderScale] is the only field touched by the UI thread.
 */
class ShaderRenderer(private val clock: SessionClock) : GLSurfaceView.Renderer {

    /**
     * Fraction of the view size the surface is rendered at (0.25..1).  The renderer itself only
     * draws whatever size it is given; the owning view reads this to pick its fixed buffer size.
     */
    @Volatile var renderScale: Float = 0.6f

    private class Program(val id: Int, val loc: IntArray)

    private val movements: List<Movement> = Movements.all
    /** At least one slot so the fallback glow is drawn even if the movement list is empty. */
    private val movementCount: Int = maxOf(1, movements.size)
    /** uSeed (0..1000) and the movement order, both derived from [SessionClock.seed]. */
    private var seed: Float = 0f
    private var order: IntArray = IntArray(movementCount) { it }
    private var seedSource: Long = 0L

    // ---- GL names – valid only for the current context, rebuilt in onSurfaceCreated ----
    private var programs: Array<Program?> = arrayOfNulls<Program>(movementCount)
    private var vertexShader = 0
    private var fallbackId = 0
    private var fallbackTried = false
    /** Movements [0, compiledUpTo) have been built (or were built on demand); the rest are pending. */
    private var compiledUpTo = 0
    private val vao = IntArray(1)
    private val status = IntArray(1)

    // ---- viewport ----
    private var width = 1f
    private var height = 1f
    private var aspect = 1f

    // ---- clocks (seconds) ----
    private var lastFrameNanos = 0L
    /** Real time the surface has spent drawing – drives the idle breath and the start/reset blend. */
    private var wallTime = 0.0
    /** uTime: intensity-scaled animation clock. */
    private var animTime = 0.0

    // ---- per-frame uniform values ----
    private var elapsed = 0f
    private var breath = 0f
    private var breathPhase = 0f
    private var breathVel = 0f
    private var intensity = 0f
    private var running = 0f

    // ---- idle <-> session breath blend ----
    private var wasStarted = clock.hasStarted
    private var blendFrom = 0f
    private var blendStartWall = -BLEND_SEC

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "GL context created: ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")

        // Every name from a previous context is dead: drop them, never delete or reuse them.
        programs = arrayOfNulls<Program>(movementCount)
        vertexShader = 0
        fallbackId = 0
        fallbackTried = false
        compiledUpTo = 0
        lastFrameNanos = 0L

        // ES 3 core requires a VAO to be bound for glDrawArrays even with no vertex attributes.
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Crossfade: colour = in * fade + out * (1 - fade).  Alpha is forced to stay 1 so the
        // window surface never carries a partial alpha into the compositor.
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE, GLES30.GL_ONE,
        )
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, Glsl.VERTEX, "vertex")
        if (vertexShader == 0) {
            vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, FALLBACK_VERTEX, "fallback vertex")
        }

        // The opening movement is needed for the very first frame; the others follow one per frame.
        program(0)
        compiledUpTo = 1

        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(err)} during setup")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = maxOf(1, w).toFloat()
        height = maxOf(1, h).toFloat()
        aspect = width / height
    }

    override fun onDrawFrame(gl: GL10?) {
        // ---- frame delta; a long gap (surface paused) is treated as a single short step ----
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else ((now - lastFrameNanos) * 1e-9).coerceIn(0.0, MAX_DT)
        lastFrameNanos = now
        wallTime += dt

        // ---- session sample ----
        val started = clock.hasStarted
        if (clock.seed != seedSource) {
            seedSource = clock.seed
            seed = SessionSeed.unit(seedSource, 0) * 1000f
            order = programmeOrder(seedSource, movementCount)
            Log.d(TAG, "session seed ${seedSource.toString(16)}: uSeed=%.1f order=${order.joinToString(",")}".format(seed))
        }
        val sec = if (started) clock.elapsedSec() else 0.0
        val i = if (started) IntensityCurve.at(sec) else 0f
        animTime += dt * (0.5 + 0.9 * i)

        // ---- breath ----
        if (started != wasStarted) {
            // Idle sine <-> session model switch: remember where we were and blend over BLEND_SEC.
            wasStarted = started
            blendFrom = breath
            blendStartWall = wallTime
        }
        var f: Float
        var v: Float
        val ph: Float
        if (started) {
            // Same maths as Breath.stateAt(sec), evaluated piecewise so no BreathState is allocated.
            val cycles = Breath.cyclesAt(sec)
            val phase = cycles - floor(cycles)
            ph = phase.toFloat()
            f = Breath.fullness(phase).toFloat()
            v = (Breath.dFullness(phase) / Breath.periodAt(sec)).toFloat()
        } else {
            val cyc = wallTime / IDLE_BREATH_PERIOD
            val a = cyc * TWO_PI
            ph = (cyc - floor(cyc)).toFloat()
            f = (0.5 - 0.5 * cos(a)).toFloat()
            v = (0.5 * TWO_PI / IDLE_BREATH_PERIOD * sin(a)).toFloat()
        }
        val u = ((wallTime - blendStartWall) / BLEND_SEC).coerceIn(0.0, 1.0)
        if (u < 1.0) {
            val s = (u * u * (3.0 - 2.0 * u)).toFloat()
            val ds = (6.0 * u * (1.0 - u) / BLEND_SEC).toFloat()
            val target = f
            f = blendFrom + (target - blendFrom) * s
            v = v * s + (target - blendFrom) * ds   // derivative of the blend, keeps uBreathVel honest
        }
        elapsed = sec.toFloat()
        breath = f
        breathPhase = ph
        breathVel = v
        intensity = i
        running = if (clock.isRunning) 1f else 0f

        // ---- programme ----
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!started || movementCount < 2) {
            drawPass(program(0), 1f)
        } else {
            val seg = (sec / SEGMENT_SEC).toInt()
            val cur = order[programmeIndex(seg, movementCount)]
            val into = sec - seg * SEGMENT_SEC - (SEGMENT_SEC - CROSSFADE_SEC)   // >= 0 inside the crossfade
            val next = if (into >= 0.0) order[programmeIndex(seg + 1, movementCount)] else cur
            drawPass(program(cur), 1f)
            if (next != cur) {
                GLES30.glEnable(GLES30.GL_BLEND)
                drawPass(program(next), smoothstep(into / CROSSFADE_SEC).toFloat())
                GLES30.glDisable(GLES30.GL_BLEND)
            }
        }

        // ---- background compilation: one movement per frame until all are ready ----
        if (compiledUpTo < movementCount) {
            program(compiledUpTo)
            compiledUpTo++
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Uploads the frame's uniforms to [p] and draws the full-screen triangle.  Allocation-free. */
    private fun drawPass(p: Program, fade: Float) {
        if (p.id == 0) return
        GLES30.glUseProgram(p.id)
        val l = p.loc   // a location of -1 (uniform optimised out) is silently ignored by glUniform*
        GLES30.glUniform2f(l[U_RESOLUTION], width, height)
        GLES30.glUniform1f(l[U_TIME], animTime.toFloat())
        GLES30.glUniform1f(l[U_ELAPSED], elapsed)
        GLES30.glUniform1f(l[U_BREATH], breath)
        GLES30.glUniform1f(l[U_BREATH_PHASE], breathPhase)
        GLES30.glUniform1f(l[U_BREATH_VEL], breathVel)
        GLES30.glUniform1f(l[U_INTENSITY], intensity)
        GLES30.glUniform1f(l[U_RUNNING], running)
        GLES30.glUniform1f(l[U_SEED], seed)
        GLES30.glUniform1f(l[U_FADE], fade)
        GLES30.glUniform1f(l[U_ASPECT], aspect)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    // ------------------------------------------------------------------ program construction

    /** The program for movement [k], building it on first use. */
    private fun program(k: Int): Program {
        val existing = programs[k]
        if (existing != null) return existing
        val built = build(k)
        programs[k] = built
        return built
    }

    private fun build(k: Int): Program {
        val m = movements.getOrNull(k)
        var id = 0
        if (m != null && vertexShader != 0) {
            val t0 = System.nanoTime()
            id = linkProgram(vertexShader, Glsl.fragment(m), "movement $k '${m.name}'")
            if (id != 0) Log.d(TAG, "compiled movement $k '${m.name}' in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        if (id == 0) {
            Log.w(TAG, "movement $k ('${m?.name}') unavailable - using the built-in glow")
            id = fallbackProgram()
        }
        return Program(id, uniformLocations(id))
    }

    private fun fallbackProgram(): Int {
        if (!fallbackTried) {
            fallbackTried = true
            if (vertexShader != 0) fallbackId = linkProgram(vertexShader, FALLBACK_FRAGMENT, "fallback glow")
            if (fallbackId == 0) Log.e(TAG, "fallback shader failed as well - nothing can be drawn on this device")
        }
        return fallbackId
    }

    private fun uniformLocations(programId: Int): IntArray =
        if (programId == 0) IntArray(U_COUNT) { -1 }
        else IntArray(U_COUNT) { GLES30.glGetUniformLocation(programId, UNIFORM_NAMES[it]) }

    private fun linkProgram(vs: Int, fragmentSource: String, label: String): Int {
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, label)
        if (fs == 0) return 0
        val p = GLES30.glCreateProgram()
        if (p == 0) {
            Log.e(TAG, "glCreateProgram failed ($label)")
            GLES30.glDeleteShader(fs)
            return 0
        }
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDetachShader(p, vs)
        GLES30.glDetachShader(p, fs)
        GLES30.glDeleteShader(fs)   // the vertex shader is shared for the life of the context
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed ($label):")
            logLines(GLES30.glGetProgramInfoLog(p) ?: "")
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val s = GLES30.glCreateShader(type)
        if (s == 0) {
            Log.e(TAG, "glCreateShader failed ($label)")
            return 0
        }
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(s) ?: ""
            Log.e(TAG, "Shader compile failed ($label):")
            logLines(info)
            logFailingLines(source, info)
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    /** Logcat truncates long messages, so emit an info log line by line. */
    private fun logLines(info: String) {
        for (line in info.lineSequence()) if (line.isNotBlank()) Log.e(TAG, "  $line")
    }

    /**
     * Drivers report positions as "ERROR: 0:123: ..." (Adreno, Mali, PowerVR), "0(123) : error"
     * (Nvidia) or "line 123".  Pull those numbers out and echo the offending source with context.
     */
    private fun logFailingLines(source: String, info: String) {
        val lines = source.split('\n')
        val seen = HashSet<Int>()
        var reported = 0
        for (match in LINE_NUMBER.findAll(info)) {
            var n = -1
            for (g in 1 until match.groupValues.size) {
                val value = match.groupValues[g]
                if (value.isNotEmpty()) { n = value.toIntOrNull() ?: -1; break }
            }
            if (n < 1 || n > lines.size || !seen.add(n)) continue
            Log.e(TAG, "  --- source around line $n ---")
            for (i in maxOf(1, n - 2)..minOf(lines.size, n + 2)) {
                Log.e(TAG, String.format("  %s%5d | %s", if (i == n) ">" else " ", i, lines[i - 1]))
            }
            if (++reported >= MAX_REPORTED_LINES) break
        }
    }

    companion object {
        private const val TAG = "ShaderRenderer"

        /** Session seconds each movement is shown for. */
        const val SEGMENT_SEC = 150.0
        /** Length of the blend into the next movement at the end of each segment. */
        const val CROSSFADE_SEC = 12.0

        private const val IDLE_BREATH_PERIOD = 10.0
        private const val BLEND_SEC = 2.0
        private const val MAX_DT = 0.1
        private const val TWO_PI = 2.0 * PI
        private const val MAX_REPORTED_LINES = 6

        // Uniform slots – the order of UNIFORM_NAMES.
        private const val U_RESOLUTION = 0
        private const val U_TIME = 1
        private const val U_ELAPSED = 2
        private const val U_BREATH = 3
        private const val U_BREATH_PHASE = 4
        private const val U_BREATH_VEL = 5
        private const val U_INTENSITY = 6
        private const val U_RUNNING = 7
        private const val U_SEED = 8
        private const val U_FADE = 9
        private const val U_ASPECT = 10
        private const val U_COUNT = 11
        private val UNIFORM_NAMES = arrayOf(
            "uResolution", "uTime", "uElapsed", "uBreath", "uBreathPhase", "uBreathVel",
            "uIntensity", "uRunning", "uSeed", "uFade", "uAspect",
        )

        private val LINE_NUMBER = Regex("""\b\d+:(\d+)\b|\((\d+)\)\s*:|\bline\s+(\d+)""")

        /**
         * Movement shown during 0-based session [segment] of a programme with [count] movements:
         * 0, 1, .., count-1, then 1, 2, .., count-1, 1, ...  (the opening is never repeated).
         */
        fun programmeIndex(segment: Int, count: Int): Int = when {
            count <= 1 -> 0
            segment < count -> segment
            else -> 1 + (segment - 1) % (count - 1)
        }

        /**
         * The order the movements are visited in this session: slot 0 is always the opening
         * movement (it is also the idle screen), slots 1..count-1 are a seeded shuffle of the rest.
         */
        fun programmeOrder(seed: Long, count: Int): IntArray {
            val order = IntArray(count) { it }
            for (i in count - 1 downTo 2) {
                val j = 1 + (SessionSeed.unit(seed, 100 + i) * i).toInt().coerceIn(0, i - 1)
                val t = order[i]; order[i] = order[j]; order[j] = t
            }
            return order
        }

        private fun smoothstep(x: Double): Double {
            val c = x.coerceIn(0.0, 1.0)
            return c * c * (3.0 - 2.0 * c)
        }

        /** Used only if [Glsl.VERTEX] itself fails to compile. */
        private val FALLBACK_VERTEX = """
            #version 300 es
            out vec2 vUv;
            void main() {
                vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = v;
                gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()

        /**
         * Self-contained breathing glow (deliberately independent of [Glsl.PRELUDE] so a prelude
         * bug cannot take it down).  Declares the full uniform contract; unused ones resolve to -1.
         */
        private val FALLBACK_FRAGMENT = """
            #version 300 es
            precision highp float;
            uniform vec2  uResolution;
            uniform float uTime;
            uniform float uElapsed;
            uniform float uBreath;
            uniform float uBreathPhase;
            uniform float uBreathVel;
            uniform float uIntensity;
            uniform float uRunning;
            uniform float uSeed;
            uniform float uFade;
            uniform float uAspect;
            in vec2 vUv;
            out vec4 fragColor;
            void main() {
                vec2 p = (vUv - 0.5) * 2.0;
                p.x *= uAspect;
                float r2 = dot(p, p);
                float radius = 0.35 + 0.30 * uBreath + 0.25 * uIntensity;
                float glow = exp(-r2 / (radius * radius)) * (0.10 + 0.40 * uBreath + 0.35 * uIntensity);
                float k = 0.5 + 0.5 * sin(uTime * 0.07 + uSeed);
                vec3 tint = mix(vec3(0.55, 0.42, 0.80), vec3(0.35, 0.62, 0.72), k);
                vec3 c = glow * tint;
                c = c / (1.0 + c);
                fragColor = vec4(c, uFade);
            }
        """.trimIndent()
    }
}
