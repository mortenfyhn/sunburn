# UV Index

A minimal Android app that shows today's UV index curve for a chosen
location. Built to answer one question: *do I need sunscreen right now?*

## Features

- 24-hour UV index curve in the location's local time, 00–24.
- The portion of the curve above UV 2 is filled in — the rough threshold
  where light skin starts needing protection on a sunny day.
- A dot marks the current hour with the live value above.
- Name-based location search (no GPS, no permissions beyond `INTERNET`).
- Recent locations are kept (max 5) for quick switching.

## Build & run

Requires the Android SDK with platform 35 installed and JDK 17.

```bash
./gradlew installDebug
adb shell am start -n no.fyhn.uvindex/.MainActivity
```

The app installs as **UV Index**. `minSdk` is 26 (Android 8.0).

## Data sources

- **UV data:** [currentuvindex.com](https://currentuvindex.com), licensed
  CC BY 4.0. Values match yr.no within ≤ 0.1 UVI in spot checks.
- **Location search:** [Nominatim](https://nominatim.openstreetmap.org/) /
  OpenStreetMap, licensed ODbL.

Both are free public services without API keys. Requests are rate-limited
and identify themselves with a `User-Agent` of `UvIndex/1.0
(no.fyhn.uvindex)` per Nominatim's usage policy.

## License

[MIT](LICENSE).
