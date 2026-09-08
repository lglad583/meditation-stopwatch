#!/usr/bin/env bash
# Build helper for NixOS: runs gradle inside the nix shell defined by shell.nix.
#   ./build.sh assembleDebug      -> app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh installDebug       -> installs on a connected adb device
set -euo pipefail
cd "$(dirname "$0")"
export NIXPKGS_ALLOW_UNFREE=1
exec nix-shell --run "gradle --no-daemon --console=plain $*"
