# Sunburn — spec

A one-screen Android app that answers *do I need sunscreen today?* with
today's UV index curve for one chosen location.

## Scope

- One screen, one location at a time, today only.
- No GPS, notifications, widgets, or background work.
- Recents list (max 5) for quick switching.

## Intentional choices

- **Default location is Trondheim**, seeded on first launch.
- **UV > 2 filled orange.** No gradient. Threshold is fixed at 2.0.
- **Curve spans 00–24** but the x-axis only labels `06 / 12 / 18` —
  the day's edges are obviously zero.
- **Y-axis is just `0` and `2`.** No gridlines, no other ticks. The
  threshold `2` is tagged directly onto the orange fill's left edge
  (direct labelling) rather than as a free-floating axis tick. Falls
  back to the axis when there's no crossing today.
- **Peak value floats above the apex**, replacing y-axis ticks for
  the top of the range.
- **Current value floats above the now-dot**, larger than the peak
  label since it's the primary reading. Peak label is suppressed when
  the now-dot sits on the peak (within an hour) to avoid overlap.
- **Location picker at the bottom** — thumb-reachable on tall phones.
- **Stale-while-revalidate cache:** today's cached forecast renders
  instantly and stays visible if the refresh fails. One successful
  fetch in the morning keeps the app working offline the rest of the
  day.
- **Light theme only.**
- **Localised** to English / Norwegian Bokmål / Norwegian Nynorsk.

## Data

- UV: [currentuvindex.com](https://currentuvindex.com) (CC BY 4.0).
  Matches yr.no closely; better than Open-Meteo at Norwegian latitudes.
- Search: [Nominatim](https://nominatim.openstreetmap.org/) (OSM/ODbL).

Both attributions sit on the main screen because the licences require
visible credit.

## Non-goals

- Multi-day forecasts.
- Play Store / F-Droid (might happen later).
- Notifications, widgets, watch face, complications.
- Cloud sync, accounts, multi-device.
- Skin-type / SPF calculators.
