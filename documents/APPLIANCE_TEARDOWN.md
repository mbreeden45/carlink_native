# Single-user CarPlay appliance: teardown, plan, and the changes in this branch

**Target stack (the only stack this is tuned for):**

- Host: Android Automotive OS, GM `gminfo37` ultrawide (2400×960, 2.5:1, Intel HD 505, 60 Hz)
- Phone: one iPhone, wireless Apple CarPlay only
- Dongle: Carlinkit CPC200-CCPA via vehicle USB

This document is the engineering spec behind the code changes on branch
`claude/vibrant-hawking-v0zlh9`. It is deliberately opinionated: universal compatibility and
multi-device support are **non-goals**, and several "features" were removed because they only exist
to serve those goals.

---

## 1. Architectural & PM teardown

### The three baselines, and what is actually different

| Repo | What it is | State |
|---|---|---|
| `MotoInsight/Carlink_native` `main` | GM AAOS tuning branch | **Byte-identical** to `niravjdn/carlink_native` `main`, both pinned at Build 61 (`2532199`). Neither leads the other. |
| `niravjdn/carlink_native` | Community fork | `main` == MotoInsight. A separate `sync-upstream-v145` branch rebases the fork onto the real origin, `lvalen91/carlink` v145. |
| `lvalen91/carlink` (origin, v145) | Upstream native port | Much larger: adds instrument-cluster, GNSS, nav-icon rendering, IPC video forwarding, a decoder-size probe, dynamic mic-format negotiation — **and** firmware shell-injection code. |
| **this fork (`mbreeden45`)** | Your appliance | Diverged from Build 61: added `ConnectionSupervisor`, `AudioFocusController`, rewrote the mic path, deleted ~900 lines of dead USB code, added 43 unit tests + CI. |

**What the forks did right**

- *This fork* is the only one with a real connection state machine (`ConnectionSupervisor`):
  single-flight connect, its own coroutine scope (not a Compose scope), forever-retry with capped
  backoff, and a watchdog for a silent/stalled adapter. That design is sound and is kept.
- *This fork* already correctly handles the USB attach intent in `onCreate`/`onNewIntent` with
  `launchMode=singleTask`, which is the only mechanism by which an attach can be silent.
- *Upstream v145* has two genuinely good ideas worth harvesting: (a) **dynamic mic `decodeType`** —
  capture at the format the adapter actually asks for instead of a hardcoded 16 kHz; (b) **awareness
  that the decoder can refuse the panel's macroblock count**. Both are adopted here in clean form.

**What the forks broke / carry as bloat (removed or avoided here)**

- **Android Auto work mode forced on** (`"Enable Android Auto (AndroidWorkMode) on every connection"`,
  commit `362f84d`). For a CarPlay-only box this makes the firmware spend the first seconds of every
  session offering an AA / U-disk handshake — the "hangs in U-Disk mode" delay. Removed.
- **`android.hardware.usb.host` required=true** in this fork's manifest. GM AAOS does not advertise
  that feature in its Play device profile, so `required=true` filters the Equinox out of the Internal
  Testing track. Upstream already fixed this to `required=false`; this fork had regressed it.
- **Upstream's firmware shell-injection** (`serializeQualityRescueBoxSettings` and friends: abusing
  the adapter's unsanitized `wifiName` field to run `riddleBoxCfg`/scripts as root). It is fragile
  (breaks silently if firmware is patched), security-relevant, and unnecessary for this use case.
  **Not ported.** The one thing it was reaching for — keeping call quality at a mic-safe value — is
  achieved instead by simply clamping `callQuality` in the normal BoxSettings JSON.
- **Upstream's cluster / GNSS / nav-icon / IPC subsystems.** Hundreds of KB of code for an
  instrument-cluster projection path this vehicle (QNX-rendered cluster) cannot use. Not pulled in.

### Where the four frustrations actually come from

1. **USB "Allow" every trip** — a normal (non-privileged) app genuinely *cannot* self-grant USB
   access; the only no-dialog path is the `<usb-device>` filter + the user's one-time "Always" tick,
   which makes the attach *launch* grant access for that connection. The real bug was that the
   connect path could call `requestPermission()` and pop a dialog *before* that implicit grant became
   visible after enumeration.
