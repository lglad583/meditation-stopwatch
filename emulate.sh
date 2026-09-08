#!/usr/bin/env bash
# Run the app in the Android emulator (NixOS; needs /dev/kvm and a display).
#   ./emulate.sh            -> create the AVD if needed, boot it, build, install and launch the app
#   ./emulate.sh --shell    -> just drop into a shell with emulator/adb/gradle on PATH
# Re-running while the emulator is up skips the boot and only reinstalls + relaunches.
# Set GPU=swiftshader_indirect if the host GPU path fails to start.
set -euo pipefail
cd "$(dirname "$0")"
export NIXPKGS_ALLOW_UNFREE=1
AVD=meditation
IMAGE="system-images;android-35;google_apis;x86_64"
GPU="${GPU:-host}"

if [ "${1:-}" = "--shell" ]; then
  exec nix-shell --arg emulator true
fi

exec nix-shell --arg emulator true --run "
set -euo pipefail
mkdir -p .android/avd
if ! avdmanager list avd -c | grep -qx '$AVD'; then
  echo '>> creating AVD $AVD'
  echo no | avdmanager create avd -n '$AVD' -k '$IMAGE' -d pixel_6 >/dev/null
  # OLED-ish phone with plenty of room; hardware keyboard so the soft one never covers the sheets
  cat >> .android/avd/$AVD.avd/config.ini <<INI
hw.gpu.enabled=yes
hw.gpu.mode=$GPU
hw.keyboard=yes
hw.ramSize=4096
disk.dataPartition.size=6G
hw.audioOutput=yes
INI
fi
if ! adb get-state >/dev/null 2>&1; then
  echo '>> booting emulator (first boot takes a minute or two)'
  emulator -avd '$AVD' -gpu '$GPU' -no-boot-anim >.android/emulator.log 2>&1 &
  adb wait-for-device
  until [ \"\$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')\" = 1 ]; do sleep 2; done
fi
# A fresh AVD starts with media volume at 5/15 (-33 dB), which makes the soundscapes inaudible.
adb shell media volume --stream 3 --set 15 >/dev/null 2>&1 || true
echo '>> building and installing'
gradle --no-daemon --console=plain installDebug
adb shell am start -n com.meditation.stopwatch/.MainActivity
echo '>> running; logs: adb logcat -s Mixer AudioEngine ShaderRenderer'
"
