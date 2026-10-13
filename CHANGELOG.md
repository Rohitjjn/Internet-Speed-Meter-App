# Changelog

All notable changes to this project will be documented in this file.

## [2026-10-13]
### Fixed — real-time speed (the core issue)
- Rewrote the meter as a dedicated 1 Hz foreground-service ticker on
  `Dispatchers.Default` with deadline scheduling: it now reports per-second
  speed exactly like other speed-meter apps. The hot path only reads
  counters and publishes — no Room, no `NetworkStatsManager`, no bitmap
  work per tick (icon is cached), so cadence stays steady on slow devices.
- Added a `TrafficStats` ⇄ `/proc/net/dev` dual-source with a health check
  that auto-switches when a source goes stale (cold-start 0/0 or ≥5
  stagnant ticks), fixing OEM ROMs where `TrafficStats` totals are locked.
- Added a 4 s window guard: a gap from Doze/suspension publishes `0`
  instead of a misleading averaged spike.
- Moved all slow work (Room upserts, `NetworkStatsManager` queries) to a
  separate 15 s accounting loop.

### Removed
- **Add-on data cap feature (fully removed)**: all "freeze/resume" UI,
  dialog state, simulation loops and related ViewModel logic. The app never
  blocks data; only an opt-in *warning* limit remains.
- Fake/simulated data: dummy history seeding, fabricated DL/UL splits and
  "interactive traffic generation" are gone — every number is a real OS
  counter.
- Unneeded permissions: `INTERNET`, `ACCESS_WIFI_STATE`, `READ_PHONE_STATE`
  dropped from the manifest (privacy + lighter install).

### Added
- **GitHub Actions workflow** (`.github/workflows/android-build.yml`):
  manual build & release — pick `release`/`debug`, set version name/code,
  tag, title, notes; runs unit tests, builds the signed APK, uploads a
  build artifact and (optionally) a GitHub Release (overwrites the asset on
  the same tag).
- 60-sample per-second history ring buffers + live 30-second sparkline.
- Session peak download/upload tracking.
- `onTimeout` handling for the Android 15+ `dataSync` FGS budget (restart
  for a fresh budget, else stop cleanly) plus a 60-minute WorkManager
  watchdog and a boot-completed restart.

### Changed
- **UI — premium glassy theme (light + dark)**: GPT-style glassmorphism.
  New `AppBackground` ambient glows, `GlassCard` (shadow → clip → fill →
  1 dp border → top highlight), `GlassChip`, and inverse-ink accent cards
  for totals. New 24-vector icon set, monospace speed numerals, floating
  glass-pill bottom navigation.
- **Dashboard** rebuilt: hero live-speed card with network chip, PEAK chip,
  large ↓/↑ readout and sparkline; real Mobile/Wi-Fi daily cards.
- **History** rebuilt: real rows only (last 30 days) with a pinned monthly
  total; empty state when there is nothing yet.
- **Settings** rebuilt: appearance, warning limit (segmented control),
  permissions (Usage access, notifications), autostart, data, about.
- Lightweight build: pruned dependencies, minimal R8 keep rules, Gradle
  9.8.1 wrapper + AGP 9.1.1 (JDK 17).
- Tests: new pure-JVM formatter/parser tests, Robolectric Room + viewmodel
  tests; removed stale screenshot/Robolectric tests.

## [2026-05-27]
### Added
- Completed App Development. Implemented SettingsScreen, Dark Mode toggle, and Custom Data Usage Warning Notifications.
- Implemented SpeedMeterService Foreground Service, integrated NetworkStatsManager for real data tracking, and connected Add-On freezing logic to live database mutations.
- Implemented Room Database architecture. Built HistoryScreen UI with LazyColumn and Monthly Summary Card.
- Created `DataUsageEntity`, `DataUsageDao`, `AppDatabase`, and `DataUsageRepository` under modern clean architecture guidelines.
- Designed dynamic LazyColumn List in `HistoryScreen` displaying 15 days of populated telemetric data with adaptive "Today" headers and detailed subclass signal splits.
- Engineered Pinned "This Month Total" summary card layout with elegant, high-contrast dark visual accents mirroring the styling parameters.
- Implemented `HistoryViewModel` with full background coroutine mappings and proactive automatic DB seeding of rich, lifelike logs on initial application setup.
- Added extensive robolectric test cases (`HistoryDatabaseTest`) confirming perfect data insertion, query criteria, and SQLite month-to-month calculation totals.
- Implemented Premium Dashboard UI featuring 4 high-end minimalist measurement cards arranged vertically with glassy bordering, translucent accents, and technical monospaced telemetry indicators.
- Created `DashboardViewModel` with active simulation data loops and ticker structures, exposing real-time flows.
- Engineered `PreferencesManager` utilizing jetpack **DataStore Preferences** for high-integrity persistent state storage.
- Designed the "Freeze/Resume" coordination flow: starting the Add-On card launches an elegant M3 Confirmation Dialog, freezes Card 1 (Mobile Data) into a partial-alpha visually paused card with a custom badge, and triggers real-time ticking simulation in Card 3. Stopping reverses these states seamlessly.
- Configured robust Unit test suit `DashboardViewModelTest` using Robolectric to ensure robust state transactions under all user scenarios.
- Project Initialized.
- Set up Base Architecture with Jetpack Compose.
- Implemented premium minimalist Material 3 Theme (Light Theme with Dark Theme support, using pure whites, blacks, and translucent slate).
- Created styled fluid-fade Animated Splash Screen with black generic vector icon (scales & fades in, lasts 2 seconds, then navigates).
- Constructed Main Application scaffold with an elegant glassy transparent Bottom Navigation Bar featuring custom-designed tabs ("Dashboard", "History", "Settings").
- Created polished placeholder screens for Dashboard, History, and Settings.
- Added Project Documentation system at the project root (`project-blueprint.md`, `database-schema.md`, `CHANGELOG.md`).
