# Meditation Stopwatch – module contracts

Package root: `com.meditation.stopwatch` (sources under `app/src/main/kotlin`).

## Shared, already written (do not modify without reason)
- `session/Breath.kt` – `Breath.stateAt(elapsedSec): BreathState` (phase, fullness 0..1, velocity, stage, cycle, periodSec). Pure.
- `session/IntensityCurve.kt` – `IntensityCurve.at(elapsedSec): Float` 0..1. Pure.
- `session/SessionClock.kt` – `SessionClock` with `start()/pause()/toggle()/reset()/tick()`, `elapsedSec()`, `isRunning`, `hasStarted`, `state: StateFlow<SessionState>`.
- `Settings.kt` – `Settings` data class, `SettingsRepository.get(context)` with `settings: StateFlow<Settings>` and setters (`setVolume(id, v)`, `setMasterVolume`, `setBreathGuide`, `setBreathLabels`, `setKeepScreenOn`, `setRenderScale`, `muteAll`).
- `audio/SoundId.kt` – enum of the 12 sounds.
- `audio/SoundGenerator.kt` – `SoundGenerator` interface, `RenderContext`, `FastRandom`.
- `audio/SoundRegistry.kt` – expects classes `audio/generators/<Name>Generator` (see file).
- `visuals/Movement.kt`, `visuals/Glsl.kt` – shader contract: prelude + `vec3 scene(vec2 p, vec2 uv)`.
- `ui/Theme.kt` – `MeditationTheme`, colours `Mist`, `Glow`, `Lilac`, `Ink`.

## To be implemented
### audio/AudioEngine.kt
```kotlin
class AudioEngine(context: Context, clock: SessionClock, settings: SettingsRepository) {
    fun start()   // idempotent; creates AudioTrack + mixing thread. Call from Activity onStart.
    fun stop()    // idempotent; releases everything. Call from Activity onStop.
    val isActive: Boolean
}
```
Sounds play whenever their volume > 0 (even before the stopwatch starts, so the user can audition them);
the engine reads `settings.settings.value` each buffer and smooths gains.

### visuals/Movements.kt  (+ MovementsA.kt / MovementsB.kt)
```kotlin
object Movements { val all: List<Movement> }   // ordered programme of the session
```

### visuals/ShaderRenderer.kt + visuals/VisualSurface.kt
```kotlin
@Composable fun VisualSurface(clock: SessionClock, renderScale: Float, modifier: Modifier = Modifier)
```
Draws Movements.all full-screen behind the UI. Crossfades between movements over time.

### ui/MeditationApp.kt, ui/StopwatchScreen.kt, ui/SoundSheet.kt, MainActivity.kt
```kotlin
@Composable fun MeditationApp(clock: SessionClock, settings: SettingsRepository)
```
