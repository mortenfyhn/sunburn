# Sunburn — Claude notes

See `SPEC.md` for the product spec and intentional design choices.

## Build & install

```bash
./gradlew installDebug
```

Requires Android SDK with platform 35 and JDK 17 (the user's default).

## Emulator workflow

The user has two AVDs: `Medium_Phone_API_36.1` and `small_phone`. Boot one
without snapshotting state between runs:

```bash
~/Android/Sdk/emulator/emulator -avd Medium_Phone_API_36.1 \
    -no-snapshot-save -no-boot-anim -netdelay none -netspeed full
```

Wait for boot to complete before installing:

```bash
adb wait-for-device
adb shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 1; done'
```

Launch the app after install:

```bash
adb shell am start -n no.fyhn.uvindex/.MainActivity
```

## Screenshotting for visual review

```bash
adb exec-out screencap -p > /tmp/sunburn.png
```

Then `Read` the file — Claude Code renders the PNG inline.

## Chart code

`app/src/main/java/no/fyhn/uvindex/UvChart.kt` — single Canvas composable.
Threshold (UV > 2) is drawn implicitly as the lower edge of the orange fill,
not as a separate reference line. Don't add gridlines.
