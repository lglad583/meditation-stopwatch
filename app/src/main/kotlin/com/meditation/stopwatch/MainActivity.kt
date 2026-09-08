package com.meditation.stopwatch

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meditation.stopwatch.audio.AudioEngine
import com.meditation.stopwatch.session.SessionClock
import com.meditation.stopwatch.ui.MeditationApp
import com.meditation.stopwatch.ui.MeditationTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the one [SessionClock] of the process so it survives configuration changes and Activity
 * recreation.  Deliberately a plain no-argument ViewModel so the default factory can build it
 * (the lifecycle consumer ProGuard rules keep the constructor under R8).
 */
class SessionViewModel : ViewModel() {
    val clock = SessionClock()
}

class MainActivity : ComponentActivity() {

    private val session: SessionViewModel by viewModels()
    private lateinit var settings: SettingsRepository
    private lateinit var audio: AudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fully transparent bars on every API level – we hide them anyway, but when the user swipes
        // them in transiently they should float over the visuals, not sit on a scrim.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        hideSystemBars()

        settings = SettingsRepository.get(this)
        // The engine gets the application context: it lives exactly as long as this Activity, but
        // must never keep the Activity reachable from its render thread.
        audio = AudioEngine(applicationContext, session.clock, settings)

        // FLAG_KEEP_SCREEN_ON follows the persisted preference while the Activity is started.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settings.settings
                    .map { it.keepScreenOn }
                    .distinctUntilChanged()
                    .collect { keepOn ->
                        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
            }
        }

        setContent {
            MeditationTheme {
                MeditationApp(clock = session.clock, settings = settings)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        audio.start()
    }

    override fun onStop() {
        audio.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    /** Sticky immersive: a dismissed dialog or a returning window can bring the bars back. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
