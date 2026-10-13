# Project blueprint — Internet Speed Meter

Target: a **premium, lightweight, real** speed-meter app. Kotlin + Jetpack
Compose + Material 3, no third-party SDKs, everything on-device.

## Product decisions

1. **Truth first** — every number the user sees comes from an OS counter
   (`TrafficStats`, `/proc/net/dev`, `NetworkStatsManager`). No simulated
   traffic, no fabricated splits, no dummy history. If the OS was asleep,
   the meter shows 0, not a fake spike.
2. **Real-time like the best meter apps** — a dedicated 1 Hz foreground
   service that only reads counters and publishes; all slow/expensive work
   (Room, NetworkStatsManager) lives in a separate 15 s loop.
3. **Lightweight & private** — no `INTERNET`, `ACCESS_WIFI_STATE`, or
   `READ_PHONE_STATE` permissions at all. Daily usage works with *zero*
   permissions (per-tick attribution) and upgrades to exact system counters
   when the user grants "Usage access".
4. **No data cap** — the old "add-on data cap" feature is removed entirely.
   The app offers a *warning* limit (alerts at 50/90/100 %) and never
   blocks the network.
5. **Premium look** — glassy-white light theme (GPT-style glassmorphism:
   translucent cards, soft top highlight, ambient glow blobs) with a full
   dark variant; ink (inverse) accent cards for totals; monospace numerals.
6. **Durable background** — `dataSync` FGS with `onTimeout` restart, 60-min
   WorkManager watchdog, boot receiver restart.

## Architecture

```
SpeedMeterService (foreground, dataSync)
├── 1 Hz ticker (Dispatchers.Default, deadline-scheduled)
│     TrafficStats ⇄ /proc/net/dev   (health-checked, auto-switch)
│     → publishSpeed() → StateFlows: speed, 60-sample history, peaks
│     → integrated daily accounting (when no Usage access)
│     → cached status-bar icon + notification
└── 15 s accounting loop
      NetworkStatsManager (when Usage access granted)
      → Room upsert(daily_data_usage) → dashboard/history Flows

MainScreen (Compose)
├── Dashboard  — hero speed card + sparkline + mobile/wifi/total cards
├── History    — last 30 days + pinned monthly total
└── Settings   — theme, warning limit, permissions, data, about

Supporting
├── WorkManager SpeedKeepAliveWorker (60 min) + BootReceiver
└── DataStore  — theme, limit, onboarding, reset baseline, alert state
```

## State flows (single source of truth)

| Flow | Owner | Consumers |
|---|---|---|
| `downloadSpeedFlow`, `uploadSpeedFlow` (B/s) | service | dashboard hero, notification |
| `dlHistoryFlow`, `ulHistoryFlow` (60 samples) | service | sparkline |
| `peakDownloadFlow`, `peakUploadFlow` | service | PEAK chip |
| `todayUsageFlow` | service | dashboard cards, notification |
| `permissionGrantedFlow` | service | settings badge, dashboard hint |
| Room `getDataByDate` / `getHistory` / `getThisMonthTotal` | DB | dashboard, history |
| DataStore flows | PreferencesManager | theme, limit, intro, alerts |

## UI theme system

- `Color.kt` — full light/dark glass palettes (background, 3 glow colors,
  glass fill/border/highlight, ink cards, text tiers, speed greens/blues).
- `Theme.kt` — Material 3 schemes + `CompositionLocal`s
  (`LocalGlassColor/Border/Highlight`, `LocalDarkTheme`) consumed by the
  glass components; `isDarkModeActive()` helper.
- `components/Glass.kt` — `AppBackground` (ambient glows), `GlassCard`
  (shadow → clip → fill → 1 dp border → 26 dp top highlight), `GlassChip`.
- Inverse-ink cards (`TotalCard`, history monthly summary) for the
  "premium object on glass" contrast.

## Background/OS compatibility notes

- Android 15+: `dataSync` FGS daily budget → `onTimeout` restart strategy.
- Background FGS start is blocked when app is stopped → watchdog + boot
  receiver + on-open restart; failures are caught and logged, never crash.
- Notification icon: white-on-transparent bitmap (alpha = mask), cached per
  distinct speed string so the 1 Hz loop stays allocation-free in steady state.
- minSdk 24 / targetSdk 36; Gradle 9.8.1 + AGP 9.1.1 + JDK 17.

## Testing

- Pure JVM: formatters + `/proc/net/dev` parser (`ExampleUnitTest`).
- Robolectric: Room schema/queries (`HistoryDatabaseTest`), viewmodel
  state (reset dialog flow) (`DashboardViewModelTest`).
- CI: `.github/workflows/android-build.yml` — unit-test job + manual
  build/release job (see README).

## Out of scope (by design)

- No network calls, no ads/analytics, no Play billing, no "add-on data cap".
- No per-app breakdown (needs UsageStats permission + heavy UI).
- No server/cloud sync (single-device app).
