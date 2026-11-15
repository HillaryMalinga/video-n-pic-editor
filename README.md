# HD Status

Android app that converts a photo or video into a WhatsApp-status-friendly file
(H.264/AAC MP4 or resized JPEG) so WhatsApp doesn't re-compress it into mush.

## Build (no Android Studio needed)

Requires JDK 17 (`brew install openjdk@17`) and the Android SDK at `~/Library/Android/sdk`.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install on a USB-connected phone (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Features

- Pick from the app, or share/open a photo or video from the gallery into HD Status
- Photos: preview, crop (Original, 9:16, 4:5, 1:1, 16:9, Free), resize, sharpen, JPEG quality
- Videos: preview player, trim with start/end handles and a draggable playhead,
  resolution up to 2160p, bitrate up to 30 Mbps, sharpening, fps cap, auto-split
- Share straight into WhatsApp, or save to the gallery

## Release build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
```

Signed APK: `app/build/outputs/apk/release/app-release.apk`. Signing uses `keystore.properties`
and `keystore/` (both git-ignored). Keep a backup of them: every future update must be signed
with the same key or phones will refuse to install it over the old version.

## Code map

- `VideoConverter.kt` – Media3 Transformer: trim, resize, bitrate, sharpen, fps cap, HDR→SDR, split into parts
- `ImageConverter.kt` – EXIF-rotate, crop, resize, sharpen, JPEG encode
- `Sharpen.kt` – unsharp-mask filter (GPU shader for video, CPU for photos)
- `ImageCropper.kt` / `VideoTrimmer.kt` – crop and trim editors
- `Sharing.kt` – share into WhatsApp (falls back to the share sheet), save to gallery
- `Settings.kt` – default output targets (tune these after real WhatsApp tests)
- `MainActivity.kt` / `MainViewModel.kt` – UI
