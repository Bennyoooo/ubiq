#!/usr/bin/env bash
# Run the Pi harness (harness/) on this Mac against a phone or emulator over USB, for fast iteration on server.mjs.
# Without this script the phone runs Pi itself (agent/PiRuntime.kt); this points it at the Mac instead:
#   phone 127.0.0.1:8788 --adb reverse--> Mac harness      (the phone starts turns)
#   Mac 127.0.0.1:8787   --adb forward--> phone ToolHost   (the harness calls the phone's tools)
# Switches the phone's engine to "pi" with the Mac's address (debug builds) and reads its harness token with run-as.
#
# Usage:  scripts/pi-dev.sh [--serial <adb serial>] [--phone | --builtin]
#   --phone    back to Pi on the phone: clear the address, remove the port mappings and exit.
#   --builtin  switch the phone back to the built-in loop, remove the port mappings and exit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB_BIN="${ADB:-$ANDROID_HOME/platform-tools/adb}"
[ -x "$ADB_BIN" ] || ADB_BIN="$(command -v adb)"
SERIAL="${ANDROID_SERIAL:-}"; BUILTIN=0; PHONE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL="$2"; shift ;;
    --builtin) BUILTIN=1 ;;
    --phone) PHONE=1 ;;
    *) echo "unknown arg $1"; exit 1 ;;
  esac; shift
done
ADB=("$ADB_BIN"); [ -n "$SERIAL" ] && ADB+=(-s "$SERIAL")
PKG=ai.ubiq.app

pref() { "${ADB[@]}" shell "am broadcast -a com.agentos.DEBUG_PREF --es key $1 --es value '$2' $PKG" >/dev/null; }

if [ "$BUILTIN" = 1 ] || [ "$PHONE" = 1 ]; then
  pref harness_url ''
  if [ "$BUILTIN" = 1 ]; then pref engine builtin; else pref engine pi; fi
  "${ADB[@]}" forward --remove tcp:8787 2>/dev/null || true
  "${ADB[@]}" reverse --remove tcp:8788 2>/dev/null || true
  if [ "$BUILTIN" = 1 ]; then echo "Phone is back on the built-in loop."; else echo "Phone runs Pi itself again."; fi
  exit 0
fi

pref engine pi
pref harness_url http://127.0.0.1:8788
sleep 1
TOKEN="$("${ADB[@]}" shell run-as $PKG cat files/harness_token 2>/dev/null | tr -d '\r\n')"
[ -n "$TOKEN" ] || { echo "Could not read the harness token (needs a debug build of Ubiq: run-as)."; exit 1; }
"${ADB[@]}" forward tcp:8787 tcp:8787 >/dev/null
"${ADB[@]}" reverse tcp:8788 tcp:8788 >/dev/null

cd "$ROOT/harness"
[ -d node_modules ] || npm install --silent
echo "Phone engine: pi. Tool host: 127.0.0.1:8787 (forwarded). Harness: 127.0.0.1:8788 (reversed). Ctrl-C stops; --phone or --builtin switches back."
AGENTOS_HARNESS_TOKEN="$TOKEN" exec node server.mjs
