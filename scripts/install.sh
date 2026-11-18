#!/usr/bin/env bash
# Build Ubiq and install it on the connected Pixel (or emulator), then wire it into the
# system: accessibility control, default assistant, mic permission.
#
# Usage:  scripts/install.sh [--release] [--no-build] [--serial <adb serial>] [--api-key sk-ant-...]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$( [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17 || echo "${JAVA_HOME:-}" )}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
[ -x "$ADB" ] || ADB="$(command -v adb)"

VARIANT=debug; BUILD=1; SERIAL=""; API_KEY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --release) VARIANT=release ;;
    --no-build) BUILD=0 ;;
    --serial) SERIAL="$2"; shift ;;
    --api-key) API_KEY="$2"; shift ;;
    *) echo "unknown arg $1"; exit 1 ;;
  esac; shift
done
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

PKG=ai.ubiq.app
SVC=$PKG/com.agentos.control.ControlService
# The Gradle build to run: this repo's android/ by default; a build that includes these modules can point here instead.
GRADLE_DIR="${AGENTOS_GRADLE_DIR:-$ROOT/android}"
APK="$ROOT/android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"

if [ "$BUILD" = 1 ]; then
  echo "==> Building $VARIANT APK"
  ( cd "$GRADLE_DIR" && ./gradlew -q ":app:assemble${VARIANT^}" )
fi
[ -f "$APK" ] || { echo "APK not found at $APK"; exit 1; }

echo "==> Waiting for device"
$ADB wait-for-device
$ADB shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
MODEL=$($ADB shell getprop ro.product.model | tr -d '\r')
SDK=$($ADB shell getprop ro.build.version.sdk | tr -d '\r')
echo "    device: $MODEL (API $SDK)"

echo "==> Installing"
$ADB install -r -g "$APK"

echo "==> Granting runtime permissions"
$ADB shell pm grant $PKG android.permission.RECORD_AUDIO || true
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

echo "==> Enabling the Ubiq accessibility (agent control) service"
CUR=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$CUR" in
  *"$SVC"*) ;;
  null|"") $ADB shell settings put secure enabled_accessibility_services "$SVC" ;;
  *) $ADB shell settings put secure enabled_accessibility_services "$CUR:$SVC" ;;
esac
$ADB shell settings put secure accessibility_enabled 1

echo "==> Making Ubiq the default assistant"
# The hidden workspace needs a companion association to get its own power group and keep running with the
# screen off. Android 17 only makes one silently for allowlisted apps, so Core cannot do it alone. The system keeps it.
$ADB shell cmd companiondevice associate 0 com.agentos.core 02:00:00:a6:e0:05 android.app.role.COMPANION_DEVICE_APP_STREAMING true >/dev/null 2>&1 || true
# Role manager (Android 10+). Falls back silently if the shell can't set roles on this build.
# Re-assign the assistant role so Android binds Ubiq's voice-interaction service (the sheet that
# opens over the current app). Re-adding an already-held role is a no-op, hence remove + add.
$ADB shell cmd role remove-role-holder --user 0 android.app.role.ASSISTANT $PKG >/dev/null 2>&1 || true
$ADB shell cmd role add-role-holder --user 0 android.app.role.ASSISTANT $PKG >/dev/null 2>&1 || true
# Holding the power button opens the assistant (Pixel default is the power menu; 5 = assistant).
$ADB shell settings put global power_button_long_press 5 >/dev/null 2>&1 || true
# Swipe up from a bottom corner opens the assistant. In gesture navigation Pixel only offers the
# assistant gestures when this is on, and it defaults to off. (Holding the nav bar stays Google's
# Circle to Search: the Pixel launcher never hands that gesture to another assistant.)
$ADB shell settings put secure assist_touch_gesture_enabled 1 >/dev/null 2>&1 || true
# No accessibility shortcut: the service asks for no button, so a shortcut would only switch it off.
$ADB shell settings put secure accessibility_button_targets "" >/dev/null 2>&1 || true
$ADB shell settings put secure accessibility_gesture_targets "" >/dev/null 2>&1 || true
# Let the assistant see the current app's text and a screenshot when invoked.
$ADB shell settings put secure assist_structure_enabled 1 >/dev/null 2>&1 || true
$ADB shell settings put secure assist_screenshot_enabled 1 >/dev/null 2>&1 || true
echo "    assistant: $($ADB shell settings get secure voice_interaction_service)"

echo "==> Allowing notification access (read notifications, rules, digests)"
$ADB shell cmd notification allow_listener $PKG/com.agentos.notify.NotificationWatcher >/dev/null 2>&1 || echo "    (allow it in Ubiq Settings > Notifications)"

if [ -n "$API_KEY" ]; then
  echo "==> Writing API key into Ubiq settings"
  # Debug builds are run-as-able; on release builds paste the key in Settings instead.
  $ADB shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/agentos_prefs.xml'" <<XML || echo "    (could not write key via run-as; paste it in Ubiq Settings)"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="api_key">$API_KEY</string>
</map>
XML
fi

echo "==> Launching"
$ADB shell am start -n $PKG/com.agentos.ui.HomeActivity >/dev/null
echo
echo "Done. Ubiq opens on its chat; hold the power button anywhere to ask it."
echo "If 'Agent control' still shows as off, open Settings → Accessibility → Ubiq Agent Control → On."
