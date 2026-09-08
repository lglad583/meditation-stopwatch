#!/usr/bin/env bash
# Validate a movement body (GLSL ES 3.00 fragment) against the app prelude.
#   tools/validate-shader.sh path/to/body.frag
# Prints nothing and exits 0 on success; prints compiler errors otherwise.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GLSLANG="${GLSLANG:-/nix/store/mki4w5ana75m2am772qc3i40jrx7hzmv-glslang-16.2.0-bin/bin/glslangValidator}"
[ -x "$GLSLANG" ] || GLSLANG="$(command -v glslangValidator || true)"
[ -x "$GLSLANG" ] || { echo "glslangValidator not found; try: nix shell nixpkgs#glslang"; exit 2; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# Extract the PRELUDE raw string from Glsl.kt (between 'val PRELUDE = """' and the closing '"""').
awk '/val PRELUDE = """/{flag=1; next} flag && /^\s*""".trimIndent\(\)/{flag=0} flag{print}' \
    "$ROOT/app/src/main/kotlin/com/meditation/stopwatch/visuals/Glsl.kt" | sed 's/^        //' > "$TMP/full.frag"
printf '\n// ---- body ----\n' >> "$TMP/full.frag"
cat "$1" >> "$TMP/full.frag"
"$GLSLANG" -S frag "$TMP/full.frag"
status=$?
if [ $status -ne 0 ]; then
  echo "--- line numbers above refer to the combined file; prelude is $(grep -c '' "$TMP/full.frag" | awk -v b=$(grep -c '' "$1") '{print $1-b-2}') lines, so subtract that for body lines."
fi
exit $status
