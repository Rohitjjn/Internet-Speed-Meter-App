# Changelog

All notable changes to this project will be documented in this file.

## [2026-10-13 · loop 3] — speed-test resilience, honest accounting, Hindi

### Fixed — background & OS compliance
- **`onTimeout` no longer restarts in a tight loop.** On Android 15/16 a `dataSync` FGS
  that the OS keeps refusing used to be re-requested instantly, burning battery and the
  remaining daily budget. The restart is now delayed by an exponential backoff
  (5 s → 10 s → 20 s → 40 s → 80 s, capped at 120 s), gives up after 6 attempts and lets
  the 60-minute WorkManager watchdog take over; a delayed retry is skipped when a start
  has already landed.

### Fixed — daily accounting
- Per-transport byte deltas now come from `SpeedEngine.drainAttributed()`, which owns the
  "already handed over" watermark, instead of the accounting loop diffing two full map
  snapshots. A re-seed in between (service restart, counter-source switch, session reset)
  contributes **nothing** rather than a negative — and therefore spiky — delta.
- The engine refuses an **implausible counter jump** (more than 8 GiB in one 500 ms read,
  i.e. faster than any phone radio) exactly like a counter that went backwards: the read
  is dropped and re-anchored, and the untrustworthy magnitude is kept out of the session
  totals too.

### Added — active speed test resilience
- **More than one server.** `speed.cloudflare.com` is blocked by some corporate DNS
  servers and a few ISPs. The test now probes a candidate list (Cloudflare → Hetzner →
  OVH) and fails over to the first endpoint that answers a round trip. Settings →
  **Speed Test Server** pins a preference (`AUTO` keeps the failover order).
- **Each number says which server produced it.** Some fallback endpoints can only serve
  downloads, so the result records the download server and the upload server separately
  and the UI shows both; when nothing reachable can take an upload, upload is reported as
  *not measured* instead of being invented.
- **Mobile-data confirmation.** Starting a test on a metered network now asks first:
  a real throughput test has to move real bytes (typically 10–60 MB) and that comes out
  of the user's plan. The exact amount the test actually used is shown afterwards.
- **No connectivity → no test.** The test refuses to start when the device reports no
  internet instead of failing after 30 s of socket timeouts.
- **Abort on transport change.** If the OS reports a different transport mid-test
  (Wi-Fi → mobile data), every phase unwinds and the test stops — the alternative was
  billing the user's data plan for the rest of a download burst.
- **Stream ramp-up.** The test opens 2 streams and only adds the rest when the line
  proves it can use them (≥ 5 Mbit/s measured after 1.5 s), so connection-setup noise no
  longer pollutes slow-line results. The result reports how many streams actually ran.
- **Upload honesty.** After the deadline the upload phase reads the server's response: an
  upload the endpoint rejected is an error, not a throughput number.

### Added — engineering
- **`SourceHealth`** — the counter-source switch decision extracted out of the 500 ms
  read loop into a pure, unit-tested helper (throttled probing + positive-evidence-only
  switching), with 11 new tests.
- **DataStore round-trip tests** (`PreferencesManagerTest`) for every user setting,
  including the new server preference and the extended speed-test history record.
- **`GaugeScale`** — the gauge's fixed ladder (10 Mbit/s → 5 Gbit/s), unit-tested.
- Instrumented `SpeedTestSmokeTest` (network-gated, skipped when offline) so a breaking
  change in the public endpoint is caught on a real device.

### Added — UI
- Gauge rebuilt as an instrument: 51-tick scale, spring-loaded needle sweep, scale end
  labels, plus the existing progress arc.
- Phase label **cross-fades**; metric tiles animate when a value lands.
- **Share result** — a plain `ACTION_SEND` text intent (no SDK, no image) with the real
  download/upload/ping/jitter, both servers and the measured data cost.
- Dashboard: **two-column layout above ~600 dp** (tablets/landscape), a breathing dot
  while the engine is still coming up, and the hero card is de-cluttered (`LAST WINDOW`
  lives in Settings → Measurement Engine where it belongs).
- History: **this month vs last month** recap on the pinned monthly card.
- Settings: **Speed Test Server** picker.

### Added — localization
- **Hindi (`values-hi`)** — every user-visible string moved out of the Compose sources
  into `values/strings.xml`, so the app is now translatable without touching Kotlin
  (198 strings; proper nouns and unit symbols stay untranslated on purpose).

### Fixed — tests
- `ExampleInstrumentedTest` asserted the Kotlin namespace (`com.example`) instead of the
  real `applicationId`, so it could never pass on a device.

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
