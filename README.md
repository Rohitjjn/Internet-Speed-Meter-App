<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Internet Speed Meter — Android

A lightweight, real-time internet speed meter for Android, with per-second
live speeds (Wi-Fi and mobile), daily/monthly data usage history, and a
glassy "GPT-style" UI in light and dark mode.

Built with **Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · DataStore**.
No third-party SDKs, no ads, no data leave the device.

---

## Real-time speed — how it actually works

Two independent engines, both reporting **real measurements only**:

### 1. Passive live meter (always on)

`services/SpeedMeterService.kt` runs a foreground service whose read loop samples the
device counters **twice per second** and publishes **once per second**:

| Step | What happens |
|---|---|
| read (500 ms) | `TrafficStats.getTotalRxBytes()/getTotalTxBytes()` — or `/proc/net/dev` when a ROM restricts them |
| maths | `SpeedEngine` (pure Kotlin, fully unit-tested) turns the `(rx, tx)` deltas into bytes/second |
| publish (~1 s) | `LiveSpeed` → dashboard, notification, diagnostics panel |

Rules the engine guarantees (all covered by tests in `SpeedEngineTest`):

- **Download and upload are truly independent.** Both always come from *one coherent
  `(rx, tx)` read of one source*, computed as separate deltas — a download-heavy burst
  can never be mirrored into the upload number (this was a real reported bug).
- **The window is measured, never assumed.** Rate = bytes ÷ *actual* elapsed ms, so a
  delayed tick produces a correct rate instead of a spike.
- **No invented values.** A counter that goes *backwards* (interface reset, source
  switch, reboot) re-anchors silently; a window longer than 3 s (process frozen by the
  OS) publishes `0` and flags `PAUSED` in the UI instead of an averaged fake — though the
  real bytes are still counted into the session totals.
- **Source health without flapping.** Each source keeps its own anchor and is only
  switched to when it is genuinely alive, so the old "compare absolute totals across two
  different counters" bug (which flapped every few seconds) cannot happen.
- **Transport is pushed, not polled.** A `ConnectivityManager.NetworkCallback` reports
  Wi-Fi/mobile/Ethernet/VPN changes; the hot path does zero binder calls.
- **Session + rolling stats**: session rx/tx, session duration, peaks, a 60 s average and
  a 60-sample sparkline history.

### 2. Active speed test (opt-in, one tap)

The passive meter shows what the device is *using* (near 0 on an idle phone), which can
never tell you the *capacity* of your line. Dashboard → **Test → START TEST** measures it
for real over the network (plain `HttpURLConnection`, no third-party SDK):

- **Latency + jitter** — 5 round trips after a throw-away warm-up request.
- **Download** — 4 parallel streams from Cloudflare's public test endpoint for 8 s.
- **Upload** — 3 parallel chunked POST streams for 6 s.
- Result = `bits × 8 ÷ seconds`, shown in **Mbps** (the unit ISPs advertise), with the
  last 20 results kept on-device.

The test runs **only** when you tap the button; it sends no identifiers and stores
nothing but the four numbers you can see.

### Daily usage ("Mobile today" / "Wi-Fi today")

- With **Usage access** granted (Settings → Apps → Special access → Usage access): real
  system-wide per-transport counters via
  `NetworkStatsManager.querySummaryForDevice`, minus the "reset today" baseline.
- **Without** the permission: the engine's per-read deltas are attributed to the
  transport that carried them and accumulated — no permission needed.

### Verify it yourself

Settings → **Measurement Engine** shows the engine's raw inputs: active counter source,
read cadence, last window, session samples, and the kernel interface totals
(`wlan0`, `rmnet_data0`, …). Nothing in that panel is estimated.

### Background behaviour (Android 15/16)

`dataSync` foreground services get a daily budget. When the system calls
`onTimeout`, the service restarts itself to get a fresh budget and stops
cleanly if the OS refuses. A 60-minute WorkManager watchdog
(`SpeedKeepAliveWorker`) restarts the service if it was killed (e.g. after
app update / device reboot — the boot receiver restarts it too).

---

## Features

- ⚡ Live ↓/↑ speed, measured twice a second, with digit-roll animation
- 🧪 **Active speed test**: ping · jitter · download · upload (Mbps) + result history
- 👥 Per-network split: real bytes attributed to Wi-Fi vs mobile while they happen
- 📊 Session total, session peak, 1-minute average and 30-second sparkline
- 📱 Mobile & Wi-Fi daily usage cards (real counters)
- 🗓 History (all recorded days, newest first) + pinned monthly total, clear option
- 🎚 Optional daily mobile **warning** limit (MB/GB) — alerts at 50 % / 90 % / 100 %
  with a buzz; the app never blocks data
