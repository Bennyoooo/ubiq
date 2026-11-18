#!/usr/bin/env bash
# Boot the local Pixel 7 / Android 15 emulator used for development. Creates the AVD on first run.
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD=agentos
if [ ! -f "$HOME/.android/avd/$AVD.ini" ]; then
  echo "==> Creating AVD $AVD"
  mkdir -p "$HOME/.android/avd/$AVD.avd"
  cat > "$HOME/.android/avd/$AVD.ini" <<INI
avd.ini.encoding=UTF-8
path=$HOME/.android/avd/$AVD.avd
path.rel=avd/$AVD.avd
target=android-35
INI
  cat > "$HOME/.android/avd/$AVD.avd/config.ini" <<'INI'
AvdId=agentos
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=agentos
disk.dataPartition.size=6G
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.device.name=pixel_7
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=4096
hw.audioInput=yes
image.sysdir.1=system-images/android-35/google_apis/arm64-v8a/
tag.display=Google APIs
tag.id=google_apis
INI
fi
exec "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -gpu auto -no-snapshot "$@"
