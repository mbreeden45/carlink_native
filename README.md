# Carlink Native

A native Android (Kotlin/Java) CarPlay / Android Auto projection client for **Android Automotive OS**
head units, talking to a **Carlinkit CPC200-CCPA** USB adapter. No Flutter/Dart.

Built and tuned for a **2025 Chevrolet Equinox EV** (GM AAOS) with an iPhone. The goal is
"set-and-forget": get in the car, CarPlay comes up, no prompts, no rituals.

> **Status:** unit-tested and builds cleanly, but the recent reliability/mic changes have **not yet
> been run on a vehicle**. See [`documents/troubleshooting.md`](documents/troubleshooting.md) for what
> changed, why, and what to watch on the first drive.

## What this fork changes

Compared with the upstream it was forked from:

| Problem | Cause found in the code | Fix |
|---|---|---|
| Stuck on **Connecting…**, needed cable pull + force stop | Connect ran in a UI coroutine that Compose cancelled mid-connect; racing start/stop callers; init sequence sleeping on the main thread; reconnect gave up after 5 tries | `ConnectionSupervisor`: own scope, single-flight, IO thread, retries forever (2 s→30 s), watchdog for a silent/stalled adapter |
| USB **"Allow" pop-up** every trip | Attach event handled by a receiver Android never delivers it to; 30 s permission timeout discarded late answers and re-prompted | Attach handled in `onCreate`/`onNewIntent`, `singleTask`, 5 min permission wait, 60 s back-off after refusal |
| **Mic silent** in Snapchat / voice messages / calls | Adapter's `START_RECORD_AUDIO`/`STOP_RECORD_AUDIO` commands were ignored (only Siri/call started the mic); no `microphone` foreground-service type so Android silences capture when app isn't visible | Commands handled; capture-thread-driven send; source fallback + silence probe; `microphone` FGS type with safe fallback |
| Audio plays via car **Bluetooth**, Spotify silent | No audio focus held; mic/audio-transfer settings were only sent on the very first run | `AudioFocusController` (toggle in Settings); both settings re-asserted every session. *Also requires forgetting the car's Bluetooth on the iPhone, see docs.* |
| Foreground service never actually started on Android 14+ | `connectedDevice` type missing its prerequisite permission → `SecurityException` swallowed | Permission added, graceful fallback chain |

Unchanged and inherited: USB protocol, H.264 `MediaCodec` video path (including the black-screen /
resume fixes from builds 52–61), multi-stream audio, multitouch, MediaSession.

## Requirements

- Android Studio (latest) or just JDK 21 + the Android SDK (platform 36, build-tools 36)
- minSdk 32 (Android 12L), compile/target SDK 36
- Carlinkit CPC200-CCPA adapter + AAOS head unit

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk          # or sdk.dir in local.properties
./gradlew testDebugUnitTest                        # 43 unit tests
./gradlew assembleDebug                            # debug APK
./gradlew bundleRelease                            # release AAB (signed if configured below)
```

### Your own package name

The default `applicationId` is `com.myequinox.myapp`. Override per build or permanently:

```bash
./gradlew assembleDebug -Pcarlink.applicationId=com.yourname.carlink
# or put  carlink.applicationId=com.yourname.carlink  in ~/.gradle/gradle.properties
```

> Android stores the USB "always open with" default **per package name**. Pick the name once;
> changing it means one more prompt on the car.

### Release signing

Create a gitignored `signing.properties` in the repo root (or set the same values as env vars for CI):

```properties
storeFile=/absolute/path/to/upload.jks      # env: CARLINK_STORE_FILE
storePassword=...                           # env: CARLINK_STORE_PASSWORD
keyAlias=...                                # env: CARLINK_KEY_ALIAS
keyPassword=...                             # env: CARLINK_KEY_PASSWORD
```

Without it, `assembleRelease` produces an unsigned APK.

### CI

- `.github/workflows/build.yml` – unit tests + debug APK on every push/PR.
- `.github/workflows/release.yml` – push a tag like `v1.2.0` to build a signed AAB. Needs secrets
  `CARLINK_KEYSTORE_BASE64`, `CARLINK_STORE_PASSWORD`, `CARLINK_KEY_ALIAS`, `CARLINK_KEY_PASSWORD`.

## Getting it onto a GM vehicle

A sideloaded APK will not run while the vehicle is moving. To keep it usable while driving, install
through Google Play **Internal Testing**:

1. Create a Google Play Console developer account (one-time fee).
2. Upload the AAB to an Internal Testing track and add your Google account as a tester.
3. Install/update from the Play Store on the head unit. **Update over the top; don't uninstall**, or
   the USB "Always" default is lost.
4. First plug-in: tick **Always** on the USB prompt and allow the microphone.

## iPhone setup that matters

- **Forget the car's own Bluetooth** on the iPhone (Settings → Bluetooth → ⓘ → Forget This Device).
  If the phone is also paired to "My Chevrolet"/"Equinox EV", iOS can send Spotify there instead of
  through CarPlay and the adapter hears nothing.
- *Control Center → Mic Mode* showing **"CarPlay replaced iPhone Microphone"** is expected; it means
  the head-unit mic is feeding the phone. Don't override it.
- In the app: **Settings → Adapter Configuration → Microphone Source = App**.

## Usage

- Plug in the adapter → the app connects on its own and reconnects after drops, restarts and car sleep.
- **Reset Video Decoder** – instant video recovery without reconnecting USB.
- **Reset USB Device** – full session restart (should rarely be needed now).
- **Audio Focus** toggle – turn off if audio cuts out when switching sources.

Useful log tags when reporting issues: `[SUPERVISOR]`, `[WATCHDOG]`, `[USB_ATTACH]`, `[MIC]`, `[FOCUS]`.

## Repository layout

```
app/src/main/kotlin/com/carlink/
  connection/   ConnectionSupervisor – connect/retry/watchdog policy (unit-tested)
  usb/          UsbDeviceWrapper – bulk transfer + permission
  protocol/     message parser/serializer, AdapterDriver (init + heartbeat)
  audio/        DualStreamAudioManager, MicrophoneCaptureManager, AudioFocusController
  media/        MediaSession + foreground connection service
  ui/           Compose screens and settings
  CarlinkManager.kt   orchestrator
app/src/main/java/com/carlink/video/   H264Renderer (MediaCodec)
documents/      troubleshooting.md, revisions.txt, GM head-unit reference notes
```

## Credits

- Native port, protocol reverse-engineering, video/audio/USB core: [@lvalen91](https://github.com/lvalen91)
  ([Carlink](https://github.com/lvalen91/Carlink), [Carlink_native](https://github.com/lvalen91/Carlink_native)).
- GM AAOS tuning and the builds this fork started from: [MotoInsight/Carlink_native](https://github.com/MotoInsight/Carlink_native).
- This fork: reliability, microphone and audio-routing work for the Equinox EV.

Released under the MIT license, as upstream.
