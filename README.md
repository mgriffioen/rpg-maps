# RPG Maps

An Android tablet app for running D&D battle maps on a TV lying flat on the
table. You keep the library, the zoom and the fog of war on the tablet; the
players see only the map.

**Built for:** an Android 16 tablet as the DM's control screen, and a TV in the
middle of the table as the battle map.

---

## What it does

- **Map library.** Import battle map images from anywhere on the tablet, Drive
  or Downloads. They are copied onto the device, so a session needs no internet.
- **Pan and zoom.** One finger to drag, two to pinch. Works the same whether
  you're framing a whole dungeon level or one corner of a room.
- **Fog of war.** Paint with your finger or a stylus to reveal, or drag out a
  rectangle or oval for a room in one go. Adjustable brush size and edge
  softness, paint fog back on, undo/redo, reveal all, hide all. Fog is saved
  per map and survives closing the app.
- **Freeze the TV.** Scout three rooms ahead without dragging the players' view
  with you. A gold outline on your screen shows exactly what they can see.
- **Scale to life.** Tell the app how big a battle square is and how big the TV
  is, and it renders one square at one real inch so 28 mm miniatures fit their
  squares.
- **Rotate, two ways.** *TV* turns the players' view in quarter steps without
  moving your own, for a TV lying flat that people read from a different side
  than you do -- or one stood on its end. *Map* turns the artwork itself, on
  both screens at once, so your tablet stays a preview of what the table sees.
  Both are remembered between sessions.
- **Ping.** Someone asks "where's the door?" — tap the map and a pink marker
  pulses there on the players' screen for a couple of seconds, over the fog.
- **Curtain.** Hide the TV during a break without losing any state.
- **Keeps running when you switch apps.** Look up a rule, roll dice, answer a
  message — the players' screen stays live. A notification shows the address
  and stops it when the session ends.

## Getting it running

With Android Studio — full walkthrough in **[docs/SETUP.md](docs/SETUP.md)**,
written for someone who has never built an Android app:

1. Install [Android Studio](https://developer.android.com/studio).
2. `File → Open` this folder and let it sync.
3. Turn on developer mode + USB debugging on the tablet, plug it in, press Run.

Without it — see **[docs/INSTALL.md](docs/INSTALL.md)**. The easiest route
installs nothing on your machine at all: the **Build APK** workflow builds it
on GitHub, and you download the APK on the tablet and tap it. There are also
command-line and `adb` routes.

## Getting it onto the TV

There are three ways, and they are very different amounts of work. Full detail
in **[docs/CASTING.md](docs/CASTING.md)**.

| Route | Setup needed | Notes |
|---|---|---|
| **Local network** | None | Open the address the app shows in any browser on a device attached to the TV. Start here — it proves everything works before you touch Cast. |
| **Chromecast** | Google Cast developer registration ($5, one-off) + GitHub Pages | Needed for older Chromecast dongles, which cannot run apps of their own. |
| **HDMI cable** | None | Mirrors the tablet. Simple, but the players see your controls too. |

> **Start with the local-network route.** It needs nothing but Wi-Fi, and it
> tells you whether a problem is in the app or in the Cast setup.

## Layout

```
app/                     the Android app (Kotlin, Jetpack Compose)
  src/main/java/com/rpgmaps/tabletop/
    data/                library storage, image import, settings
    fog/                 the fog mask and the editing/undo model
    display/             getting the player view onto a screen
      protocol/          the tablet <-> receiver wire format
      lan/               embedded HTTP + WebSocket server
      cast/              Google Cast sender
    ui/                  Compose screens
  src/test/              JVM unit tests (./gradlew test)
receiver/index.html      the player view — one file, runs on Chromecast and in
                         any browser. Bundled into the APK at build time and
                         served from GitHub Pages for Cast.
docs/                    setup, installing, casting, architecture
.github/workflows/       builds an installable APK on every push
```

## Status

Everything above is implemented, and it **compiles**: the `Build APK` workflow
assembles a debug APK and runs the unit tests on every push. Most of this was
written in an environment with no Android SDK, so that is worth stating rather
than assuming.

What has still not happened is a session at a real table. The player view *has*
been tested end to end in a real browser against the real protocol, including
the chunked image transfer Chromecast needs, and soft fog edges are checked
both with and without `ctx.filter` so Safari is covered.

If a dependency version ever needs bumping, see
[docs/SETUP.md](docs/SETUP.md#if-gradle-sync-fails) — Android Studio fixes that
with two clicks.
