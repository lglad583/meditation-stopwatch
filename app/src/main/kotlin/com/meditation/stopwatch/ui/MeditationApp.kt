package com.meditation.stopwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditation.stopwatch.SettingsRepository
import com.meditation.stopwatch.session.SessionClock
import com.meditation.stopwatch.visuals.VisualSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How often the UI-side state snapshot is refreshed while the stopwatch runs (~30 Hz). */
private const val TICK_MS = 33L

private enum class Sheet { NONE, SOUNDS, SETTINGS }

/**
 * Root of the composition: the GPU visuals fill the window, the stopwatch sits on top, and the
 * two bottom sheets are composed on demand.  This is also where [SessionClock.tick] is driven –
 * the clock itself is passive so the GL and audio threads can read it without any scheduling.
 */
@Composable
fun MeditationApp(clock: SessionClock, settings: SettingsRepository) {
    val state by clock.state.collectAsStateWithLifecycle()
    val prefs by settings.settings.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // Ticker: keyed on `running` so it restarts (and publishes one fresh snapshot) whenever the
    // stopwatch starts, pauses or resets, and loops only while time is actually advancing.
    LaunchedEffect(state.running) {
        if (state.running) {
            while (isActive) {
                clock.tick()
                delay(TICK_MS)
            }
        } else {
            clock.tick()
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        VisualSurface(
            clock = clock,
            renderScale = prefs.renderScale,
            modifier = Modifier.fillMaxSize(),
        )
        StopwatchScreen(
            state = state,
            breathGuide = prefs.breathGuide,
            breathLabels = prefs.breathLabels,
            keepChrome = sheet != Sheet.NONE,
            onToggle = clock::toggle,
            onReset = clock::reset,
            onOpenSounds = { sheet = Sheet.SOUNDS },
            onOpenSettings = { sheet = Sheet.SETTINGS },
            modifier = Modifier.fillMaxSize(),
        )
    }

    when (sheet) {
        Sheet.SOUNDS -> SoundSheet(settings = settings, onDismiss = { sheet = Sheet.NONE })
        Sheet.SETTINGS -> SettingsSheet(settings = settings, onDismiss = { sheet = Sheet.NONE })
        Sheet.NONE -> Unit
    }
}
