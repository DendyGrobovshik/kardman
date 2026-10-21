# Kardman demo

A runnable demo of Kardman: write Kotlin code in the app, press **Generate**, and the
demo dev server compiles it into a plugin, publishes it to the store, and the app loads
it over the network and renders it in the **Preview** tab — no app restart.

> ⚠️ **Example only.** Sending arbitrary client code to a server that compiles and
> executes it is a demo of the dynamic-loading pipeline. Do **not** do this in
> production: never accept and compile untrusted client code without sandboxing,
> signing and review.

## Requirements

Run `../scripts/setup.sh` once (Kotlin fork + Hermes + publishes the framework to
`mavenLocal`). You also need a booted Android emulator (`adb devices`) for Android, or
Xcode + a simulator for iOS.

## Run

Open `demo/` as the project root. The commands below assume you are inside `demo/`, so
the framework scripts are one level up under `../scripts/`.

**1. Dev server** (compiles submitted code + serves the store):

```bash
../scripts/demo-server.sh         # http://localhost:8080
```

**2. Android app:** open `demo/` in Android Studio and Run the `androidApp`
configuration on a booted emulator, or build from the command line:

```bash
../scripts/demo.sh                # builds the APK
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

**3. iOS app:** open `demo/iosApp/iosApp.xcodeproj` in Xcode, pick a simulator and
Run.

## Usage

- **Edit** tab: pick an example, edit the Kotlin code, press **Generate**.
- The server compiles the code into a plugin bundle and the app re-syncs it.
- **Preview** tab shows the loaded plugin (interactive, no restart).
