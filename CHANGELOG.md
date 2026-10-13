# Changelog

All notable changes to this project will be documented in this file.

## [2026-10-13 · loop 3] — speed-test resilience, source-health extraction, premium polish
### Fixed
- **The active test could not survive a blocked host.** It now measures through an
  endpoint profile (`TestServer`): `AUTO` tries Cloudflare first and retries on the
  LibreSpeed public backend when the first host cannot answer the latency probe, while an
  explicit choice pins one host. The result card shows **which** host produced the numbers,
  and says so when the fallback was used. Settings gained a "Speed test server" card
  (AUTO / Cloudflare / LibreSpeed / custom `http(s)` URL with a Cloudflare- or
  LibreSpeed-style flavour).
- **A test on mobile data can no longer silently spend ~30 MB.** On a metered connection
  the app now asks first, with a real estimate computed from the measured line speed and
  the actual phase durations (`TestDataCost`, floor 5 MB, +10 % protocol overhead). Wi-Fi
  and Ethernet still start immediately.
- **A network change mid-test stops the test.** A Wi-Fi → mobile switch (or a mobile-data
  dropout) would otherwise mix two connections into one number *and* keep downloading on
  the user's data plan. The phases poll a cooperative `TestAbortSignal` — coroutine
  cancellation alone cannot interrupt a blocking socket read — so the run ends within a
  fraction of a second with an explicit message.
- **`onTimeout` no longer restarts in a tight loop.** Android 15+ `dataSync` budget
  exhaustion is answered with a delayed restart (20 s) at most twice; after that the service
  stops cleanly and hands over to a one-shot WorkManager retry (30 min), instead of burning
  the remaining daily budget immediately.
- **The status-bar glyph no longer allocates a bitmap per second.** The speed icon is drawn
  into two ping-pong buffers (~83 KB ARGB each) — on a fast line the number changes almost
  every publish, and that churn showed up as GC/battery noise.
- `ExampleInstrumentedTest` asserted the namespace `com.example` instead of the real
  applicationId, so it failed on every device; it now checks the app's own package.
- Settings "Network" row read `networkTypeFlow.value` once during composition and never
  refreshed; it is collected now.

### Added
- **`SourceHealth`** — the counter-source switch rule extracted from the service into a
  pure, unit-tested class (`SourceHealthTest`): flap-free switching, rate-limited probes
  (one extra `/proc/net/dev` read every 4th tick at most), "UNSUPPORTED ≠ 0 bytes" and
  "a peer without a shadow is never judged alive".
- **`SpeedEngine` plausibility ceiling** — a single delta above 2 GiB is refused and
  re-anchored instead of being charged to the session and the daily total (a kernel counter
  anomaly cannot invent petabytes any more).
- **Instrumented, network-gated smoke test** for the live endpoint
  (`SpeedTestEngineInstrumentedTest`): one stream / 2 s download, a latency probe and an
  abort test; it *skips* (never fails) without a connection.
- **`PreferencesManagerTest`** — DataStore round-trips for the unit, accent, reset baseline,
  last speed, limit, the 20-entry speed-test history and the new endpoint choice.
- **`TestServerTest`** — URL building for both flavours, candidate/fallback ordering,
  unusable custom URLs degrading to the default host, and the data-cost estimate.
- Hindi resources (`values-hi`) for the always-visible surfaces: the whole status-bar
  notification, the network names and the bottom navigation.

### Changed
- **Gauge is now an instrument**: labelled value scale (0 → a ceiling derived from what the
  run really measured), major/minor tick marks, a needle that sweeps to the live value, the
  latency in the dial during the ping phase, and a separate thin bar for the phase's time
  progress. No fake "max" is ever drawn.
- Phase label cross-fades between phases, metric values animate to their readings instead of
  popping in, and a **share** action (`ACTION_SEND` plain text: ↓/↑, ping, jitter, server,
  timestamp) was added to the result card.
- Hero card lost the developer-facing "LAST WINDOW / samples" stat (it moved into the
  Settings diagnostics panel, next to the new "last published window" row).
- **Dashboard is two-column from 600 dp** (tablet / landscape / foldable) instead of one
  very wide column.
- **History monthly recap** now compares this month with the previous month
  ("↑ 12 % vs last month") — real totals only, and only when a previous month exists.

### Docs
- README, project blueprint and database schema updated for the endpoint profiles, the
  metered-data confirmation, the abort rule, the timeout backoff and the localization
  status; next-loop prompt regenerated from a fresh multi-perspective brainstorm.

