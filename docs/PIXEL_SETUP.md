# Putting Ubiq on your Pixel

There are two levels. Level 1 is what you can do right now; Level 2 is the real ROM.

## Level 1 — install on stock Android (keeps the bootloader locked)

What it gives you: the Ubiq app as the phone's assistant (hold the power button), full app control via accessibility, voice. Your home screen stays as it is. Everything in the README's "works today" list. This is how I tested it.

1. **Enable USB debugging** on the Pixel: Settings → About phone → tap *Build number* seven times → back → System → Developer options → *USB debugging* on.
2. **Connect** the phone to the Mac with a USB-C cable. Unlock the phone and accept "Allow USB debugging?" (tick *Always allow*).
3. Check it is visible:
   ```
   ~/Library/Android/sdk/platform-tools/adb devices     # should list a serial with "device"
   ```
4. Install and wire up:
   ```
   scripts/install.sh --api-key sk-...     # your OpenAI key
   ```
   This builds the APK, installs it, grants mic/notification permissions, enables the *Ubiq Agent Control* accessibility service, and sets Ubiq as the ASSISTANT role holder.
5. Open Ubiq. If the setup card still says agent control is off, open it via the card's button and toggle *Ubiq Agent Control* on (Android 13+ may show a "Restricted setting" dialog for sideloaded apps; if so, tap the Ubiq app info → ⋮ → *Allow restricted settings*, then try again).

6. **Background mode (optional, recommended).** Install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api), start it (the Shizuku app explains wireless-debugging start, or run `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh` from the Mac; it stays running until reboot), then Ubiq Settings → **Grant Ubiq access to Shizuku** → *Allow all the time*. From then on tasks run on a hidden display: your screen stays yours, a **Watch** chip in the chat shows the agent's screen live, and the task notification offers **Peek** and **Stop** when you are in another app. Switch it off with *Run tasks in the background* in the same Settings page.

Going back to normal: Settings → Apps → Default apps → Digital assistant app → Google. Uninstall with `adb uninstall ai.ubiq.app`.

### Wireless (no cable after the first time)
Developer options → *Wireless debugging* → pair, then `adb connect <ip:port>`. `scripts/install.sh --serial <ip:port>` works the same.

## Level 2 — flash a ROM with Ubiq baked in

See `ROM.md`. Short version: one `modal run` command builds AOSP 15 + Ubiq for `cheetah` on a 32-core cloud box (2–4 hours); then unlock the bootloader and `fastboot flashall`.

## What I need from you to drive the phone directly

- The phone connected over USB with debugging on (Level 1 step 1–2) *to the Mac this session runs on*. Then every `adb` command in `scripts/install.sh` works from here and I can install, screenshot and debug on the real device.
- Your OpenAI API key (paste it into Ubiq Settings on the phone, or pass `--api-key`). It never leaves the phone.
- Pixel 7 Pro = codename `cheetah` (already wired into the ROM docs).
