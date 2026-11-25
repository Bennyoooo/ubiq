# Installing Ubiq

Ubiq is not on Google Play. You install it by downloading the APK from this repository's
[releases page](https://github.com/Bennyoooo/ubiq/releases/latest).

You need:

- An Android phone running Android 12 or newer with a 64-bit ARM processor (every recent phone).
- An OpenAI API key. Ubiq sends its requests straight from your phone to OpenAI with your key;
  usage is billed to your OpenAI account.

## 1. Download and install

1. On the phone, open the [latest release](https://github.com/Bennyoooo/ubiq/releases/latest) and
   tap `ubiq-<version>.apk` under **Assets**. The "Source code" archives are not the app.
2. Open the downloaded file. If Android asks, allow your browser (or Files) to install apps, then
   tap **Install**.

## 2. Set it up

Open Ubiq. Its setup card and Settings page lead you through these:

1. **API key.** Settings > Brain: paste your OpenAI API key.
2. **Agent control.** Settings > Phone integration > **Accessibility settings**, then turn on
   **Ubiq Agent Control**. This is what lets Ubiq read the screen and tap for you.

   Android blocks this switch for apps installed from outside a store. If it is greyed out or says
   "Restricted setting": open Settings > Apps > Ubiq, tap **⋮** in the top right corner, choose
   **Allow restricted settings**, and try the switch again.
3. **Assistant (optional).** Settings > Phone integration > **Set Ubiq as the assistant**, so a
   long press on the power button opens Ubiq.

Then type or say what you want done.

## 3. Background mode (optional)

Without this, Ubiq works on the screen you are looking at. With it, Ubiq works on a hidden second
screen while you keep using the phone, and you can watch it with the **Watch** button.

It needs [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api), a
free helper app. Install and start it (the Shizuku app explains how), then in Ubiq's Settings >
Background workspace tap **Grant Ubiq access to Shizuku**. Shizuku has to be started again after
each reboot.

## Updating

Download the new APK and install it over the old one. Your chats and settings are kept.

## Checking the download (optional)

Each release lists the APK's SHA-256 in `SHA256SUMS`. Every release is signed with the same key;
`apksigner verify --print-certs ubiq-<version>.apk` should show this certificate SHA-256:

```
144f11365bed6028523afd9eec9b9cd133bda93193c08e49594990b0bf4fb635
```

## Uninstalling

Settings > Apps > Ubiq > Uninstall. If you made Ubiq the assistant, pick another one first under
Settings > Apps > Default apps > Digital assistant app.

## Trouble

- **Agent control switched itself off.** Android turns an accessibility service off when its app is
  force-stopped. Turn **Ubiq Agent Control** on again.
- Anything else: [open an issue](https://github.com/Bennyoooo/ubiq/issues).