2. **Slow / U-disk boot** — AndroidWorkMode on (AA negotiation) + slow 3 s device-poll cadence + no
   explicit auto-connect request.
3. **Dead mic** — on GM, `VOICE_COMMUNICATION` routes to `mic_for_vc` (telephony uplink), which is
   digital silence unless a native call is up. Siri / voice memos / Snapchat open a *generic* record,
   so grabbing `VOICE_COMMUNICATION` first gave them silence. Plus a fixed 16 kHz assumption and no
   echo cancellation.
4. **Ultrawide distortion** — the adapter negotiates to a discrete resolution *tier*; if that tier's
   aspect ratio differs from the 2.5:1 panel, `SurfaceView`'s default scaling stretches it to fill.

---

## 2. Single-user aggressive optimization plan

The guiding principle: **hardcode everything that a multi-device app has to negotiate.**

| Lever | Before | Now | Why it saves time / friction |
|---|---|---|---|
| Phone negotiation | AA + CarPlay offered | **CarPlay only** (`android_work_mode = false`) | Box commits to wireless CarPlay immediately; no AA/U-disk handshake. |
| Auto-connect | implicit | `AUTO_CONNECT_ENABLE` every session + `autoConn=true` in BoxSettings | Box reconnects the last-paired iPhone the instant it is in Wi-Fi/BT range. |
| Device discovery | poll every 3000 ms, ≤10 tries | poll every **250 ms**, 12 s cap | Adapter is grabbed within ¼ s of enumerating instead of up to 3 s. |
| USB permission | prompt if not already held | **2 s grace** waiting for the implicit "Always" grant, then prompt | Kills the spurious dialog on a normal cold boot. |
| Resolution | whatever the laid-out surface is | **pinned to native 2400×960 on gminfo37** | Adapter streams the true panel aspect ratio, no tier mismatch. |
| Pair nudge | 15 s | 8 s | A single known phone re-pairs fast. |
| Mic format | hardcoded 16 kHz | **tracks the adapter's `decodeType`** (clamped to 8/16 kHz) | Correct PCM size → far end actually hears audio. |

**On hardcoding the specific iPhone (UUID/MAC):** the CPC200 firmware owns the Bluetooth/Wi-Fi
pairing list; this protocol surface (`BLUETOOTH_PAIRED_LIST`, 0x12) is observed but not a
host-selectable allow-list. The realistic single-phone win is therefore `autoConn=true` +
`AUTO_CONNECT_ENABLE` (box auto-reconnects the remembered phone) rather than an app-side MAC filter.
Pair the one iPhone once; the box remembers it and this config reconnects it with no UI.

**Heartbeat-first is preserved.** `documents/reference/Firmware/firmware_initialization.md` shows the
box fails ~11.7 s into a cold start if the 2 s heartbeat is not already running during boot
stabilization. None of the fast-boot changes touch that ordering.

---

## 3. The code patches (what changed, file by file)

All changes are on `claude/vibrant-hawking-v0zlh9`. Unit tests added for the protocol behavior.

### `AndroidManifest.xml`
- `android.hardware.usb.host` → `required="false"` (stop Play from filtering the GM head unit off the
  track; the `<usb-device>` filter and `UsbManager` still work at runtime).
- `launchMode=singleTask`, the `<usb-device>` attach filter, and the `distractionOptimized` /
  `DISTRACTION_OPTIMIZED` declarations are retained — they are the correct appliance config.
- Microphone + `connectedDevice` + `mediaPlayback` foreground-service types retained.

### `usb/UsbDeviceWrapper.kt`
- `openWithPermission()` now waits out a **2 s grace window** (`awaitImplicitGrant`) for the implicit
  "Always" grant from the attach-launch before it ever falls back to `requestPermission()`. This is
  the core "no dialog every trip" fix.

