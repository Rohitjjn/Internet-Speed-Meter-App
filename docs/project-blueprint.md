# Project blueprint — Internet Speed Meter

Target: a **premium, lightweight, real** speed-meter app. Kotlin + Jetpack Compose +
Material 3, no third-party SDKs, everything on-device.

## Product decisions

1. **Truth first** — every number the user sees comes from an OS counter
   (`TrafficStats`, `/proc/net/dev`, `NetworkStatsManager`) or from a measurement the app
   itself performed (the active speed test). No simulated traffic, no fabricated splits,
   no dummy history. If the OS was suspended, the meter shows `0` and flags
   `PAUSED`; it never averages across a gap.
2. **Two engines, two questions.**
   - *Passive meter* ("what is my device using right now?"): always-on 500 ms read loop
     (2 s while the screen is off — the window is measured, so the numbers do not change)
     → ~1 Hz publish. The user can **pause** it; that pause is durable and labelled
     "paused by you", distinct from an OS pause.
   - *Active speed test* ("what can my line actually do?"): opt-in, tapped, real
     download/upload/latency measurement — the only honest way to answer the second
     question, since an idle phone legitimately shows 0.
3. **Lightweight & private** — no `ACCESS_WIFI_STATE`, no `READ_PHONE_STATE`, no
   analytics, no accounts. `INTERNET` exists **only** for the manual speed test.
   Daily usage works with *zero* permissions (per-read attribution) and upgrades to exact
   system counters when the user grants "Usage access".
4. **No data cap** — there is no "add-on data cap / freeze" feature. The app offers a
   *warning* limit — daily, and optionally monthly — (one alert each at 50/90/100 %) and
   never blocks the network (`util/LimitAlerts.kt` is the single pure rule).
5. **Premium look** — glassy-white light theme (GPT-style glassmorphism: translucent
   cards, soft top highlight, ambient glow blobs) with a full dark variant, an accent
   picker (plus the wallpaper/Material You primary on Android 12+), ink (inverse) accent
   cards, monospace numerals and animated digits — with a *reduce motion* switch that
   turns the choreography off.
6. **Durable background** — `dataSync` FGS; `onTimeout` stops the service at once and
   hands over to a one-shot WorkManager retry (30 min, exponential back-off 10 → 20 → 40 min
   on `Result.retry()`); 60-minute watchdog; boot receiver restart. An inline restart from
   the background is refused on API 35+ anyway, so none is attempted. Every restart path
   first asks `MeterControl` whether the user paused the meter.
7. **Everything localizable** — every user-facing string is a resource in `values/` with a
   Hindi counterpart in `values-hi/` (267 keys); `StringResourceParityTest` fails the build
   when a key or a format argument is missing on either side. Technical identifiers that
   are the same in every language (host names, `Mbps`, `KB/s`, HTTP codes, `/proc/net/dev`)
   stay literal on purpose.
8. **A home-screen surface** — a `RemoteViews` widget with today's real total; no Glance
   dependency, no estimation when the service is not running (it renders the persisted row).
9. **Installs that update in place** — every CI build (debug and release) is signed with
   one stable key (`KEYSTORE_BASE64` → `my-upload-key.jks` → committed
   `keystore/ci-shared.p12`), and `version_code` defaults to the workflow run number.

## Architecture

