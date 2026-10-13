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
     → ~1 Hz publish.
   - *Active speed test* ("what can my line actually do?"): opt-in, tapped, real
     download/upload/latency measurement — the only honest way to answer the second
     question, since an idle phone legitimately shows 0.
3. **Lightweight & private** — no `ACCESS_WIFI_STATE`, no `READ_PHONE_STATE`, no
   analytics, no accounts. `INTERNET` exists **only** for the manual speed test.
   Daily usage works with *zero* permissions (per-read attribution) and upgrades to exact
   system counters when the user grants "Usage access".
4. **No data cap** — there is no "add-on data cap / freeze" feature. The app offers a
   *warning* limit (alerts at 50/90/100 %) and never blocks the network.
5. **Premium look** — glassy-white light theme (GPT-style glassmorphism: translucent
   cards, soft top highlight, ambient glow blobs) with a full dark variant, an accent
   picker, ink (inverse) accent cards, monospace numerals and animated digits.
6. **Durable background** — `dataSync` FGS with `onTimeout` restart, 60-minute
   WorkManager watchdog, boot receiver restart.

## Architecture

```
MainActivity (Splash → Intro → Main)
└── MainScreen — floating glass-pill nav
    ├── Dashboard  — hero live card + Mobile/Wi-Fi/Today cards
    ├── Speed Test — active measurement (gauge, metrics, history)
    ├── History    — recorded days + pinned monthly total
    └── Settings   — appearance, unit, accent, limits, permissions, engine diagnostics

SpeedMeterService (foreground, dataSync)
├── read loop — 500 ms, Dispatchers.Default
│     TrafficCounterReader: TrafficStats  ⇄  /proc/net/dev   (per-source anchors +
│     health probe; switch only when the other source is genuinely alive)
│     → SpeedEngine (pure maths) → LiveSpeed every ~1 s
│     → StateFlows: raw dl/ul, steady (EWMA), 1-min average, peaks, history,
│       session bytes, per-transport rates, transport
│     → status-bar notification (rebuilt only when visible text changes)
├── ConnectivityManager.NetworkCallback → transport push (+30 s self-heal)
└── accounting loop — 15 s, Dispatchers.IO
      Usage access ? NetworkStatsManager since midnight − reset baseline
                   : engine per-transport attribution deltas
      → Room upsert(daily_data_usage)

SpeedTestEngine (opt-in, Dispatchers.IO)
├── latency: 5 round trips after a warm-up request → min / average / jitter
├── download: 4 parallel streams, 8 s budget, bytes actually received
└── upload: 3 parallel chunked POST streams, 6 s budget, bytes accepted by the socket
   → SpeedTestViewModel → SpeedTestScreen (+ last 20 results in DataStore)

Supporting
├── WorkManager SpeedKeepAliveWorker (60 min) + BootCompletedReceiver
└── DataStore — theme, accent, speed unit, limit, onboarding, reset baseline,
                alert dismissal, last known speed, speed-test results
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
| Transport is pushed by `NetworkCallback`, re-resolved every 30 s as a fallback | Instant, correct attribution without polling in the hot path |
| Per-transport rates are zeroed on a transport change | Wi-Fi speed must not "stick" after switching to mobile data |

## State flows (single source of truth)

| Flow | Owner | Consumers |
|---|---|---|
| `liveSpeedFlow` (`LiveSpeed`) | service | dashboard hero, settings diagnostics — the *only* speed surface (the old per-field mirror flows were removed so there is exactly one source of truth) |
| `networkTypeFlow` | service | network chip, notification, diagnostics |
| `engineStatusFlow` (`EngineStatus`) | service | Settings → Measurement Engine |
| `todayUsageFlow` | service | notification |
| `permissionGrantedFlow` | service | settings badge, dashboard hint |
| `SpeedTestViewModel.state` / `.history` | viewmodel | Speed Test screen |
| Room `getDataByDate` / `getAllHistory` / `getThisMonthTotal` | DB | dashboard, history |
| DataStore flows | PreferencesManager | theme, accent, unit, limit, intro, alerts, last speed |

## UI theme system

- `Color.kt` — light/dark glass palettes (background, 3 glow colours, glass
  fill/border/highlight, ink cards, text tiers, speed greens/blues, accent violet).
- `Theme.kt` — Material 3 schemes, `AccentTheme` (Ink/Green/Blue/Violet applied to
  `primary`/`onPrimary`) and `CompositionLocal`s
  (`LocalGlassColor/Border/Highlight`, `LocalDarkTheme`) consumed by the glass
  components; `isDarkModeActive()` helper.
- `components/Glass.kt` — `AppBackground` (ambient glows), `GlassCard`
  (shadow → clip → fill → 1 dp border → top highlight), `GlassChip`.
- Motion: hero digit roll (`animateFloatAsState`), speed-test gauge sweep, nav pill
  width animation, cross-fade between app stages.

## Background / OS compatibility notes

- Android 15+: `dataSync` FGS daily budget → `onTimeout` restart strategy.
- Background FGS start is blocked when the app is stopped → watchdog + boot receiver +
  on-open restart; failures are caught and logged, never crash.
- Notification icon: white-on-transparent bitmap (alpha = mask), cached per distinct
  speed string; the whole notification is skipped when nothing visible changed.
- A NetworkCallback may be dropped by some OEM ROMs → the ticker re-resolves the
  transport every 30 s.
- minSdk 24 / targetSdk 36; Gradle 9.8.1 wrapper + AGP 9.1.1 + JDK 17.

## Testing

- Pure JVM: `SpeedEngineTest` (the measurement rules above), `ExampleUnitTest`
  (formatters, bits/bytes modes, `/proc/net/dev` parser, interface classification,
  jitter, speed-test result serialization), `AccountingTest` (baselines, clamping,
  rollover).
- Robolectric: `HistoryDatabaseTest` (Room), `DashboardViewModelTest` (state),
  `DashboardUiSmokeTest` (Compose render of the hero + usage cards).
- CI (`.github/workflows/android-build.yml`): unit-test job, non-blocking lint job,
  manual build/release job (see README).

## Out of scope (by design)

- No network calls at all outside the manual speed test; no ads, analytics, billing.
- No per-app breakdown (needs UsageStats permission + heavy UI).
- No server/cloud sync, no accounts, no telemetry (single-device app).
- No status-bar *overlay* (that needs `SYSTEM_ALERT_WINDOW`); the live number lives in
  the notification, exactly like the lightweight meter apps.
