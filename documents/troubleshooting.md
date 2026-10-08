# Troubleshooting & what each fix actually does

This fork was driven by problems on a 2025 Equinox EV (GM AAOS) with an iPhone and a Carlinkit
CPC200-CCPA. Read this when something misbehaves; it separates what the **app** can fix from what
only the **iPhone/car** can.

## 1. "Open with Carlink?" / Allow USB access pop-up every trip

**How Android decides.** When the adapter appears, Android launches the app that is registered for
it. If you ticked *Always/Use by default*, the launch is silent **and** the app is granted USB access
for that connection without any dialog. The default is stored **per package name**.

**What the app now does**
- Handles the attach intent (`onCreate` / `onNewIntent`). Previously it listened with a dynamic
  receiver, which Android never delivers this event to, so a re-enumerated adapter was ignored and a
  *second* permission dialog was triggered by the app's own request.
- `launchMode=singleTask`: one Activity instance, attach intents always redelivered to it.
- Waits up to 5 minutes for an answer to a permission dialog instead of 30 s (answering late used to
  be discarded and re-prompted), and waits 60 s before asking again after a refusal.

**What an app cannot do.** A normal app cannot grant itself USB access with no user action at all;
that needs a system/privileged permission. The "Always" tick is the only mechanism, so:
- Tick **Always** once and don't change the package name afterwards. **Changing `applicationId`
  (or uninstalling/reinstalling) resets it** and you will be asked once more.
- Install updates over the top (Play Internal Testing does this); don't uninstall first.
- If GM's build clears these defaults on boot, the prompt will return no matter what the app does.
  Logs will show `[USB_ATTACH]` followed by `Requesting USB permission`.

## 2. Spotify silent / playing through "My Chevrolet" or "Equinox EV" Bluetooth

This one is decided **on the iPhone**, not in the app. If the phone is also paired to the car's own
Bluetooth, iOS may route Spotify to that Bluetooth device instead of the CarPlay session, and the
dongle never receives any audio.

**Fix (phone side):** iPhone → Settings → Bluetooth → ⓘ next to the car ("My Chevrolet" /
"Equinox EV") → **Forget This Device**, and stop re-pairing it. Keep only the Carlinkit adapter's
pairing. (You can also disable Bluetooth on the car side for the phone profile.)

**What the app does to help**
- Re-sends `MIC` and `AUDIO_TRANSFER_OFF` (adapter audio over USB, not Bluetooth) on **every**
  connection. Before, they were only sent on the very first run; if the adapter ever drifted, nothing
  corrected it.
- Holds Android audio focus per stream (media / navigation / Siri / call), so the car's own sources
  are told CarPlay is playing. Toggle: **Settings → Audio Focus**. Turn it off if audio cuts out when
  you switch sources (GM uses an external focus policy; I could not test it on a vehicle).
  Automatically off when "Audio source" is set to Bluetooth.

## 3. Microphone: Siri works, voice messages / Snapchat / calls are silent

Two real causes were found in the code:
1. The adapter signals "open the microphone" with `START_RECORD_AUDIO` (command 1) /
   `STOP_RECORD_AUDIO` (command 2). The app **ignored both** and only started the mic for the
   explicit Siri and phone-call audio commands. Any other app got silence. Now handled.
2. Since Android 11, `AudioRecord` returns pure silence for an app that is not visible unless it runs
   a foreground service of type `microphone`. The service only declared `mediaPlayback` and
   `connectedDevice`. It now requests the microphone type (when the app is visible; it falls back
   safely otherwise and upgrades when you return to the app).

Also: the capture source falls back (`VOICE_COMMUNICATION` → `MIC` → `VOICE_RECOGNITION` →
`CAMCORDER`) if one delivers all zeros on this head unit, and the log says why if everything is
silent (look for `[MIC]` lines, especially `SILENCING capture`).

**iOS Control Center showing "CarPlay replaced iPhone Microphone / Same as System" is normal.** It
means iOS is taking microphone input *from the head unit through the adapter* — which is exactly the
audio this app must supply. Don't try to override it on the phone; if it records silence, the
problem is the app's mic stream, not the iPhone setting.

Requirements: *Settings → Adapter Configuration → Microphone Source* must be **App** (head-unit mic).
"Phone" tells the adapter to use its own mic, which the dongle typically does not have.

## 4. Stuck on "Connecting…" after Apply & Restart / drop / car wake

Root causes fixed:
- The connection ran inside a UI coroutine. Compose cancels it when the screen re-lays-out (surface
  size changes during startup), which abandoned a half-open connection with state `CONNECTING` and
  nobody left to retry. It now lives in its own scope (`connection/ConnectionSupervisor`).
- Concurrent start/stop calls (attach event + UI + auto-reconnect + "unplugged") raced. Now single-flight.
- The adapter init sequence (`Thread.sleep(120)` × ~15 messages) ran on the **main thread**. Now IO.
- Auto-reconnect gave up after 5 tries and only for some errors. Now retries forever (2 s → 30 s cap).
- Watchdog: adapter completely silent 25 s after start, or no data for 20 s while streaming →
  automatic restart. A restart never needs the cable pulled or the app force-stopped.

Useful log tags: `[SUPERVISOR]`, `[WATCHDOG]`, `[USB_ATTACH]`, `[MIC]`, `[FOCUS]`.

## Not verified on a vehicle

Everything above is covered by unit tests where logic can be isolated (43 tests) and builds cleanly,
but **none of it has been run on a real Equinox EV**. The riskiest items to watch on first drive are
audio focus (use the Settings toggle) and the microphone foreground-service type.
