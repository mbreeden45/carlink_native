# Carlink on a GM EV: user guide

Wireless Apple CarPlay on a GM EV (built and tested on a 2025 Chevrolet Equinox EV) using a
**Carlinkit CPC200-CCPA** adapter and this app.

**What to expect:** start the car, open Carlink, tap **OK** once on the USB prompt, and CarPlay is
up in about 10 seconds. Short stops (car off briefly, screen never shut down) need no taps at all.
No iPhone Shortcut.

---

## One-time setup

1. **Install Carlink from Google Play** (Internal Testing track) on the car. Update over the top;
   don't uninstall.
2. **Plug the adapter into the car's USB port**, then pair your iPhone with it the way the adapter's
   own instructions describe.
3. **On the iPhone, forget the car's own Bluetooth** (Settings → Bluetooth → ⓘ next to the car →
   Forget This Device). Otherwise iOS can send audio to the car's Bluetooth instead of CarPlay.
4. **Let the app use the adapter's Wi-Fi.** On first start, Carlink asks Android to join the
   adapter's network ("carlink"). When **"Device to use with Carlink"** appears (it lists "carlink"), tap **Connect** once; after that it
   rejoins by itself. The car shows "no internet" on that network; that's expected, and the car keeps
   using cellular for everything else. (GM cars don't rejoin this network on their own at power-up,
   which is why the app does it.)
   - Why: some days the adapter powers up without showing up on USB. When that happens, Carlink
     sends it a "switch to CarPlay mode" request over this Wi-Fi, the same request the old iPhone
     Shortcut sent.
5. **In Carlink: Settings → Adapter Configuration → Microphone Source = App**, and allow the
   microphone when asked.

### Only if you build the app yourself

Add two GitHub repository secrets (never written into the source code; each missing one only
switches its feature off and shows a build warning):

| Secret | Value | Enables |
|---|---|---|
| `CARLINK_BOX_SECRET` | The adapter's web-config signing secret (the long text your iPhone Shortcut uses as `SECRET`) | Sending the wake request |
| `CARLINK_BOX_WIFI_PSK` | The adapter's Wi-Fi password | The app joining the adapter's Wi-Fi itself |

---

## Every drive

1. Start the car and open **Carlink**.
2. When **"Allow Carlink to access Auto Box?"** appears, tap **OK**. You don't need to tick
   "Always open"; it has no effect on this car (see below).
3. CarPlay comes up.

The grey line under **[ Connecting... ]** tells you what's happening:

| Message | Meaning |
|---|---|
| Looking for the adapter on USB | Normal for the first second or two. |
| Adapter not detected on USB - retrying | The adapter hasn't shown up on USB yet. |
| Adapter not on USB - joining the adapter's Wi-Fi ("carlink") to wake it | The app is joining the adapter's Wi-Fi. The first time, tap **Connect** on "Device to use with Carlink". |
| Adapter not on USB - connect the car to the adapter's Wi-Fi ("carlink") to wake it | The adapter needs waking but the car isn't on its Wi-Fi. Join "carlink" in the car's Wi-Fi settings; the request goes out the moment it connects. |
| Adapter not on USB - asking it over Wi-Fi to switch to CarPlay mode | The automatic replacement for the Shortcut is running. |
| Adapter accepted the Wi-Fi request - waiting for it on USB | It worked; the adapter appears on USB about 10 seconds later. GM's "USB Device not supported" pop-up may show at the same time; ignore it. |
| Adapter found - tap OK on the USB prompt now | Tap OK within about 9 seconds. |
| Adapter re-connected on USB while asking for access - retrying | The adapter restarted before you tapped; a new prompt follows within a second. |
| Starting adapter | Permission granted; connecting. |

---

## If it doesn't connect

Before tapping anything else: **Carlink → Settings → Logs → USB diagnostics → Show**, and photograph
the screen (scroll up for older lines). It works at any log level. Exported log files can only be
saved to the car itself, so the photo is the way to get them off.

| Lines | Meaning |
|---|---|
| `[BOX_KICK] … accepted` | The Wi-Fi request worked; the adapter should appear on USB a few seconds later. |
| `[BOX_KICK] … not on any Wi-Fi network` | The car isn't joined to the adapter's Wi-Fi (setup step 4). |
| `[BOX_KICK] … rejected` | The adapter refused the request: the signing secret is wrong. |
| `[BOX_KICK] Not configured` | The build has no `CARLINK_BOX_SECRET`. |
| `[BOX_WIFI] Joined "carlink"` | The app joined the adapter's Wi-Fi by itself. |
| `[BOX_WIFI] … not found or not approved` | The adapter's Wi-Fi wasn't up yet, or the join prompt was dismissed; retried every 30 s. |
| `[USB_EVENT] ATTACHED / DETACHED` every ~9 s | Normal until the app opens the adapter (see below). |

---

## How this differs from the usual method

The common approach (as used with this car before this fork) relied on an iPhone Shortcut and ticking
"Always open" on every start. What was actually going on, and what this app does instead:

| Usual method | What was really happening | This app |
|---|---|---|
| Run an iPhone Shortcut over the adapter's Wi-Fi on every start (worked about 1 in 3 times) | Some power-ups, the adapter doesn't present its CarPlay USB interface until it gets that web-config request | Carlink sends the same request itself, from the car, only when the adapter is missing from USB. No phone involved. |
| Tick "Always open Carlink when Auto Box is connected" every time; it never sticks | GM routes every USB plug-in to its own fixed system handler (the one that says "USB device not supported"), so Android's saved "Always open" choice is never consulted | Skip the checkbox; just tap OK. One tap per real power-up is the floor for any Play Store app on this car. |
| The permission prompt flashes and disappears | Until an app opens it, the adapter restarts itself about every 9 seconds; each restart closes the prompt | Carlink raises the prompt within about 0.3 s of the adapter appearing, giving you almost the whole window. Once opened, the adapter stays up for the drive. |
| "Stuck on Connecting" for a minute after a missed prompt | A prompt closed by the system was treated as "you said no", with a 60 s wait | Treated as a system dismissal; retries right away. |
| Logs can't be sent anywhere | Exports only save to the car's storage | On-screen USB diagnostics to photograph. |

> The original Reddit write-up couldn't be retrieved automatically, so this comparison is written
> from the steps actually used on this car before the fork. Wording may differ from that post.

---

## Limits

- **No CarPlay without the adapter.** GM's EVs don't ship CarPlay, and an app can't add it: CarPlay
  needs Apple's authentication hardware, which the adapter provides.
- **The one OK tap per power-up stays.** Removing it would need system-level access to the car's
  software.
- The steering-wheel voice button goes to GM's assistant, not Siri; Android Automotive doesn't let a
  Play Store app take that button. Use Siri from the CarPlay screen.
