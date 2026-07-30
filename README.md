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
- **Fog of war.** Paint with your finger or a stylus to reveal. Adjustable brush
  size and edge softness, paint fog back on, undo/redo, reveal all, hide all.
  Fog is saved per map and survives closing the app.
- **Freeze the TV.** Scout three rooms ahead without dragging the players' view
  with you. A gold outline on your screen shows exactly what they can see.
- **Scale to life.** Tell the app how big a battle square is and how big the TV
  is, and it renders one square at one real inch so 28 mm miniatures fit their
  squares.
- **Rotate.** Turn the map and the players' view together in quarter steps, for
  a TV lying flat that people read from different sides. Remembered between
  sessions.
- **Curtain.** Hide the TV during a break without losing any state.

## Getting it running

Full walkthrough in **[docs/SETUP.md](docs/SETUP.md)** — it assumes you have
never built an Android app before.

The short version:

1. Install [Android Studio](https://developer.android.com/studio).
2. `File → Open` this folder and let it sync.
3. Turn on developer mode + USB debugging on the tablet, plug it in, press Run.

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
docs/                    setup, casting, architecture
```

## Status

Everything above is implemented. What has **not** happened yet is a real build:
this was written in an environment with no Android SDK available, so the first
`Gradle sync` and the first `Run` will happen on your machine. See
[docs/SETUP.md](docs/SETUP.md#if-gradle-sync-fails) for what to do if a
dependency version needs bumping — that is the most likely first hiccup, and
Android Studio fixes it with two clicks.

The player view *has* been tested end to end in a real browser against the real
protocol, including the chunked image transfer that Chromecast needs.
