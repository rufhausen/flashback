# Flashback

A Hammerhead Karoo extension that does one thing: **flash your rear light only while the radar sees a vehicle**, then put it back the way it was. The point is battery: the light stays off until there's actually someone behind you.

Built for a Garmin Varia RTL 515/516 + Karoo 3. It should also work with any ANT+ light paired to the Karoo, and any radar the Karoo can read.

Derived from [KarooFireFly](https://github.com/derstrassi/karoofirefly) via [szaffarano's maintenance fork](https://github.com/szaffarano/karoofirefly). The ANT+ light plumbing comes from there. Everything else (sunrise/sunset, ambient sensor, BLE lights, zones) is gone.

## Behaviour

| Situation | What happens |
|---|---|
| No vehicle | Nothing. Karoo's own light control (or whatever you set) is in charge. |
| Radar reports a vehicle | Selected light(s) switch to the flash mode (default: Fast Flash). |
| Car closing fast | Switches to the fast-approach mode if you set one, and stays there until clear. |
| Road clear | After a short hold (default 3 s), the light goes back to its previous mode, or Off if you chose that. |
| Another car during the hold | Keeps flashing; no mode churn. |
| Something knocks the light out of flash mid-alert | Re-asserts the flash (at most every 2 s). |
| Radar disconnects mid-alert | Restores immediately, so the light isn't left strobing. |
| Ride ends | Restores, releases the light session. |

## Is it working?

Add the **Flashback Status** data field to a ride page:

| Field | Meaning |
|---|---|
| 🟩 **ARMED** · Light: Off | Radar clear, light confirmed off. |
| 🟧 **FLASHING** · Light: Fast Flash ✓ | Car detected, and the light confirmed it's flashing. |
| 🟧 **FLASHING** · Light: Off ✗ | Car detected but the light didn't follow. Something's wrong. |
| 🟥 **NOT ACTIVE** · reason | Flashback can't control the light. You also get a beep and an alert. |
| ⬛ **OFF / STANDBY** | Disabled, or no ride running. |

The "Light:" part comes from what the Varia reports back, not what Flashback sent, so ✓ is real confirmation.

It's active only while a ride is recording or paused. A **Toggle Radar Flash** bonus action is available for hardware or AXS buttons.

## Karoo setup for "dark until a car shows up"

1. Pair the Varia under **Settings › Sensors** (as usual).
2. In the Varia's sensor settings, set the light mode to **Off** (or turn **Auto Control** off), so Karoo doesn't turn it on at ride start.
3. Open **Flashback**, tick the Varia, and pick the flash mode. **Test flash** shows you what it looks like.

If you'd rather have Karoo run the light normally (e.g. solid at night) and just get a flash on approach, leave Karoo's settings alone. Flashback will restore whatever mode the light was in.

## Build & install

Requirements: JDK 17+ and an Android SDK with platform 37. No GitHub token needed; karoo-ext is pulled from JitPack.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To sideload onto the Karoo, either connect over USB with USB debugging on and run `adb install`, or host the APK somewhere and share the link to the Karoo from the Hammerhead Companion app.

**Uninstall KarooFireFly (or disable its radar flash) first.** Two extensions fighting over the same light is not a good time.

## Releasing

The version lives in `gradle.properties` (`flashback.versionName` / `flashback.versionCode`).

1. Add notes under `## [Unreleased]` in `CHANGELOG.md` and commit.
2. `scripts/release.sh 0.2.0`. This bumps the version, stamps the changelog, commits and tags `v0.2.0`.
3. `git push origin main --follow-tags`

The tag triggers `.github/workflows/release.yml`. It tests the app, builds a signed `flashback.apk`, and publishes a GitHub Release with the changelog notes and a Karoo `manifest.json`. The Karoo polls that manifest (`releases/latest/download/manifest.json`) to offer in-app updates.

**Signing.** Release builds are signed with `~/.android-keystores/flashback-release.jks`. Locally, the credentials come from `~/.gradle/gradle.properties`. In CI they come from the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. **Back up that keystore.** If you lose it, existing installs can't be updated: you'd have to uninstall and reinstall. Debug and release builds are signed differently, so switching from the debug APK to a release needs a one-time uninstall.

## How it works / firmware fragility

Karoo doesn't let third-party apps open ANT+ channels, so light commands go through Karoo's internal `SensorService` AIDL (`LightCommandConnectionAIDL`). That's undocumented, and two 2026 firmware updates broke it:

- **Karoo OS 1.663.2548 (15 Oct 2026)** inserted a method into `SensorServiceAIDL`, which shifted the transaction codes by one. Fix: probe candidate codes at runtime and accept the one that returns a `LightCommandConnectionAIDL` binder.
- **Karoo OS, 12 Nov 2026:** SELinux blocks reading the sensor-service APK, so reflection on Hammerhead's `LightMode` and `Device` classes fails. Fix: hand-written parcels with the same class names and byte layout (`io.hammerhead.datamodels...LightMode`, `HammerheadDeviceParcel`).

Both fixes come from szaffarano's fork (v0.7.2) and live in `karoo/`. If the next firmware breaks it, look there first, and check whether upstream or szaffarano already patched it.

Radar comes from the public karoo-ext `DataType.Type.RADAR` stream (`RADAR_THREAT_LEVEL`: 0 none, 1 approaching, 2 fast).

## Layout

```
app/src/main/kotlin/
  dev/gtaylor/flashback/
    FlashbackExtension.kt        KarooExtension service: ride state, radar stream, wiring
    radar/RadarFlashController.kt the state machine (unit tested)
    karoo/                       SensorService light control (from szaffarano fork)
    data/                        settings model + DataStore
    ui/SettingsScreen.kt         Compose settings
  io/hammerhead/.../LightMode.kt parcel stand-in; class name is the contract, don't move it
```
