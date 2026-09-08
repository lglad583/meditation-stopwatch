package com.meditation.stopwatch.visuals

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meditation.stopwatch.session.SessionClock
import kotlin.math.roundToInt

/**
 * Full-screen GPU visuals for the session, meant to sit at the bottom of a Box with the UI on top.
 *
 * @param renderScale fraction of the view size the GPU renders at (0.25..1); lower is cheaper.
 *                    Changing it resizes the surface buffer in place (the compositor scales it up).
 */
@Composable
fun VisualSurface(clock: SessionClock, renderScale: Float, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Set from the factory so the lifecycle effect below can address the real view instance.
    var glView by remember { mutableStateOf<VisualGlView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> VisualGlView(ctx, clock).also { glView = it } },
        update = { it.setRenderScale(renderScale) },
    )

    // GLSurfaceView must be told about pause/resume or it keeps its GL thread rendering into a
    // surface that is about to vanish (and drains battery from the background).
    DisposableEffect(glView, lifecycleOwner) {
        val view = glView
        if (view == null) {
            onDispose { }
        } else {
            val lifecycle = lifecycleOwner.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)   // replays ON_RESUME immediately if already resumed
            onDispose { lifecycle.removeObserver(observer) }
        }
    }
}

/**
 * GLSurfaceView configured for the shader renderer: ES 3.0, RGBA8 with no depth/stencil, context
 * preserved across pause, continuous rendering, and a reduced-resolution buffer via
 * [android.view.SurfaceHolder.setFixedSize].  It never consumes touches, so Compose UI stacked on
 * top keeps working.
 */
private class VisualGlView(context: Context, clock: SessionClock) : GLSurfaceView(context) {
    private val renderer = ShaderRenderer(clock)
    private var fixedW = 0
    private var fixedH = 0

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        isClickable = false
        isLongClickable = false
        isFocusable = false
    }

    /** Called from AndroidView.update on the UI thread. */
    fun setRenderScale(scale: Float) {
        renderer.renderScale = scale.coerceIn(MIN_SCALE, 1f)
        applyFixedSize()
    }

    /** Layout callback: the first time the size is known (and on rotation) size the buffer. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFixedSize()
    }

    private fun applyFixedSize() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return   // not laid out yet; onSizeChanged will call again
        val s = renderer.renderScale
        val fw = maxOf(1, (w * s).roundToInt())
        val fh = maxOf(1, (h * s).roundToInt())
        if (fw == fixedW && fh == fixedH) return
        fixedW = fw
        fixedH = fh
        holder.setFixedSize(fw, fh)   // GLSurfaceView receives surfaceChanged(fw, fh) -> glViewport
    }

    // Never consume touches: everything interactive lives in the Compose layer above.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object {
        const val MIN_SCALE = 0.25f
    }
}