```
MainActivity (Splash → Intro → Main)
└── MainScreen — floating glass-pill nav
    ├── Dashboard  — pause/resume + reset header, "paused by you" banner, hero live card,
    │                Mobile/Wi-Fi/Today cards
    ├── Speed Test — active measurement (gauge, metrics, history + CSV export)
    ├── History    — recorded days (tap → per-day ↓/↑ per network) + pinned monthly total
    │                with the optional monthly-limit bar + CSV export (two-column ≥ 600 dp)
    └── Settings   — appearance (dark, unit, accent incl. wallpaper, reduce motion), daily +
                     monthly limits, permissions, pause switch + autostart/battery,
                     tap-to-save endpoint, collapsible server + engine diagnostics groups

TodayUsageWidget (AppWidgetProvider, RemoteViews)
├── rendered from DataUsageEntity(today): total, mobile/Wi-Fi split, render time
├── pushed by the service after accounting (≤ 1/min, only when an instance exists)
└── WidgetRefreshWorker — 30 min periodic + one-shot on add/resize (persisted row)

SpeedMeterTile (TileService, Quick Settings)
├── state = meter running (ACTIVE) / not running (INACTIVE)
└── subtitle (API 29+) = today's persisted total, "Measuring", "Meter paused" or
    "Paused by you"; tap opens app

MeterControl (object)
├── meter_paused_by_user (DataStore) mirrored in a StateFlow for the UI/tile
├── pause(): flag → stopService → clear the OS "paused" notice; resume(): flag → start
└── mayStart()/startUnlessPaused() asked by MainActivity, BootCompletedReceiver (goAsync),
    SpeedKeepAliveWorker, the timeout retry and onStartCommand (stale-restart safety net)

SpeedMeterService (foreground, dataSync)
├── read loop — 500 ms on-screen / 2 s screen-off (ACTION_SCREEN_ON/OFF receiver,
│     PowerManager.isInteractive at start; a screen-on wakes the loop at once), Dispatchers.Default
│     TrafficCounterReader: TrafficStats  ⇄  /proc/net/dev
│     SourceHealth (pure): stagnation/UNSUPPORTED → rate-limited probe → switch only
│     when the other source is genuinely alive
│     → SpeedEngine (pure maths) → LiveSpeed every ~1 s
│     → StateFlows: raw dl/ul, steady (EWMA), 1-min average, peaks, history,
│       session bytes, per-transport rates, transport
│     → status-bar notification (NotificationText, pure; rebuilt only when text changes)
├── ConnectivityManager.NetworkCallback → transport push (+ self-heal every 60 reads)
├── accounting loop — 15 s, Dispatchers.IO
│     Usage access ? NetworkStatsManager since midnight − reset baseline
│                  : engine per-transport attribution deltas
│     → Room upsert(daily_data_usage) → widget push
└── refusal path — startService(): Boolean; a refused/failed foreground start
      (API 31+ background rule, API 35 dataSync budget) posts the "Speed meter paused"
      notification (channel speed_meter_status, id 1002, tap → MainActivity), cleared on
      the next successful onStartCommand; MainActivity.onResume() restarts the meter

SpeedTestEngine (opt-in, Dispatchers.IO) — behind the SpeedTestRunner interface
├── endpoint: TestServer profile — AUTO (Cloudflare → LibreSpeed fallback **only when the
│             latency probe fails**), explicit built-in host, or a custom http(s) URL
├── guard: TestDataCost estimate shown before a metered run (mobile, metered Wi-Fi, VPN
│          over either — Connectivity.isMetered) + Data Saver note
├── abort: TestAbortSignal polled by every read loop **and** a per-phase sentinel that
│          disconnects the live sockets the moment it flips (blocking reads included)
├── latency: warm-up, then 5 round trips on a kept-alive connection → min / avg / jitter
├── download: 4 parallel streams, 8 s budget; streams joined before the counter is read;
│             throughput over the window after a 1.5 s grace (≥ 1 s left) else first byte;
│             request size adapts — 20 MB first, doubled (≤ 100 MB) whenever the previous
│             body completed in < 1 s (nextRequestBytes, pure) on a kept-alive connection
└── upload: 32 KiB acknowledgement POST (2xx/3xx required, code kept in the result),
            then 3 parallel chunked POST streams, 6 s budget; at the deadline each stream
            closes its body and waits ≤ 3 s for the server's response — all acknowledged →
            window = first byte → last ack, all bytes (computeUploadThroughput, pure);
            otherwise the send-side 1 s-grace window, flagged acknowledgedWindow = false
   → SpeedTestViewModel(Connectivity, SpeedTestRunner, PreferencesManager, liveSpeed)
     abort watcher = Connectivity.transportChanges() (NetworkCallback push + 2 s safety
     poll) → first transport ≠ transport at start raises TestAbortSignal
     → SpeedTestScreen (gauge + ghost needle + "vs last test" chip + share,
       last 20 results in DataStore, two-column ≥ 600 dp)

Supporting
├── WorkManager SpeedKeepAliveWorker (60 min; retries with back-off and posts the paused
│   notice when the OS refuses the start) + BootCompletedReceiver (same refusal path)
├── CsvExport — FileProvider (${applicationId}.fileprovider, cache/exports/) share of the
│   UsageCsv / SpeedTestCsv text as a .csv attachment, plain-text fallback
└── DataStore — theme, accent, reduced motion, speed unit, daily + monthly limit,
                onboarding, reset baseline, alert dismissals (day / month), last known
                speed, speed-test results, meter_paused_by_user
                (PreferencesManager(context, store) — the store is injectable; tests use
                 TestPreferences for a per-test file)
```

