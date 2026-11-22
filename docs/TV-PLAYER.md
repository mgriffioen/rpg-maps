# The TV player app (Fire TV and Android TV)

A small second app, in `tv/`, that turns a Fire TV stick or Android TV box into
the players' screen. It is the local-network route from
[CASTING.md](CASTING.md) without the browser:

- **Completely full screen.** No address bar, no tabs, no system bars.
- **No screensaver** while it is open, so the map never fades out mid-fight.
- **Finds the tablet by itself.** Open RPG Maps on the tablet and the TV picks
  it up. Nobody types an IP address with a remote, and it follows the tablet
  if the router gives it a new one.
- **Recovers on its own.** If the tablet drops off the Wi-Fi, the TV shows a
  small "reconnecting" line and comes back by itself.
- No accounts, no Google Cast registration and no internet needed to play.

It needs a **tablet build that includes this app's change**, which is any
build from the same commit or later: the tablet announces itself on the Wi-Fi
so the TV can find it. An older tablet build still works if you type its
address on the TV.

## Which devices

| Device | Works? |
|---|---|
| Fire TV Stick, Fire TV Stick Lite, Fire TV Stick 4K / 4K Max, Fire TV Cube, Fire TV Edition TVs | Yes |
| **Fire TV Stick 4K Select (2025) and other Vega OS devices** | **No.** Vega OS is not Android and cannot install outside apps. |
| Android TV / Google TV boxes and TVs, Chromecast with Google TV, Google TV Streamer | Yes |
| Older Chromecast dongles | No. Use the Cast route in [CASTING.md](CASTING.md). |

To check a Fire TV: **Settings → My Fire TV → About**. Anything that has a
*Developer options* entry under My Fire TV can install it.

The oldest supported is Fire OS 5 (Fire TV Stick 2nd generation, Android 5.1).
On very old sticks the built-in web engine is slow; if maps are sluggish,
lower *Map quality* in the tablet's Display settings.

## Installing on a Fire TV

You do this once. Updates install over the top the same way.

### 1. Allow installing from outside the Amazon store

1. **Settings → My Fire TV → About**, highlight the device name and press the
   select button **seven times**. A message says you are now a developer.
   (Older Fire OS versions show *Developer options* already; skip this.)
2. **Settings → My Fire TV → Developer options → Install unknown apps**
   (on older versions: *Apps from Unknown Sources*). You turn on the app that
   will download the file in the next step.

### 2. Download and install the APK

1. From the Amazon app store, install **Downloader** (by AFTVnews), the
   standard free tool for this.
2. Back in Developer options → Install unknown apps, turn **Downloader** on.
3. Open Downloader and type the release address:

   ```
   https://github.com/mgriffioen/rpg-maps/releases/download/latest-debug/rpg-maps-tv-player-debug.apk
   ```

   This file exists once the **Build APK** workflow has been run by hand
   from the Actions tab, the same as for the tablet app (see
   [INSTALL.md](INSTALL.md)). The address never changes, so Downloader
   remembers it for updates.

4. Downloader offers **Install** when the download finishes. Afterwards,
   choose **Delete** to free up the space the file took.

**RPG Maps Player** is now under *Your Apps & Channels*. Long-press the select
button on it and pick *Move* to put it on the home row.

> **With a computer instead:** turn on *ADB debugging* in Developer options,
> then `adb connect <fire-tv-ip>` and
> `adb install -r rpg-maps-tv-player-debug.apk`. The Fire TV's IP is under
> *Settings → My Fire TV → About → Network*.

### On an Android TV / Google TV

Same idea: **Settings → System → About**, press *Android TV OS build* seven
times for developer mode, then allow a downloader app (Downloader is in the
Google Play Store too) to install unknown apps.

## Using it

1. On the tablet, open RPG Maps.
2. On the TV, open **RPG Maps Player**. Within a few seconds it shows
   *Waiting for the map*, and then whatever map the tablet has open.

**The remote:**

| Button | What it does |
|---|---|
| **Menu** (☰) or **Back** | Opens the panel. Press again to return to the map. |
| On the panel: **Connect** | Connects to a typed address, for when the tablet is not found automatically. |
| On the panel: **Search again** | Starts a fresh search for the tablet. |
| On the panel: **Exit** | Closes the app. Back also exits when no map is showing. |

Back deliberately does **not** close the app while a map is showing, so a
stray press does not end your session.

## If it doesn't find the tablet

**The panel stays on "Looking for the tablet…"**

- Make sure RPG Maps is open on the tablet and the player-view address shows
  in *Display settings*.
- The TV and tablet must be on the **same Wi-Fi network**. Guest networks
  usually keep devices apart on purpose, and some mesh systems block the
  multicast that automatic discovery uses.
- If discovery never works on your network, type the address from the
  tablet's Display settings into the panel and press **Connect**. The TV
  remembers it and tries it first next time. A DHCP reservation for the
  tablet in your router keeps the address from changing.

**"Nothing answered at …"** — the address is wrong, the tablet app is closed,
or the two are on different networks. On the TV's own browser, the tablet's
`http://<address>/health` should show `OK`.

**The screensaver still appears.** The app keeps the screen on while it is in
the foreground, which Fire TV honours. If yours still dims, set *Settings →
Display & Sounds → Screensaver → Start Time* to *Never*.

**The TV went black or reloaded by itself.** On low-memory sticks Android
sometimes stops the web engine to reclaim memory. The app restarts it and
reloads the map, and the tablet resends the full fog state on reconnect, so
nothing is lost.

## How it works

The tablet's local-network server (`LanServer`) already serves the player
page and a `/health` check. The tablet now also advertises that server over
mDNS as `_rpgmaps._tcp` (`LanAdvertiser`). The TV app:

1. searches for `_rpgmaps._tcp` (`TabletFinder`), and also tries the last
   address it connected to;
2. checks `/health` before loading, so it never shows a browser error page;
3. loads `http://<tablet>/` in a full-screen WebView. That is the same
   `receiver/index.html` every other route uses, so fog, drawings, rotation
   and scale-to-life behave identically;
4. checks `/health` every ten seconds and reloads, or switches to the
   tablet's new address, if the tablet went away and came back.

The page's own WebSocket reconnect (see CASTING.md) still does most of the
work; the app covers the cases a web page cannot, such as the tablet moving
to a new IP address.
