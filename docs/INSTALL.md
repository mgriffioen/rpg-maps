# Installing on the tablet without Android Studio

[docs/SETUP.md](SETUP.md) assumes you build from Android Studio. You don't have
to. Three routes, easiest first.

| Route | What you install | Good for |
|---|---|---|
| **[GitHub Actions](#route-1-let-github-build-it)** | Nothing at all | Getting it on the tablet today, and every update after |
| **[Command line](#route-2-build-it-yourself-from-a-terminal)** | JDK + Android command-line tools (~700 MB) | Building offline, faster turnaround |
| **[ADB](#route-3-installing-with-adb)** | Android platform-tools (~15 MB) | Pushing a build you already have, over USB or Wi-Fi |

Routes 1 and 2 *produce* an APK. Route 3 is one way to *install* one — but you
don't need it, because the tablet can install an APK by itself.

---

## First, a one-time setting on the tablet

Android will not install an app from outside the Play Store until you say it
may. You are granting this to the app that *does* the installing — usually
Chrome or Files — not to the APK.

**Settings → Apps → Special app access → Install unknown apps**, pick the
browser or file manager you'll download with, and turn on *Allow from this
source*.

If you can't find it, download the APK first and tap it — Android will refuse
and offer a *Settings* button that jumps straight to the right screen.

---

## Route 1: let GitHub build it

The repository has a **Build APK** workflow. It builds on every push, so a
finished APK already exists for the current code.

### Getting the file

1. On the tablet, open the repository on github.com and sign in.
2. **Actions** tab → **Build APK** → the most recent green run.
3. Scroll to **Artifacts** and download `rpg-maps-debug-apk`.
4. That download is a **zip**. Open it in Files and extract `app-debug.apk`.
5. Tap the APK, tap **Install**.

### Avoiding the zip

Artifacts are always zipped, which is a nuisance on a tablet. To get a single
tappable file instead, publish a release:

**Actions → Build APK → Run workflow → Run workflow.**

A manual run does everything an automatic one does, and then attaches the APK
to a prerelease tagged `latest-debug`. On the tablet, go to the repository's
**Releases**, open **Latest debug build**, and tap `app-debug.apk` — no zip, no
extracting. The URL is stable, so bookmark it; re-running the workflow replaces
the file at the same address.

> The workflow runs the unit tests before assembling, so if something is
> broken you get a file and line number in the log rather than a crash on the
> tablet.

## Route 2: build it yourself from a terminal

No IDE, but you do need a JDK and the Android SDK.

**1. JDK 17 or newer.** [Temurin](https://adoptium.net/) has installers for
every platform. Check it took:

```bash
java -version
```

**2. Android command-line tools.** From the *"Command line tools only"* section
at the bottom of <https://developer.android.com/studio>. Unpack so the path
ends `cmdline-tools/latest/bin`, then:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"          # wherever you unpacked it
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

sdkmanager --licenses                             # accept all of them
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

**3. Point the project at the SDK.** In the repository root:

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

`local.properties` is machine-specific and deliberately not in git.

**4. Build.**

```bash
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

The first run downloads Gradle and the dependencies and takes a few minutes.
The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Get it onto the tablet however you like — USB, Google Drive, email it to
yourself — then tap it. Or use route 3.

Useful variations:

```bash
./gradlew test                 # unit tests only, no SDK platform needed
./gradlew installDebug         # build and install to a connected device
./gradlew --stacktrace assembleDebug   # when a failure is unclear
```

## Route 3: installing with ADB

ADB pushes an APK over a cable or Wi-Fi. It comes in **platform-tools**, a
~15 MB download that stands alone — you do not need the rest of the SDK just to
install.

Download it from <https://developer.android.com/tools/releases/platform-tools>
and unzip anywhere.

**On the tablet**, enable developer mode: **Settings → About tablet** → tap
*Build number* seven times. Then **Settings → System → Developer options** →
turn on **USB debugging**.

**Over USB:**

```bash
adb devices          # first run pops a "Allow USB debugging?" prompt -- accept
adb install -r app-debug.apk
```

`-r` replaces an existing install and keeps your map library. To start clean,
`adb uninstall com.rpgmaps.tabletop.debug` first.

**Over Wi-Fi** (Android 11+, no cable at all) — on the tablet, **Developer
options → Wireless debugging → Pair device with pairing code**:

```bash
adb pair 192.168.1.50:37105      # the pairing port and code from the tablet
adb connect 192.168.1.50:5555    # the other port shown on that screen
adb install -r app-debug.apk
```

---

## Which build am I installing?

Everything above builds the **debug** variant. Two things follow from that:

- Its application ID is `com.rpgmaps.tabletop.debug`, so it installs alongside
  a release build rather than replacing it.
- It is signed with Android's automatic debug key. Fine for your own tablet.
  You'd only need a real signing key to put it on the Play Store, which this
  app has no reason to be on.

`assembleRelease` builds the smaller, optimised variant, but it produces an
*unsigned* APK that Android will refuse to install until you create a keystore
and sign it. There is no reason to bother for a tablet you own.

## Updating later

Repeat whichever route you used and install over the top. Your map library,
fog and settings live in the app's own storage and survive a reinstall — as
long as you don't uninstall first, and as long as the application ID hasn't
changed.
