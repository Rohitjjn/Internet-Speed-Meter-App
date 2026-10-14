# Changelog

All notable changes to this project will be documented in this file.

## [2026-10-14 · loop 6] — stable signing (installs update in place), pause-by-you, screen-off cadence, acknowledged upload window, monthly limit, CSV attachments
### Fixed
- **"App not installed as package conflicts with an existing package."** Every CI build
  without a `KEYSTORE_BASE64` secret was signed with a *fresh* throwaway key (and debug
  builds with the runner's own debug keystore), so no Actions build could ever update the
  previous one. All builds — `debug` and `release` — are now signed with **one stable key**:
  `KEYSTORE_PATH`/`KEYSTORE_BASE64` if provided, else `my-upload-key.jks` in the repo root
  (git-ignored, local), else the committed `keystore/ci-shared.p12` (alias `upload`, password
  `android`). `app/build.gradle.kts` resolves it in that order and prints which one it used;
  the workflow's *Prepare signing key* step reports it in the job summary; a new *Verify APK
  signature* step runs `apksigner verify --print-certs` on the artifact. `version_code` is
  optional and defaults to the **workflow run number**, so a later build is always a higher
  version. **One-time uninstall required** for installs signed with the old throwaway keys
  (export History first); README documents it, together with the fact that the committed key
  is a convenience, not a secret, and how to move to a private key.
- `softprops/action-gh-release@v2` was passed `overwrite`, which is not an input (an
  "unexpected input" warning on every release); it is `overwrite_files`.
- **Upload speed counted bytes the kernel still held.** `OutputStream.write()` returns when
  the socket *buffer* accepted the bytes, not when they left the phone; at the deadline a
  slow uplink can still hold a few hundred KB, which the send-side window counted as "sent".
  Each stream now ends its chunked body at the deadline and waits (≤ `UPLOAD_ACK_WAIT_MS` =
  3 s) for the server's response; when **every** stream is acknowledged the window runs from
  the first byte to the **last acknowledgement** and counts everything (pure
  `computeUploadThroughput`, tested in `SpeedTestMathTest`). A silent stream falls back to
  the classic send-side window and `ThroughputResult.acknowledgedWindow` is `false`; the
  result card shows an *Upload window* row either way.

### Added
- **Pause / resume the meter yourself.** A pause button in the dashboard header (with an
  explanatory banner and a *Resume* action) and a *Pause meter* switch in Settings stop the
  service and store `meter_paused_by_user`. `services/MeterControl.kt` is the one place every
  restart path asks — `MainActivity.onResume` and the stage `LaunchedEffect`, the boot
  receiver (now `goAsync()` so it can read DataStore), `SpeedKeepAliveWorker`, the timeout
  retry, and a safety net in `onStartCommand` for stale sticky restarts. The UI always
  distinguishes **"paused by you"** (hero label, Settings engine status, tile subtitle) from an
  OS pause; no "meter paused" notice is posted while the user holds the pause.
  `PreferencesManagerTest`, `SettingsViewModelTest` and a Compose test cover the flag.
- **Screen-off read cadence.** The service listens to `ACTION_SCREEN_ON/OFF` (initial state
  from `PowerManager.isInteractive`) and reads every **2 s** instead of 500 ms while the
  display is off, waking at once when it comes back. The engine divides by the measured
  window, so the numbers are identical (`SpeedEngineTest.aTwoSecondScreenOffCadenceMeasuresTheSameRate`,
  `switchingFromScreenOffToOnCadenceDoesNotSpike`); 2 s stays inside the 3 s "frozen" guard,
  so Doze is still flagged as paused. The diagnostics row shows both cadences.
- **Optional monthly mobile limit** (Settings → alerts): the same warn-only 50/90/100 % rule
  per calendar month, one dialog per threshold per month (new DAO query for month-to-date
  mobile bytes, keys `monthly_limit`, `monthly_limit_unit`, `last_dismissed_monthly_alert[_month]`).
  The daily and the monthly rule share the pure `util/LimitAlerts.kt` (`LimitAlertsTest`).
  The History recap shows the month's mobile use against the limit as a thin bar.
- **Per-day drill-down in History**: tapping a day expands it to download/upload per network
  from the four stored counters, with expanded/collapsed state semantics.
- **CSV exports as real attachments.** `util/CsvExport.kt` writes to `cache/exports/` and
  shares through a manifest `FileProvider` (`${applicationId}.fileprovider`,
  `res/xml/file_paths.xml`; text fallback if the provider fails). New **speed-test history
  export** (`util/SpeedTestCsv.kt`: UTC timestamp, epoch ms, bytes/s *and* Mbps, latency,
  jitter, server; `SpeedTestCsvTest`).
- **Wallpaper accent (Material You)** on Android 12+: a fifth swatch borrows only the dynamic
  primary/onPrimary, glass surfaces and status colours stay the app's own. The INK swatch now
  always shows the app's own primary instead of the currently selected accent.
- **Reduce motion** switch (Settings → theme): skips the splash zoom/fade, the breathing
  "waiting" label and the hero digit roll (`LocalReducedMotion`); measurement cadence is
  untouched.
- `NotificationText.build()` — the status-bar ticker's two lines as a pure function
  (`NotificationTextTest`: both directions, chosen unit, transport, total, paused suffix).
- `publish_check.py --self-test` checks its log/JUnit/lint parsers offline; the test job runs
  it before Gradle so a broken publisher cannot hide a real failure.

### Changed
- 31 new EN/HI string pairs (267 keys, parity-tested); `engine_row_cadence_value` names both
  cadences. `SpeedTestState.uploadAcknowledgedWindow`; `ThroughputResult.acknowledgedWindow`
  (default `false`, fakes unaffected). Smallest text size stays 10 sp.

## [2026-10-14 · loop 5] — CI you can read, honest "meter paused", adaptive download, pushed abort, test isolation
### Fixed
- **CI gave no compile signal.** The workflow only ran on manual dispatch, and GitHub's raw
  Actions logs are not reachable from every environment. The unit-test and lint jobs now run
  on **every push** (Markdown/docs-only pushes skipped) and publish condensed **check runs**
  — "Unit test log" (compiler errors, failing tests with the first stack frames, Gradle task
  that failed) and "Lint log" (every issue with file:line) — plus inline annotations, through
  a stdlib-only `.github/scripts/publish_check.py`. The build/release job keeps all seven
  manual inputs and still runs only on dispatch.
- **Four tests that the first green-light run exposed:** the gauge scale asserted a 10 %
  headroom the spec never asked for (`niceCeilingMbps` now rounds up to the next nice number,
  44 → 50, 95 → 100); `resetSession` did not republish, so the dashboard kept the old session
  figures until the next tick; `cancelTest()` needed the dispatcher drained before asserting;
  `TestServerTest`'s cost figures were stale. `fallbackToDestructiveMigration()` →
  `fallbackToDestructiveMigration(dropAllTables = true)` (Room 2.7 deprecation).
- **A refused foreground start was silent.** Since Android 12 a foreground service cannot be
  started from the background (WorkManager is *not* an exemption) and Android 15 can exhaust
  the `dataSync` budget; the watchdog, the boot receiver and the post-timeout retry simply
  failed and the user saw a frozen "last known" number. `startService()` now returns whether
  the start was accepted; `startForegroundWithNotification()` reports a thrown
  `startForeground`; every refusal posts a low-priority **"Speed meter paused"** notification
  (channel `speed_meter_status`, id 1002) that says nothing is being counted and that a tap
  resumes the meter (a notification tap, like opening the app, *is* allowed to start it). The
  notice is cleared on the next successful `onStartCommand`, `MainActivity.onResume()`
  re-starts the meter, and the watchdog returns `Result.retry()` (back-off) when refused.
- **Download could be request-bound on fast lines.** Every stream fetched 20 MB bodies back
  to back; with 4 streams a gigabit connection finished each in ~150 ms and spent a visible
  share of the window on request setup. Streams now **adapt**: when a body completes in under
  one second the next request doubles (20 → 40 → 80 → 100 MB cap), on a **kept-alive**
  connection; a slow or cut-off body keeps its size. `nextRequestBytes` is pure and tested.
- **Transport abort was polled.** The view model checked `currentTransport()` every 500 ms;
  `Connectivity.transportChanges()` is now a `Flow` fed by `registerDefaultNetworkCallback`
  (with a 2 s safety poll for ROMs that drop callbacks), so a Wi-Fi → mobile switch aborts the
  test the moment the OS reports it. The engine's grace window also starts at the first byte.
- **Unit tests wrote to the app's real DataStore.** `PreferencesManager` takes its
  `DataStore<Preferences>` as a constructor parameter (default: the app file);
  `SettingsViewModel` accepts a `PreferencesManager`; `TestPreferences` gives every test an
  isolated file that is deleted afterwards. The singleton-restore hacks are gone.
- Lint: dead API-23 branches (minSdk is 24), unused `black`/`white` colours and two unused
  drawables removed; redundant activity label dropped; widget layout no longer uses 9–10 sp.

### Added
- **Quick Settings tile** (`SpeedMeterTile`): one tap from the shade into the app. Its state
  mirrors whether the meter is running and its subtitle (Android 10+) is today's *persisted*
  total or "Meter paused" — never a live speed, which a tile cannot follow honestly.
- Tests: `ScreensRenderTest` (Settings renders, the server group expands and **saves on
  tap**; the idle speed-test screen renders gauge, phase label and START without a network),
  `TodayUsageWidgetTest` (the `RemoteViews` carry the real total, the split and a timestamp,
  and the root is tappable), `SpeedTestMathTest` request-sizing cases. 99 unit tests.

### Changed
- **Readability:** no Compose text below **10 sp** any more (the 8.5 / 9 / 9.5 sp eyebrows and
  axis captions were bumped); the widget uses ≥ 11 sp.
- **Haptics:** one light tick when a *new* speed-test result lands (not when the tab is
  re-entered with an old one).
- **Settings** is a single readable column, capped at 720 dp and centred on tablets and in
  landscape.
- README / blueprint / schema describe the push CI, the refusal path, the adaptive request
  size, the pushed transport watcher, the tile and the test isolation. Hindi strings: 236 keys.

### Deferred (see `docs/next-loop-prompt.md`)
- Monthly mobile warning (warn-only), FileProvider-backed CSV + test-history export, 4×1
  widget, per-phase peak captions (interval peaks are too bursty to show without a 1 s
  smoothing window), dependency refresh (lint lists newer AGP/Compose/Room/WorkManager — a
  separate, CI-verified loop).

## [2026-10-14 · loop 4] — speed-test accuracy + testability, Android 15 timeout fix, full Hindi, widget
### Fixed
- **`onTimeout` could get the app killed as "not responding".** On Android 15+ the
  `dataSync` budget callback was answered with a 20 s *inline* restart from the background
  (refused by the OS on API 35+) and `stopSelf()` was never reached. The service now stops
  immediately and hands the restart to WorkManager (30 min one-shot, then exponential
  back-off 10 → 20 → 40 min via `Result.retry()`); the streak counters and their coroutine
  are gone. Both `onTimeout` overloads stay.
- **Speed test: aborting could take up to 10 s.** The cooperative flag was only polled
  between reads, so a Wi-Fi → mobile switch inside a blocking `read()` kept spending data
  until the socket timeout. Every phase now runs a **sentinel that disconnects the live
  sockets** the moment the flag flips, and the view model raises the flag *before*
  cancelling the job. `cancelTest()` can no longer race the engine into a stray
  "Test stopped" card after the screen already reset.
- **Speed test: in-flight bytes were dropped.** Stream jobs were cancelled before the
  counter was read; they are now **joined** (with a short grace) first, so the final byte
  count includes what was in flight.
- **Speed test: the number included TCP slow start.** Throughput is averaged over the
  window after a grace period (1.5 s download / 1 s upload) whenever ≥ 1 s of measurement
  remains after it, otherwise from the first byte — `computeThroughput` is pure and tested.
  Latency samples run on a kept-alive connection after a warm-up, so a "ping" is a round
  trip, not a TLS handshake. (The brainstormed "stream ramp" was rejected: it deflates fast
  lines; the grace window achieves the goal without touching the measurement.)
- **Upload could report how fast a proxy discards bytes.** The server now has to
  acknowledge a fixed 32 KiB POST with a 2xx/3xx before the timed streams start; otherwise
  the phase fails with a typed error (`UPLOAD_NOT_ACKNOWLEDGED` / HTTP code) and the result
  card shows the acknowledgement code.
- **Typed failures instead of exception strings.** `SpeedTestException(failure, host,
  httpCode)` → localized messages (`test_error_*`); `AUTO` falls back **only** when the
  *latency probe* fails on the first host — a failed download is reported, not retried on
  another host at the user's expense.
- **VPN over mobile and metered hotspots were treated as free.** `Connectivity.isMetered()`
  now also honours `ConnectivityManager.isActiveNetworkMetered`; the confirmation names the
  transport and notes when Data Saver is on.
- **Date keys depended on the device locale.** `DateFormatter`/service day keys use
  `Locale.US` digits (a Hindi/Arabic locale would have produced non-ASCII `yyyy-MM-dd`
  keys and split the history).
- Session duration can no longer read 0 when the monotonic clock starts at 0 (explicit
  "started" flag instead of a sentinel value); `SpeedEngine` accepts an injected clock.
- Settings: the endpoint radio kept a second copy of the choice that could drift from the
  stored one; the Dark Mode row had a doubled inner padding; permission rows only
  re-checked on first composition (now on every resume, in Settings and Intro).
- Dashboard limit dialog printed the limit as a raw `Double` (`2.0 GB`).

### Added
- **`SpeedTestViewModelTest`** — the test's rules with a fake `Connectivity` and a fake
  `SpeedTestRunner`: metered asks / dismiss / confirm, unmetered runs at once, AUTO fallback
  only on unreachability, download failure does not fall back, explicit endpoint never falls
  back, transport change aborts without a number, previous result exposed, STOP → idle with
  the abort flag seen by the engine.
- **`SpeedTestMathTest`** (grace-window throughput, `DeltaMath.percentChange`, `UsageCsv`),
  **`SettingsViewModelTest`** (endpoint auto-save round-trips), **`StringResourceParityTest`**
  (English ⇄ Hindi keys and format arguments), two new `SpeedEngineTest` cases (injected
  clock never runs backwards; history/average stay bounded over a 20-minute run), and an
  upload-acknowledgement case in the instrumented smoke test.
- **Home-screen widget** `TodayUsageWidget` (RemoteViews, 2×1 resizable, light/night
  colours): today's real total + mobile/Wi-Fi split + render time. Pushed by the service at
  most once a minute when a widget exists, refreshed every 30 min by `WidgetRefreshWorker`,
  kept by ProGuard.
- **History CSV export** through the share sheet (`UsageCsv`, raw bytes, newest first, no
  file / storage permission).
- **Hindi pass 2** — every string in Intro, Dashboard, Test, History, Settings, the widget
  and all dialogs is a resource now: 229 keys in `values/` with a `values-hi/` counterpart.
- **Gauge ghost needle** for the previous result of the metric being shown, a
  "↑ 12 % vs last test" chip, and a breathing "WAITING FOR ENGINE" label.
- **Accessibility**: content descriptions on the live download/upload numbers, the gauge,
  every icon button and the accent swatches; polite live regions on phase/status labels
  and the result/error cards; `Role`/`selected` semantics on the segmented controls and
  endpoint options; the widget root is described for TalkBack.
- Settings: **tap-to-save** endpoint options with a "Saved: …" summary, custom URL keeps an
  explicit SAVE ENDPOINT with an "unsaved" hint, **collapsible** SPEED TEST SERVER and
  MEASUREMENT ENGINE groups (collapsed by default, one-line status in the header), a
  "Saved: 2 GB per day" line under the limit field.
- Test and History screens are **two-column from 600 dp**.

### Changed
- `SpeedTestEngine` exposes a `SpeedTestRunner` interface (`Live` = the real engine) and
  `SpeedTestViewModel` takes `Connectivity`, `SpeedTestRunner`, `PreferencesManager` and a
  live-speed provider through its constructor (the default factory still works).
- The service accounts usage once immediately after start (the "Today" figure no longer
  shows 0 for the first 15 s with Usage access), and the status-bar glyph's `Paint`/typeface
  are created once instead of per redraw.
- `WorkManager` retry request carries explicit exponential back-off criteria.
- Removed the unused template colours from `colors.xml`; widget colours added
  (`values/` + `values-night/`).

### Docs
- README, project blueprint, database schema and this changelog describe the grace-window
  measurement, the upload acknowledgement, the timeout hand-off, the widget, the export and
  the localization status; next-loop prompt regenerated.

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