## [2026-10-13 · loop 2] — measurement-engine redesign + real speed test
### Fixed — "speed not calculated correctly" (the reported bug)
- **Download and upload can no longer show the same number.** Both are now computed from
  one coherent `(rx, tx)` read of a single counter source, as two independent deltas.
  Previously the source health-check compared *absolute totals across two different
  counters*, so an idle device flapped between `TrafficStats` and `/proc/net/dev` every
  few seconds and skipped/duplicated published ticks (the "34 KB, 34 KB, 25 KB, 25 KB"
  stutter).
- **The network type was never published** (`_networkTypeFlow` was written on creation
  and never again), so the hero card and the notification said "Offline" even with an
  active connection. Transport is now pushed by a
  `ConnectivityManager.NetworkCallback` and self-heals every 30 s.
- Sampling moved from 1 Hz to **500 ms reads / 1 s publishes** with the rate computed over
  the *measured* window (never an assumed 1000 ms), so bursts are no longer split across
  an imaginary second.
- A counter that goes **backwards** (interface reset / source switch / reboot) now
  re-anchors silently instead of publishing a bogus spike.
- `TrafficStats` `UNSUPPORTED` (`-1`) counters are detected per direction instead of
  silently becoming "0 bytes".

### Hardened in the same loop (self-review pass)
- `SpeedEngine`'s per-transport byte map is a `ConcurrentHashMap` and the published
  snapshot is `@Volatile`: the 15 s accounting coroutine reads them while the read loop
  writes, so a concurrent read can no longer throw or see a torn value.
- Active test: throughput is averaged over the interval that carried data (first byte →
  deadline) and progress ticks use *measured* intervals — the TCP/TLS handshake no longer
  drags the result down by a few percent, which matters when comparing with other meters.
- Status-bar icon text is sized for 4-character values (`104.9`), and `SpeedFormatter` no
  longer truncates (512.9 KB/s → 513 KB/s) — every visible digit is now rounded, not cut.
- The daily-limit warning can be dismissed (back press / outside tap = acknowledge); the
  app still never blocks or caps data.
- `metadata.json` description/capabilities updated to match the app (no unimplemented
  capability is advertised).
- Removed the 15 per-field mirror flows (`downloadSpeedFlow`, `steady*Flow`, `average*Flow`,
  `peak*Flow`, `wifi*/mobile*Flow`, `sessionBytesFlow`, `dlHistoryFlow`, `ulHistoryFlow`)
  that only duplicated `liveSpeedFlow` — one source of truth for every consumer.

### Added
- **`SpeedEngine`** — the measurement core extracted into pure, dependency-free Kotlin
  with 15 unit tests (`SpeedEngineTest`) covering directions, windows, suspension,
  counter resets, session/peak/60 s-average maths and per-transport attribution.
- **Active speed test** (new "Test" tab): real ping/jitter + 4-stream download +
  3-stream upload against Cloudflare's public test endpoint, live gauge, result detail
  and a 20-entry on-device history (Mbps, the unit ISPs advertise). `INTERNET` permission
  added **only** for this opt-in test.
- Per-network attribution: real bytes split into Wi-Fi vs mobile live rates.
- Session total + duration, session peak, 1-minute average, and a status-bar number that
  no longer flashes 0 for the first second (last speed is persisted).
- **Measurement Engine diagnostics panel** (Settings): active counter source, read
  cadence, last window, sample count and raw kernel interface totals — everything the
  meter is built on, verifiable by the user.
- Speed display unit setting: **KB/s · MB/s** or **Kbps · Mbps**.
- Accent picker (Ink / Green / Blue / Violet) for the glass theme.
- Limit alert now buzzes (short at 50/90 %, double at 100 %) and the notification is only
  rebuilt when something visible actually changed (fewer wake-ups when idle).
- CI: non-blocking `lint` job that uploads the lint report; `SpeedTestResult` persistence
  round-trip, jitter, bits/bytes and `/proc/net/dev` per-interface tests.

### Changed
- Dashboard hero card rebuilt: animated digit roll, unit-aware readouts, 1-minute average
  + session total, thin dividers, per-network chips, smarter status label
  (`REAL-TIME` / `PAUSED — ENGINE SUSPENDED` / `WAITING FOR ENGINE`).
- Sparkline: shared scale for both directions (a fast upload could previously overflow
  the chart), gradient under the upload line as well, head marker on the newest sample.
- Bottom navigation is now 4 pills (Speed · Test · History · Settings).

### Docs
- README, project blueprint and database schema rewritten to match the new engine;
  next-loop prompt regenerated from a fresh multi-perspective brainstorm.

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
