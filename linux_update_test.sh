#!/usr/bin/env bash
# Interactive packaged updater test; all application data stays in a fresh sandbox.
set -euo pipefail

case "${1:-}" in
  --help|-h)
    printf 'Usage: %s [--check-prerequisites]\n' "$0"
    printf 'Build two desktop versions and open an isolated local update test. Requires Linux x64, JDK 25 and Python 3.10+.\n'
    exit 0
    ;;
  ''|--check-prerequisites) ;;
  *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
esac
if (( $# > 1 )); then
  printf 'Only --check-prerequisites or --help is accepted.\n' >&2
  exit 2
fi

trap 'test_exit_code=$?; printf "ERROR: Test stopped (exit %s). Files and logs: %s\n" "$test_exit_code" "${run_directory:-not created yet}" >&2; exit "$test_exit_code"' ERR

fail() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

[[ "$(uname -s)" == Linux ]] || fail 'Run this launcher on Linux, not Windows or macOS.'
case "$(uname -m)" in
  x86_64|amd64) ;;
  *) fail 'The desktop updater test requires Linux x64.' ;;
esac

repository="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ -f "$repository/mvnw" && -f "$repository/scripts/updater/updater_tools.py" ]] || fail 'Keep this launcher in the fm-ai-assistent repository root.'
[[ -n "${JAVA_HOME:-}" && -f "$JAVA_HOME/release" ]] || fail 'Set JAVA_HOME to your full JDK 25 installation, then run again.'
grep -Eq '^JAVA_VERSION="25[."]' "$JAVA_HOME/release" || fail 'JAVA_HOME must point to JDK 25.'
for tool in java jpackage jlink; do
  [[ -x "$JAVA_HOME/bin/$tool" ]] || fail "JDK 25 is missing executable bin/$tool. Install a full JDK."
done

python_executable=''
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
    python_executable="$(command -v "$candidate")"
    break
  fi
done
if [[ -z "$python_executable" ]] && command -v uv >/dev/null 2>&1; then
  candidate="$(uv python find 2>/dev/null || true)"
  if [[ -n "$candidate" && -x "$candidate" ]] && "$candidate" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' >/dev/null 2>&1; then
    python_executable="$candidate"
  fi
fi
[[ -n "$python_executable" ]] || fail 'Install Python 3.10+ (or make it available through uv), then run again.'

printf 'JDK:    %s\nPython: %s\n' "$JAVA_HOME" "$python_executable"
if [[ "${1:-}" == --check-prerequisites ]]; then
  printf 'Prerequisites OK. No builds or applications were started.\n'
  exit 0
fi
[[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]] || fail 'Run the interactive test from a graphical Linux desktop session.'

run_directory="$(mktemp -d "${TMPDIR:-/tmp}/fmai-updater-manual.XXXXXXXX")"
run_directory="$(cd -- "$run_directory" && pwd -P)"
builds="$run_directory/builds"
sandbox="$run_directory/sandbox"
printf 'Test files and logs: %s\n' "$run_directory"
printf 'Building versions 2.1.0 and 2.1.1. This can take several minutes.\n'
printf 'Build logs: %s/old-build.log and %s/new-build.log\n' "$builds" "$builds"
"$python_executable" "$repository/scripts/updater/updater_tools.py" build --output-directory "$builds"

printf '\nThe isolated 2.1.0 app will open. Wait for the LOCAL update offer, then click Download & install once.\n'
printf 'After it restarts as 2.1.1, expect PASS here. Close the test app after inspection.\n'
"$python_executable" "$repository/scripts/updater/updater_tools.py" test \
  --old-image "$builds/old/target/dist/FM AI Assistent" \
  --new-image "$builds/new/target/dist/FM AI Assistent" \
  --sandbox "$sandbox" --wait --timeout 1800
printf 'Test completed. Files retained at %s\n' "$run_directory"
