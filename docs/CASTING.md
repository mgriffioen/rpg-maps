# Getting the map onto the TV

Three routes. They differ enormously in how much setup they need, so read the
comparison before picking one.

| | Local network | Chromecast | HDMI cable |
|---|---|---|---|
| Setup | none | $5 registration + hosting | none |
| Players see your controls | no | no | **yes** |
| Needs internet | no | to set up, not to play | no |
| Works with an old Chromecast dongle | only via another device | yes | n/a |

---

## Route 1: Local network (start here)

The app runs a small web server. Anything on the same Wi-Fi that can open a URL
can be the player view.

1. Open **Display settings** from the library screen.
2. Under *Player view on this network*, note the address, e.g.
   `http://192.168.1.34:8770`.
3. Open that address on whatever is attached to the TV.

Devices that work well:

- **A laptop plugged into the TV over HDMI.** Open the URL, press F11 for full
  screen. Honestly the most reliable setup there is.
- **An Android TV / Google TV / Fire TV.** Install a browser and open the URL.
- **A spare tablet or old phone** propped under the TV. Works, but you have a
  TV, so use the TV.

There is nothing to configure, no account, and no internet involved. If this
works and Cast doesn't, the problem is in the Cast setup, not the app.

> The address changes if your router hands the tablet a different IP. A DHCP
> reservation in your router for the tablet makes it permanent.

---

## Route 2: Chromecast

An older Chromecast dongle (anything before Chromecast with Google TV) cannot
run apps. The only way to put a custom view on it is a **Cast custom receiver**:
a web page that Google's servers tell the Chromecast to load.

That means two things you cannot avoid:

- a **one-off $5 Google Cast developer registration**, and
- the receiver page has to be **hosted at a public HTTPS URL**.

The second one is free — GitHub Pages serves it straight out of this repository.

There is also a design consequence worth knowing, because it explains why the
map takes a few seconds to appear: a Chromecast receiver cannot reach back to
your tablet to download anything. Every byte of the map image has to be pushed
through Cast's message channel, which caps messages at 64 KB. The app
base64-encodes the map and sends it in slices, waiting for each to be
acknowledged. Fog strokes and viewport changes are tiny by comparison and stay
instant.

### Step 1: Publish the receiver page

1. Push this repository to GitHub (it already is, if you're reading this there).
2. On GitHub: **Settings → Pages**.
3. Under *Build and deployment*, set Source to **Deploy from a branch**, branch
   **main**, folder **/ (root)**. Save.
4. Wait a minute, then check that this loads:

   ```
   https://<your-github-username>.github.io/rpg-maps/receiver/
   ```

   You should get a dark page saying "RPG Maps — waiting for the map". That is
   the receiver idling, which is exactly right.

The page in `receiver/index.html` is the same file the app bundles into the APK.
The build copies it in, so the two can never drift apart.

### Step 2: Register the receiver application

1. Go to the [Google Cast SDK Developer Console](https://cast.google.com/publish).
2. Sign in and pay the one-off $5 registration fee.
3. **Add new application → Custom Receiver.**
4. Fill in:
   - *Name*: `RPG Maps`
   - *Receiver Application URL*: the GitHub Pages URL from step 1
   - Leave everything else at its default.
5. Save. You get an **Application ID** — eight hex characters, like `A1B2C3D4`.

You do **not** need to publish the application. An unpublished one works
indefinitely on devices you have registered, which is what step 3 is for.

### Step 3: Register your Chromecast

Unpublished receivers only load on registered devices.

1. In the same console, **Add new device**.
2. Enter the Chromecast's **serial number**. It is printed on the dongle itself,
   and also shown in the Google Home app under the device's settings → *Device
   information*.
3. Save.

Registration takes up to 15 minutes to propagate. Reboot the Chromecast
afterwards (unplug it for ten seconds) — it caches the old list otherwise. This
step is the single most common reason a custom receiver "doesn't work", so if
something fails later, come back and reboot it again.

### Step 4: Point the app at your receiver

Open `app/build.gradle.kts` and find:

```kotlin
resValue("string", "cast_app_id", "CC1AD845")
```

Replace `CC1AD845` with your Application ID, then rebuild and reinstall.

`CC1AD845` is Google's Default Media Receiver. It is there so the Cast button
does something before you've registered anything — it will connect, but it
cannot draw fog of war, because it isn't your page.

### Step 5: Cast

Tap the Cast icon in the app's top bar, pick your Chromecast. The status line
under the map title shows the connection and a progress bar while the map
transfers.

### If it doesn't work

**The Cast button shows no devices.** The tablet must be on the same Wi-Fi as
the Chromecast, and on a network that allows multicast — many guest networks
and some mesh systems block it. Check the Google Home app can see the device.

**It connects, then the TV goes back to the ambient screen.** Almost always an
unregistered device or one that hasn't been rebooted since you registered it.
Redo step 3.

**It connects and stays black.** The receiver URL is wrong or not reachable.
Open the GitHub Pages URL in a desktop browser and confirm you get the idle
screen.

**The map appears, then the TV drops out after a few minutes of no activity.**
The receiver disables Cast's idle timeout, so this should not happen. If it
does, check that the browser can load your Pages URL fresh (Pages may be serving
a cached older copy — a hard refresh tells you).

**A first-generation Chromecast is slow or reloads.** It has 512 MB of RAM.
Lower *Map quality* to 1600 px in Display settings and re-import your maps.

---

## Route 3: HDMI cable

A USB-C to HDMI adapter mirrors the tablet onto the TV. Nothing to set up, and
zero latency.

The catch is that it mirrors — the players see your fog brush, your toolbar and
your unrevealed map. That makes it useful for testing, and for games where you
don't mind, but not for a fog-of-war session.

If you want to go this way properly, the fix is Android's `Presentation` API,
which puts a genuinely different view on the second display. The display layer
in this app is built around a `DisplaySink` interface exactly so that a
`PresentationSink` could be added alongside the LAN and Cast ones without
touching anything else. It is not implemented yet.

---

## Which should you use?

Given an older Chromecast dongle: **do route 1 first** to confirm the app works
and to get a feel for the fog tools, then set up route 2 for actual play.

If you ever replace the dongle, a Google TV Streamer or any Android TV can just
open the local-network URL in a browser, and route 2 stops being necessary at
all.
