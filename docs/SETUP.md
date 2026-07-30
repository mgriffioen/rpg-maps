# Setup

Written for someone who has never built an Android app. If a step already looks
familiar, skip it.

---

## 1. Install Android Studio

Download it from <https://developer.android.com/studio> and install with the
defaults. On first launch it offers to download the Android SDK — accept.

You do not need to install Java, Gradle or the Kotlin compiler separately.
Android Studio brings its own, and this project uses the Gradle wrapper
(`gradlew`), which fetches the exact Gradle version it needs.

## 2. Open the project

`File → Open`, pick the `rpg-maps` folder (the one with `settings.gradle.kts` in
it), and press OK. **Do not** use `File → New → Import Project`.

Android Studio will start a "Gradle sync". The first one downloads a few hundred
megabytes and can take several minutes. Watch the status bar at the bottom.

### If Gradle sync fails

The most likely failure is a dependency version that does not exist. All
versions live in one file: **`gradle/libs.versions.toml`**.

Android Studio underlines out-of-date or missing versions in that file. Put the
cursor on the underlined version, press `Alt+Enter` (`Option+Enter` on a Mac)
and choose the newest version it offers. Sync again.

If it complains about the **Android Gradle Plugin** specifically, use
`Tools → AGP Upgrade Assistant` instead — it updates the plugin and the Gradle
wrapper together, which is what that particular pair needs.

Two other common ones:

- *"SDK location not found"* — `File → Settings → Languages & Frameworks →
  Android SDK`, make sure a platform is installed, and let Studio write
  `local.properties`. That file is deliberately not in git; it is specific to
  your machine.
- *"Installed Build Tools revision ... is corrupted"* — open the SDK Manager and
  reinstall the build tools.

## 3. Get the tablet ready

On the tablet:

1. `Settings → About tablet`, tap **Build number** seven times. It will tell you
   you're now a developer.
2. `Settings → System → Developer options`, turn on **USB debugging**.
3. Plug the tablet into the computer. A dialog appears on the tablet asking
   whether to allow USB debugging from this computer — tick "always allow" and
   accept.

The tablet should now appear in the device dropdown at the top of Android
Studio.

### Or go wireless

Android 11+ supports wireless debugging, which is far more pleasant for a tablet
you're holding. In Developer options, turn on **Wireless debugging**, tap
**Pair device with pairing code**, then in Android Studio use the device
dropdown → **Pair Devices Using Wi-Fi**.

## 4. Run it

Press the green ▶ button. The first build takes a few minutes; later ones are
much faster.

The app installs as **RPG Maps**. The debug build uses the application ID
`com.rpgmaps.tabletop.debug`, so it can sit alongside a release build without
conflict.

## 5. Add a map and check it works

1. Tap **Add maps** and pick a battle map image.
2. Open it. The whole map is fogged. Switch to **Reveal** and drag a finger
   across it — a soft-edged hole opens up.
3. Open **Display settings** from the library screen. Under *Player view on
   this network* there is an address like `http://192.168.1.34:8770`.
4. Type that address into a browser on any device on the same Wi-Fi — your
   laptop, your phone. You should see the same map with the fog fully opaque,
   updating live as you paint.

If step 4 works, the app is fine and anything that goes wrong later is in the
casting setup. That is why it is worth doing before anything else.

## Building an APK to install directly

If you would rather side-load than run from Studio:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Copy it to the tablet and open
it; you will have to allow installing from unknown sources once.

For a build you keep long term, `./gradlew assembleRelease` produces a smaller,
optimised APK — but it needs a signing key, which Android Studio can generate
for you via `Build → Generate Signed App Bundle / APK`.

## Running the tests

```bash
./gradlew test
```

These cover the wire protocol and the image/fog sizing maths. They run on the
JVM, so no tablet or emulator is needed.

## Troubleshooting

**The app installs but the map list is empty after a reinstall.** Map images
live in the app's private storage, which Android wipes on uninstall. Reinstall
over the top (which Studio does by default) and they survive.

**"Casting unavailable on this device"** in the status line. The tablet does not
have Google Play services, or it is out of date. The local-network route still
works.

**The tablet screen keeps dimming during a session.** It shouldn't — the map
screen holds the screen awake. If it still dims, check that battery saver is
off.

**A huge map is slow or the app runs out of memory.** Lower *Map quality* in
Display settings and re-import that map. Maps are downscaled on import, and
2048 px is already plenty for a 1080p TV.
