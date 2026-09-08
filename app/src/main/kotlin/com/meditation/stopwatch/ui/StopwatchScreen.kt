package com.meditation.stopwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.meditation.stopwatch.session.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Geometry & timing
// ---------------------------------------------------------------------------------------------

/** Diameter of the tappable centre button. */
private val ButtonSize = 180.dp

/** The ring canvas is larger than the button so the halo can bloom outwards without clipping. */
private val RingCanvasSize = 300.dp

/** Where (as a fraction of the halo gradient radius) the halo is brightest; see [BreathRing]. */
private const val HALO_PEAK = 0.6f

/** Period of the idle "breathing" of the ring and the word *begin*. */
private const val IDLE_PERIOD_MS = 10_000

private const val CHROME_HIDE_DELAY_MS = 6_000L
private const val TWO_PI = 6.2831855f

// ---------------------------------------------------------------------------------------------
// Typography – light weights, wide tracking, tabular digits so the clock never jitters.
// ---------------------------------------------------------------------------------------------

private val TimeStyle = TextStyle(
    color = Mist,
    fontSize = 56.sp,
    lineHeight = 60.sp,
    fontWeight = FontWeight.ExtraLight,
    letterSpacing = 0.05.em,
    fontFeatureSettings = "tnum",
    textAlign = TextAlign.Center,
)
private val TimeStyleHours = TimeStyle.copy(fontSize = 38.sp, letterSpacing = 0.04.em)

private val StageStyle = TextStyle(
    color = Mist,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = 0.32.em,
    textAlign = TextAlign.Center,
)

private val CounterStyle = TextStyle(
    color = Mist.copy(alpha = 0.45f),
    fontSize = 11.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = 0.22.em,
    textAlign = TextAlign.Center,
)

private val BeginStyle = TextStyle(
    color = Mist,
    fontSize = 19.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = 0.42.em,
    textAlign = TextAlign.Center,
)

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

/**
 * The stopwatch itself: one breathing circle in the middle of the visuals, and a whisper of
 * chrome that gets out of the way while a session is running.
 *
 * @param keepChrome  true while a sheet is open, so the auto-hide timer does not run behind it.
 */
@Composable
fun StopwatchScreen(
    state: SessionState,
    breathGuide: Boolean,
    breathLabels: Boolean,
    keepChrome: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onOpenSounds: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bumped by every touch anywhere on the screen; restarts the auto-hide countdown.
    var interactionSerial by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }

    LaunchedEffect(state.running, keepChrome, interactionSerial) {
        chromeVisible = true
        if (state.running && !keepChrome) {
            delay(CHROME_HIDE_DELAY_MS)
            chromeVisible = false
        }
    }

    // While the chrome is hidden the readout recedes too, so the visuals dominate.
    val focusAlpha by animateFloatAsState(
        targetValue = if (chromeVisible) 1f else 0.55f,
        animationSpec = tween(durationMillis = 1400),
        label = "focus",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Observe every down in the Initial pass and never consume it: the button and the
                // icon buttons still receive the gesture untouched; we only learn that it happened.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    interactionSerial++
                }
            },
    ) {
        Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            if (state.everStarted) {
                BreathRing(
                    fullness = state.breath.fullness,
                    intensity = state.intensity,
                    running = state.running,
                    guided = breathGuide,
                    modifier = Modifier.size(RingCanvasSize),
                )
                CentreButton(everStarted = true, contentAlpha = focusAlpha, onToggle = onToggle, onReset = onReset) {
                    SessionReadout(state = state, breathLabels = breathLabels)
                }
            } else {
                val idle = rememberIdleBreath()
                IdleRing(phase = idle, modifier = Modifier.size(RingCanvasSize))
                CentreButton(everStarted = false, contentAlpha = 1f, onToggle = onToggle, onReset = onReset) {
                    BeginLabel(phase = idle)
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            enter = fadeIn(tween(durationMillis = 500)),
            exit = fadeOut(tween(durationMillis = 1200)),
        ) {
            Row(modifier = Modifier.padding(top = 10.dp, end = 10.dp)) {
                ChromeButton(icon = Icons.Rounded.Tune, description = "sounds", onClick = onOpenSounds)
                Spacer(Modifier.width(4.dp))
                ChromeButton(icon = Icons.Rounded.Settings, description = "settings", onClick = onOpenSettings)
            }
        }

        IntensityLine(
            intensity = state.intensity,
            emphasised = chromeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 44.dp, end = 44.dp, bottom = 16.dp),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Centre button
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CentreButton(
    everStarted: Boolean,
    contentAlpha: Float,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // No ripple – a slow, barely-there settle is the only press feedback.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "press",
    )
    Box(
        modifier = Modifier
            .size(ButtonSize)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = contentAlpha
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onLongClick = {
                    if (everStarted) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReset()
                    }
                },
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Idle phase 0..1 over a 10 s period; read in the draw phase only, so nothing recomposes. */
@Composable
private fun rememberIdleBreath(): State<Float> {
    val transition = rememberInfiniteTransition(label = "idle")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = IDLE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "idlePhase",
    )
}

@Composable
private fun BeginLabel(phase: State<Float>) {
    Text(
        text = "begin",
        style = BeginStyle,
        maxLines = 1,
        modifier = Modifier
            // Compensate the trailing letter-spacing so the word sits optically centred.
            .padding(start = 8.dp)
            .graphicsLayer {
                val s = sin(phase.value * TWO_PI)
                alpha = 0.55f + 0.35f * (0.5f + 0.5f * s)
            },
    )
}

@Composable
private fun SessionReadout(state: SessionState, breathLabels: Boolean) {
    val totalSec = state.elapsedMs / 1000L
    // Formatting allocates, so only do it when the displayed second actually changes.
    val timeText = remember(totalSec) { formatElapsed(totalSec) }
    val counter = remember(state.breath.cycle) { "breath " + (state.breath.cycle + 1) }

    val stageText = when {
        !state.running -> "paused"
        breathLabels -> state.breath.stage.label
        else -> ""
    }
    val counterText = if (state.running) counter else "hold to reset"

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = timeText,
            style = if (totalSec >= 3600L) TimeStyleHours else TimeStyle,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.height(60.dp),
        )
        Spacer(Modifier.height(6.dp))
        // Fixed-height slots: toggling labels must never move the clock.
        FadingLabel(text = stageText, style = StageStyle, modifier = Modifier.height(18.dp))
        Spacer(Modifier.height(2.dp))
        FadingLabel(text = counterText, style = CounterStyle, modifier = Modifier.height(16.dp))
    }
}