## Measurement rules (the part that must never regress)

`SpeedEngine` is pure Kotlin with no Android imports, so all of this is unit-tested
(`app/src/test/java/com/example/SpeedEngineTest.kt`):

| Rule | Why it exists |
|---|---|
| Download and upload come from **one coherent `(rx, tx)` read** of **one source**, as two independent deltas | The reported bug "upload and download show the same number" |
| `rate = bytes ÷ measured elapsed ms` | A late tick must not inflate the rate |
| Windows > 3 s publish `0` + `paused = true`, and are kept out of history/averages | Honest behaviour after Doze/suspension (but the bytes still count towards the session) |
| A counter that decreases re-anchors silently | Interface resets / source switches / reboots must never create a spike |
| Each counter source keeps its **own** anchor; switching re-seeds the engine | The old cross-source comparison flapped every few seconds |
| `TrafficStats.UNSUPPORTED` is tracked per direction | A blocked counter is "unmeasurable", not "0 bytes" |
| Transport is pushed by `NetworkCallback`, re-resolved every 60 reads (30 s on-screen) as a fallback | Instant, correct attribution without polling in the hot path |
| A 2 s screen-off cadence yields the same rate as 500 ms reads, and the on/off transition never spikes | Battery saving must not change a number (`aTwoSecondScreenOffCadenceMeasuresTheSameRate`) |
| Upload: bytes still in the socket send buffer at the deadline are timed until the server acknowledged them | `write()` returning is not "sent"; the send-side window over-reports on slow uplinks (`SpeedTestMathTest`) |
| Per-transport rates are zeroed on a transport change | Wi-Fi speed must not "stick" after switching to mobile data |

## State flows (single source of truth)

| Flow | Owner | Consumers |
|---|---|---|
| `liveSpeedFlow` (`LiveSpeed`) | service | dashboard hero, settings diagnostics — the *only* speed surface (the old per-field mirror flows were removed so there is exactly one source of truth) |
| `networkTypeFlow` | service | network chip, notification, diagnostics |
| `engineStatusFlow` (`EngineStatus`) | service | Settings → Measurement Engine |
| `todayUsageFlow` | service | notification |
| `permissionGrantedFlow` | service | settings badge, dashboard hint |
| `MeterControl.pausedByUser` | MeterControl (mirrors DataStore) | dashboard header button + banner, hero label, Settings switch/status, tile |
| `LocalReducedMotion` | theme (from DataStore) | splash, hero digit roll + waiting label |
| `SpeedTestViewModel.state` / `.history` | viewmodel | Speed Test screen (phases, server, `usedFallback`, `pendingConfirmation*`, `previousResult`, `uploadAckCode`, `uploadAcknowledgedWindow`, `aborted`) |
| `testServerFlow` / `testServerUrlFlow` / `testServerFlavourFlow` | DataStore | read **at run start** by the view model (no mirror copy); Settings highlights the stored value and saves on tap |
| Room `getDataByDate` / `getAllHistory` / `getThisMonthTotal` / `getThisMonthMobileTotal` | DB | dashboard, history, monthly-limit alert |
| DataStore flows | PreferencesManager | theme, accent, reduced motion, unit, daily/monthly limit, intro, alerts, last speed, user pause |

## UI theme system

- `Color.kt` — light/dark glass palettes (background, 3 glow colours, glass
  fill/border/highlight, ink cards, text tiers, speed greens/blues, accent violet).
- `Theme.kt` — Material 3 schemes, `AccentTheme` (Ink/Green/Blue/Violet/System applied to
  `primary`/`onPrimary`; System = `dynamicLight/DarkColorScheme` primary on API 31+, Ink
  below) and `CompositionLocal`s (`LocalGlassColor/Border/Highlight`, `LocalDarkTheme`,
  `LocalReducedMotion`) consumed by the glass components; `isDarkModeActive()` /
  `isReducedMotion()` helpers.
- `components/Glass.kt` — `AppBackground` (ambient glows), `GlassCard`
  (shadow → clip → fill → 1 dp border → top highlight), `GlassChip`.
- Motion: hero digit roll (`animateFloatAsState`), instrument gauge (needle sweep over a
  labelled tick scale, ghost needle for the previous result), phase-label cross-fade +
  animated metric readouts, phase progress bar, breathing "waiting" label, nav pill width
  animation, collapsible Settings groups and History day rows (`AnimatedVisibility` +
  rotating chevron), cross-fade between app stages. *Reduce motion* disables the splash
  zoom/fade, the breathing label and the digit roll.