### `protocol/MessageSerializer.kt`
- `AUTO_CONNECT_ENABLE` added to the init preamble (every session, all modes).
- BoxSettings JSON: `autoConn=true`, `autoPlay=false`, `callQuality` clamped to ≤ 1 (value 2 / 24 kHz
  soft-bricks the adapter's hardcoded 640 B mic input buffer), `boxName` used consistently, AA
  resolution tiering removed (fixed placeholder, never exercised).
- Full init now writes `android_work_mode = false` (CarPlay-only).

### `CarlinkManager.kt`
- Fast device discovery (250 ms poll, 12 s cap); `PAIR_TIMEOUT_MS` 15 s → 8 s.
- Mic: tracks the adapter's requested `decodeType` from every audio frame / `AUDIO_INPUT_CONFIG` and
  starts capture at that format (clamped to 8/16 kHz); live format switch re-opens capture.
- Resolution: `openResolutionFor()` pins OPEN to native **2400×960** on `gminfo37`.
- New `Callback.onVideoSizeChanged()`; forwards the decoder's true size to the UI for aspect-lock.

### `audio/MicrophoneCaptureManager.kt`
- Source preference reordered to **`VOICE_RECOGNITION` first** (GM Built-In Mic; works for Siri /
  voice memos / apps), then `MIC`, then `VOICE_COMMUNICATION` (real calls), then `CAMCORDER`. The
  runtime silence-probe still skips any source that delivers zeros.
- Attaches **AcousticEchoCanceler + NoiseSuppressor + AutomaticGainControl** (best-effort) to the
  capture session so the far end stops hearing cabin echo.

### `video/H264Renderer.java` + `ui/components/VideoSurface.kt` + `ui/MainScreen.kt`
- Renderer reports the **crop-corrected** decoded size via `VideoSizeListener`; sets
  `setVideoScalingMode(SCALE_TO_FIT)` explicitly.
- `MainScreen` centers the surface in the black panel and **aspect-locks it to the decoded size**
  (`fillMaxHeight/Width().aspectRatio(...)`), so a non-matching stream is letterboxed, never stretched.

---

## 4. First-drive watch list

Unchanged from the prior note: none of this has run on a vehicle yet. On the first drive, watch the
`[MIC]` logs (source chosen + any `SILENCING capture`), `[FOCUS]`, `[USB_ATTACH]` (should show the
implicit grant, not a dialog), and `[RES]` (should read `2400x960`, `gmNative=true`). The
**Audio Focus** toggle and the **Microphone Source = App** setting remain the two levers if audio or
mic misbehave.

---

## 5. Review follow-ups (polish pass on top of this branch)

Corrections to claims above, and the fixes that landed with them:

- **`android_work_mode` is not the U-disk switch.** `firmware_configurables.md` lists `AndroidWorkMode`
  and `UdiskMode` as separate parameters; the web-config shortcut toggles the U-disk one, which no
  host USB message here touches. Writing `android_work_mode=false` turns off Android Auto
  negotiation. Whether that alone cures the cold-boot stall is **unverified**. Keep the shortcut until
  a cold boot without it shows `[USB] Interface 0: class=0xff ...` followed by protocol traffic.
- **No USB command can reach a box that hasn't exposed its protocol interface.** If it boots presenting
  only a storage personality, `findDevice()` never matches (or now logs and skips the class-0x08
  interface instead of claiming it), so nothing in the init sequence is ever sent.
- **Boot settings never reached already-initialised installs.** BoxSettings (`autoConn`, `autoPlay`,
  `callQuality` clamp, `boxName`) and `android_work_mode` were FULL-only, and an upgraded install runs
  MINIMAL_ONLY forever. Fixed: `CURRENT_INIT_REVISION` forces one FULL init after upgrade, and
  `android_work_mode=false` is now re-sent every session.
- **USB permission.** The dialog wait now also completes when the grant arrives out-of-band (the
  attach launch) or when the device instance disappears, instead of holding the supervisor mutex for
  up to 5 min. Attach intents are debounced, and an attempt bound to a stale device path is preempted.
- **Mic.** Only voice-format frames (3/5/6/7) steer the uplink `decodeType`; it resets per session. An
  effect handle no longer leaks when `setEnabled()` throws. AGC was dropped (VOICE_RECOGNITION path).
- **Video.** `resume()` is idempotent (`paused` flag), so a surface rebind can't call `start()` on an
  Executing codec, and is never throttled away after a `pause()`. The decoded size is cached and
  replayed to the current callback on the main thread. Resizes are no longer re-announced as new
  surfaces, and OPEN uses the panel size rather than the letterboxed surface size.