- 🌗 Glassy light + dark theme (GPT-style glassmorphism) + accent picker
- 🔔 Status-bar notification with live speed + custom speed glyph icon
- 🧾 Privacy: no `ACCESS_WIFI_STATE` / `READ_PHONE_STATE`; `INTERNET` exists **only**
  for the manual speed test, no analytics, no accounts, everything on-device

---

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)
(2025.x or newer) with SDK 36 (API 36.1) — Android Studio installs it
automatically on first sync.

1. Open the project directory in Android Studio.
2. Let it sync (Gradle 9.8.1 wrapper + AGP 9.1.1, JDK 17).
3. Run the `app` configuration on an emulator or device (min API 24).

> Old AI Studio step (`.env` / `GEMINI_API_KEY`) is no longer needed —
> this build has no API keys and no `.env`.

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

Covers the measurement core without a device: `SpeedEngineTest` (independent
directions, measured windows, suspended-process guard, counter resets, per-transport
attribution), `ExampleUnitTest` (formatters, bits/bytes modes, `/proc/net/dev` parser,
jitter, result serialization), `AccountingTest` (reset baselines, clamping, midnight
rollover), `HistoryDatabaseTest` (Room) and `DashboardUiSmokeTest` (Robolectric Compose
render).

---

## Build & release (GitHub Actions)

`.github/workflows/android-build.yml` is a **manual workflow**
(*Actions → Android Build & Release → Run workflow*) with these inputs:

| Input | Meaning |
|---|---|
| `build_type` | `release` (signed, minified) or `debug` |
| `version_name` | e.g. `1.1.0` |
| `version_code` | integer, e.g. `2` |
| `tag` | release tag (blank → `v<version_name>`) |
| `release_title` | blank → `v<version_name>` |
| `release_notes` | Markdown body for the release |
| `create_release` | `yes` → create/update the GitHub release with the APK |

The workflow runs the unit tests, builds the APK, uploads it as a build
artifact **and** (optionally) to a GitHub Release. Re-running with the same
tag **overwrites** the APK asset in place.

### Release signing

Two options:

1. **Upload your key** (recommended): add these *repository secrets*
   - `KEYSTORE_BASE64` — base64 of your `.jks` file
     (`base64 -w0 my-upload-key.jks`)
   - `STORE_PASSWORD`, `KEY_ALIAS` (default `upload`), `KEY_PASSWORD`
2. **Throwaway key**: if `KEYSTORE_BASE64` is missing, CI generates a
   self-signed key for that build (so the workflow never dead-ends). Note
   each throwaway key is different — apps signed with it won't update each
   other.

---

## Project layout

```
app/src/main/java/com/example/
├── MainActivity.kt            # Splash → Intro → Main, theme override
├── data/
│   ├── AppDatabase.kt         # Room (schema v3)
│   ├── DataUsageDao.kt        # per-day rows, month totals
│   ├── DataUsageEntity.kt     # mobile/wifi rx+tx per day
│   ├── DataUsageRepository.kt
│   ├── NetworkStatsHelper.kt  # per-transport counters since midnight
│   ├── PreferencesManager.kt  # DataStore: theme, accent, unit, limit, baselines,
│   │                          #   last speed, speed-test results
│   └── UsageResetter.kt       # single reset path (service path uses its own)
├── services/
│   ├── SpeedMeterService.kt   # 500 ms read loop + 15 s accounting + notification
│   ├── SpeedEngine.kt         # pure measurement maths (unit-tested, no Android)
│   ├── TrafficCounterReader.kt# TrafficStats / /proc/net/dev + per-interface parser
│   ├── SpeedTestEngine.kt     # active ping/download/upload measurement
│   └── ServiceScheduler.kt    # WorkManager keep-alive + boot handling
└── ui/
    ├── components/            # AppBackground, GlassCard, GlassChip, AppIcon
    ├── screens/               # Dashboard, Speed Test, History, Settings, Intro, Splash
    ├── theme/                 # glass palettes (light + dark), Material 3
    ├── util/                  # secondary()/dlColor()/ulColor()/toBytes()
    └── viewmodel/             # Dashboard / SpeedTest / History / Settings VMs
```

Docs: [`docs/database-schema.md`](docs/database-schema.md) ·
[`docs/project-blueprint.md`](docs/project-blueprint.md) ·
[`CHANGELOG.md`](CHANGELOG.md)

---

*Created with [Google AI Studio](https://ai.studio/apps/a5912416-2185-4907-b8ac-ffadd5ddd026)*
