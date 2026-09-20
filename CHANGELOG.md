# Changelog

## [Unreleased]

- Ship the bundled restaurant database gzipped (~20 MB → ~8 MB), inflating it on first
  open. Cuts roughly 12 MB from every APK
- Record the bundled dataset's upstream revision and seed it on first launch, so a fresh
  install no longer immediately re-downloads the full ~17.5 MB CSV to rebuild data it
  already shipped with
- Refresh the bundled dataset to the current upstream snapshot (19,460 restaurants,
  up from 19,036)
- Add a weekly `Refresh dataset` workflow that regenerates and gzips the bundled data
  when upstream changes, and opens a PR
- Fix the app dying on launch with `No Vulkan compatible GPU found` on any device without
  Vulkan. It is optional below API 29, so every Android 8/9 device could install a build
  that could not start. Switched to MapLibre's OpenGL ES variant, which all supported
  devices have
- Fix an `AbstractMethodError` crash on the first location update on API 26-28, where
  `LocationListener.onStatusChanged` is still abstract rather than defaulted
- Fix Near Me rendering a blank page when nothing is within range, which is the normal
  case outside the regions the guide covers
- Fix restaurants missing from the edges of the map. The viewport was queried as an exact
  box, so a restaurant whose centre fell just outside it was dropped even though its
  circle overlapped the screen. The query box is now wider than the camera, which also
  means a short pan lands on pins that are already loaded
- Fix Near Me reaching further north than east. It searched ±0.5° on both axes, but a
  degree of longitude is only 111km × cos(latitude) — 35km in London, 24km in Reykjavik —
  so a restaurant 40km due east could be left out of a list sorted by true distance while
  one 50km due north was kept. The search box is now the same distance on both axes

## [1.5]

- Split the release APK by ABI. Each architecture is now published as its own APK
  (~31–36 MB instead of one 67 MB build carrying all four), stamped
  `10 * <base versionCode> + <ABI offset>` so F-Droid clients resolve the right variant.
  An unsplit `Mmmap-<code>-universal.apk` is still published for sideloading.
- Fix `just run debug`, which launched `app.mmmap/.MainActivity` even though the debug
  build carries `applicationIdSuffix = ".debug"`
- Fix `just assemble-release` with no arguments failing under macOS's bash 3.2
  (`"${ARGS[@]}"` on an empty array trips `set -u`)
- Fix all restaurants disappearing when the map is rotated — visible-region screen
  corners were being used as geographic SW/NE, inverting the latitude bounds
- Fix the map view never being destroyed when leaving the Map tab, leaking a native
  map, GL renderer and connectivity receiver on every Map↔Nearby switch
- Fix 10 restaurants with no price data (including a 1-Star) showing up under the
  `$$$$` filter, and rendering as "none" in the detail sheet
- Fix crashes when tapping Directions on a device with no maps app, or the website
  button on the one restaurant with a malformed URL in the dataset
- Fix a corrupt or full tile cache crashing the app on every launch
- Harden dataset sync: validate the CSV header so an upstream column change can't
  silently corrupt awards, bound multi-line record accumulation so one stray quote
  can't drop thousands of rows, and stop a tiny parse replacing the full dataset
- Fix the Near Me tab leaking a database observer on every visit, and show a message
  instead of an empty list when location is unavailable
- Fix export reporting success when the file could not be written
- Fix a manual light/dark theme choice resetting on rotation

## [1.4]

- Disable AGP's "Dependency metadata" signing block (`dependenciesInfo.includeInApk = false`)
  so F-Droid's APK scanner accepts the release build

## [1.3]

- Move `versionName` / `versionCode` from `gradle.properties` into literal
  values in `app/build.gradle.kts` so F-Droid's `checkupdates` regex parser
  can extract the current version at each tagged commit

## [1.2]

- Drop `kotlin { jvmToolchain(17) }` in favour of `compilerOptions { jvmTarget = JVM_17 }`
  so F-Droid's build server (which disables Gradle toolchain auto-provisioning) can
  build successfully

## [1.1]

- Move `versionName` / `versionCode` into `gradle.properties` so source-only
  builds (F-Droid) produce identically-versioned APKs without `-P` flags
- Release pipeline auto-bumps `gradle.properties`; GH Actions workflow
  fails fast if it drifts from the input version
- Fix F-Droid metadata output path: `app-release-unsigned.apk`

## [1.0]

Initial release.

- Browse all MICHELIN Guide restaurants on an offline-first map (OpenFreeMap tiles)
- Filter by distinction: ★★★ / ★★ / ★ / Bib Gourmand / Selected Restaurants
- Filter by cuisine and price range
- Tap any pin for details: phone, website, cuisine, facilities from the MICHELIN dataset
- Near Me — restaurants sorted by distance
- Direct deep-link to each restaurant's official MICHELIN Guide page
- Mark restaurants as visited; export / import visited list
- No tracking, no analytics, no account required
