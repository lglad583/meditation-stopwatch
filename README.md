# Meditation Stopwatch

An Android meditation stopwatch that turns time into a slowly deepening audiovisual journey.

- **One button.** Tap the luminous circle to begin. Tap again to pause, hold to reset.
- **A breathing guide.** The ring around the time inflates and deflates on a coherent-breathing
  rhythm that eases from six to five breaths per minute over the first eight minutes
  (a long exhale, short holds). Every visual and several sounds are locked to the same breath.
- **Twelve synthesised soundscapes**, each with its own volume slider: water stream, rain, thunder,
  ocean (its swell follows your breath), nature (birds, crickets, leaves), wind, campfire,
  white / pink / brown noise, a singing-bowl drone that swells as you inhale, and a 6 Hz binaural
  theta beat. Nothing is a recording; everything is generated live on the audio thread.
- **Generative visuals** rendered by GPU fragment shaders: a programme of eight "movements"
  (Threshold, Bloom, Mandala, Lumen, Descent, Nebula, Cathedral, Tide) that cross-fade every
  150 seconds. Fractals, kaleidoscopes, domain-warped colour fields, interference patterns and
  volumetric clouds, all moving at breathing speed.
- **Intensity that builds quickly, then never stops.** A fast curve carries the piece past half
  within two minutes, so it is clearly evolving within the first half minute, and a slow one keeps
  it climbing for the whole hour, roughly a tenth every ten minutes, without ever jumping. Iteration
  counts, saturation, fold order, warp depth, chromatic aberration and animation speed all follow it.
- **Never the same twice.** Every session draws a fresh seed: shader palettes and layouts, the
  order the movements arrive in (the opening is always first), and the phases and periods of the
  mix drift all follow it, and each sound generator randomises its own grain on creation.
- **A living mix.** The soundscape lifts by seven decibels over the session, and every sound drifts
  forward and back on its own slow tide, deeper as the intensity grows, so the balance you set keeps
  shifting without touching a slider.

## Build

Requires Android SDK 35 and JDK 17.

```
./gradlew assembleDebug        # any machine with the SDK installed
./build.sh assembleDebug       # NixOS: uses shell.nix to provide SDK + JDK + Gradle
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`, or grab a prebuilt one from the
[releases page](../../releases).

### Emulator

```
./emulate.sh                  # NixOS: create a Pixel 6 / API 35 AVD, boot it, build, install, launch
GPU=swiftshader_indirect ./emulate.sh   # software rendering if the host GPU path fails
```

The first run downloads the emulator and system image (about 1.5 GB). Re-running while the
emulator is up only reinstalls and relaunches. Needs `/dev/kvm` and a display.

## Layout

```
app/src/main/kotlin/com/meditation/stopwatch/
  session/    Breath model, intensity curve, session clock (pure functions of elapsed time)
  audio/      AudioTrack mixer + one procedural generator per sound
  visuals/    GLSL prelude, eight movements, GL renderer, Compose surface
  ui/         Stopwatch screen, sound sheet, settings sheet, theme
tools/validate-shader.sh   compile-check a movement body with glslangValidator
```

## Tests

`./build.sh testDebugUnitTest` runs breath/intensity model tests and dumps every movement shader to
`app/build/shaders/` for offline validation.

## Licence

Apache License 2.0 – see [LICENSE](LICENSE).