/**
 * Text that cross-fades whenever its content changes: the old word fades out, the new one fades
 * in.  The swap happens only once the fade-out has completed, so a rapid sequence of changes is
 * collapsed onto the latest value rather than flickering through every intermediate one.
 */
@Composable
private fun FadingLabel(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(text) }
    val settled = shown == text
    val labelAlpha by animateFloatAsState(
        targetValue = if (settled && text.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = if (settled) 700 else 350),
        label = "label",
    )
    LaunchedEffect(text) {
        if (shown != text) {
            snapshotFlow { labelAlpha }.first { it <= 0.002f }
            shown = text
        }
    }
    Text(
        text = shown,
        style = style,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .padding(start = 4.dp)
            .graphicsLayer { alpha = labelAlpha },
    )
}

/** `mm:ss`, or `h:mm:ss` once an hour has passed. */
private fun formatElapsed(totalSec: Long): String {
    val h = totalSec / 3600L
    val m = (totalSec / 60L) % 60L
    val s = totalSec % 60L
    val sb = StringBuilder(8)
    if (h > 0L) sb.append(h).append(':')
    if (m < 10L) sb.append('0')
    sb.append(m).append(':')
    if (s < 10L) sb.append('0')
    sb.append(s)
    return sb.toString()
}

// ---------------------------------------------------------------------------------------------
// Rings
// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberStroke(width: Dp): Stroke {
    val density = LocalDensity.current
    return remember(density, width) { Stroke(width = with(density) { width.toPx() }) }
}

/** Before the first start: a thin ring that breathes on a 10 s sine, scale 0.94..1.06. */
@Composable
private fun IdleRing(phase: State<Float>, modifier: Modifier) {
    val crisp = rememberStroke(1.dp)
    val soft = rememberStroke(6.dp)
    Canvas(modifier) {
        val s = sin(phase.value * TWO_PI)
        val radius = ButtonSize.toPx() * 0.5f * (1f + 0.06f * s)
        val alpha = 0.45f + 0.30f * (0.5f + 0.5f * s)
        drawCircle(color = Glow, radius = radius, style = soft, alpha = alpha * 0.18f)
        drawCircle(color = Mist, radius = radius, style = crisp, alpha = alpha)
    }
}

/**
 * The breath guide: a soft halo plus a crisp ring whose radius follows lung fullness
 * (0.55..1.0 of the button radius).  The halo is one remembered radial gradient whose peak sits at
 * [HALO_PEAK] of the canvas radius; instead of rebuilding a shader every frame we scale the canvas
 * so that peak lands exactly on the ring – zero allocation per frame.
 */
@Composable
private fun BreathRing(
    fullness: Float,
    intensity: Float,
    running: Boolean,
    guided: Boolean,
    modifier: Modifier,
) {
    // Paused: the ring holds its shape but loses most of its light.
    val presence by animateFloatAsState(
        targetValue = if (running) 1f else 0.45f,
        animationSpec = tween(durationMillis = 900),
        label = "presence",
    )
    val crisp = rememberStroke(1.25.dp)
    val halo = remember {
        Brush.radialGradient(
            0f to Color.Transparent,
            HALO_PEAK - 0.13f to Color.Transparent,
            HALO_PEAK to Glow.copy(alpha = 0.55f),
            HALO_PEAK + 0.11f to Glow.copy(alpha = 0.16f),
            1f to Color.Transparent,
        )
    }
    Canvas(modifier) {
        val f = if (guided) fullness else 0.6f
        val radius = ButtonSize.toPx() * 0.5f * (0.55f + 0.45f * f)
        val gradientRadius = size.minDimension * 0.5f
        val haloAlpha = presence * (0.35f + 0.35f * f + 0.30f * intensity).coerceAtMost(1f)
        scale(scale = radius / (gradientRadius * HALO_PEAK)) {
            drawCircle(brush = halo, radius = gradientRadius, alpha = haloAlpha)
        }
        drawCircle(color = Mist, radius = radius, style = crisp, alpha = presence * (0.7f + 0.3f * f))
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------------------------

@Composable
private fun ChromeButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .background(Mist.copy(alpha = 0.07f), CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Mist.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A whisper-thin line along the bottom showing how far the piece has intensified. */
@Composable
private fun IntensityLine(intensity: Float, emphasised: Boolean, modifier: Modifier) {
    val brush = remember { Brush.horizontalGradient(0f to Glow, 1f to Lilac) }
    val lineAlpha by animateFloatAsState(
        targetValue = if (emphasised) 0.28f else 0.14f,
        animationSpec = tween(durationMillis = 1400),
        label = "line",
    )
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        if (intensity <= 0f) return@Canvas
        val y = size.height * 0.5f
        drawLine(
            brush = brush,
            start = Offset(0f, y),
            end = Offset(size.width * intensity, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
            alpha = lineAlpha,
        )
    }
}