- Layout: single column on phones, two columns from 600 dp (Dashboard, Test, History),
  text kept in the glass-card rhythm.
- Accessibility: value texts carry `contentDescription`s (never a `mergeDescendants`
  container around test-tagged children), polite live regions on phase/status labels and
  result/error cards, `Role` + `selected` on pickers, `stateDescription` on the pause
  switch and expandable rows, labelled icon buttons, nothing below 10 sp.

## Background / OS compatibility notes

- Android 15+: `dataSync` FGS daily budget → `onTimeout` stops immediately and schedules
  the WorkManager retry (never an inline background restart).
- `NetworkStatsManager` mobile query uses a `null` subscriber id (no `READ_PHONE_STATE`);
  on API ≤ 28 a few ROMs answer `0` — the app shows the system figure, never an estimate.
- Background FGS start is blocked when the app is stopped (API 31+; WorkManager is not an
  exemption, a notification tap is) → watchdog + boot receiver + on-open/on-resume restart;
  a refused start is surfaced as the "Speed meter paused" notification, never swallowed.
- API 35 `dataSync` budget exhaustion makes `startForeground` throw → same paused notice;
  the service stops itself instead of running un-promoted.
- Notification icon: white-on-transparent bitmap (alpha = mask), cached per distinct
  speed string; the whole notification is skipped when nothing visible changed.
- A NetworkCallback may be dropped by some OEM ROMs → the ticker re-resolves the
  transport every 60 reads (30 s on-screen, 2 min screen-off).
- `ACTION_SCREEN_ON/OFF` are protected system broadcasts: registered at runtime with
  `RECEIVER_NOT_EXPORTED`, still delivered. Without the receiver the loop simply keeps the
  on-screen cadence.
- Signing: one stable key for every CI build (see README "Signing & installing updates");
  switching keys always costs the user one uninstall, so the key choice is deliberate.
- minSdk 24 / targetSdk 36; Gradle 9.8.1 wrapper + AGP 9.1.1 + JDK 17+ (CI: 21).

## Testing

- Pure JVM: `SpeedEngineTest` (the measurement rules above, the implausible-delta
  ceiling, the injected clock, bounded history over a long run, the screen-off cadence),
  `SourceHealthTest` (the source-switch rule), `SpeedTestMathTest` (grace-window
  throughput, the acknowledged upload window, adaptive request sizing, `DeltaMath`,
  `UsageCsv`), `LimitAlertsTest` (warn-only thresholds), `SpeedTestCsvTest`,
  `TestServerTest` (endpoint URLs, fallback order, cost estimate),
  `ExampleUnitTest` (formatters, bits/bytes modes, `/proc/net/dev` parser, interface
  classification, gauge scale, jitter, speed-test result serialization), `AccountingTest`
  (baselines, clamping, rollover), `StringResourceParityTest` (en ⇄ hi).
- Robolectric: `SpeedTestViewModelTest` (fake connectivity + fake runner: every rule of the
  active test), `SettingsViewModelTest`, `HistoryDatabaseTest` (Room),
  `DashboardViewModelTest` (state), `PreferencesManagerTest` (DataStore round-trips on an
  isolated store, incl. the pause flag and monthly limit), `NotificationTextTest` (the
  ticker's two lines), `DashboardUiSmokeTest` (Compose render of the hero + usage cards),
  `ScreensRenderTest` (Settings incl. the pause switch writing the flag + idle speed-test
  screen against fakes), `TodayUsageWidgetTest` (RemoteViews carry the real total).
- Instrumented (network-gated, skips without a connection):
  `SpeedTestEngineInstrumentedTest` — real 2 s download, latency probe, upload
  acknowledgement, abort behaviour.
- CI (`.github/workflows/android-build.yml`): on **every push** the unit-test job and the
  non-blocking lint job run and publish condensed check runs ("Unit test log", "Lint
  log") via `.github/scripts/publish_check.py` (which self-tests its parsers first); the
  build/release job runs on manual dispatch only, signs with the stable key and verifies
  the APK signature (see README). Raw Actions logs are not needed to read a failure.

## Out of scope (by design)

- No network calls at all outside the manual speed test; no ads, analytics, billing.
- No per-app breakdown (needs UsageStats permission + heavy UI).
- No server/cloud sync, no accounts, no telemetry (single-device app).
- No status-bar *overlay* (that needs `SYSTEM_ALERT_WINDOW`); the live number lives in
  the notification, exactly like the lightweight meter apps.
