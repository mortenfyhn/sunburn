[![Build Status](https://fyhn.semaphoreci.com/badges/sunburn/branches/main.svg?key=5fe10ac9-b5d4-45c2-b926-adfb7288a2c2)](https://fyhn.semaphoreci.com/projects/sunburn)

<img src="docs/logo.png" width="96" alt="Sunburn app icon" align="right">

# Sunburn

An Android app that shows today's UV index:

<img src="docs/screenshot.png" width="280" alt="Sunburn app screenshot">

## Build & run

Requires Android SDK with platform 35 and JDK 17.

```bash
./gradlew installDebug
```

## Data

- UV index: [currentuvindex.com](https://currentuvindex.com) (CC BY 4.0)
- Location search: [Nominatim](https://nominatim.openstreetmap.org/) / OpenStreetMap (ODbL)
