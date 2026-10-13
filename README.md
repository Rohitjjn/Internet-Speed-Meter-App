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
- **Displayed digits are rounded, not chopped**: `512.9 KB/s` shows as `513 KB/s`, and
  hero values print one decimal with a pointless `.0` trimmed (`104.9 Mbps`, `256 MB/s`).

### 2. Active speed test (opt-in, one tap)

The passive meter shows what the device is *using* (near 0 on an idle phone), which can
never tell you the *capacity* of your line. Dashboard → **Test → START TEST** measures it
for real over the network (plain `HttpURLConnection`, no third-party SDK):

- **Latency + jitter** — 5 round trips after a throw-away warm-up request.
- **Download** — parallel streams for 8 s (2 to start, ramped up only on a proven-fast
  line), counting the bytes that actually arrive.
- **Upload** — parallel chunked POST streams for 6 s, counting the bytes the socket
  accepted and then checking the server actually took them.
- Result = `bits × 8 ÷ seconds`, shown in **Mbps** (the unit ISPs advertise), with the
  last 20 results kept on-device. The divisor is the window that *actually carried data*
  (first byte → deadline) and the live gauge is fed measured intervals too, so TCP/TLS
  setup and coroutine scheduling can never deflate or inflate the number.

**It is built to survive a hostile network.** `speed.cloudflare.com` is blocked by some
corporate DNS servers and a few ISPs, so the test probes a candidate list (Cloudflare →
Hetzner → OVH) and fails over to the first endpoint that answers a round trip. Settings →
**Speed Test Server** pins a preference. Because some fallback endpoints can only serve
downloads, the result records the **download server and the upload server separately**
and the UI shows both — when nothing reachable can take an upload, upload is reported as
*not measured* instead of being invented.

Before running on a metered (mobile-data) connection it asks first: a real throughput
test has to move real bytes, and the exact amount it used is shown afterwards. It refuses
to start when the device reports no connectivity, and it **stops the moment the transport
changes** — a Wi-Fi → mobile switch would otherwise bill your plan for the rest of a
download burst.

The test runs **only** when you tap the button; it sends no identifiers and stores
nothing but the numbers you can see.

### Daily usage ("Mobile today" / "Wi-Fi today")

- With **Usage access** granted (Settings → Apps → Special access → Usage access): real
  system-wide per-transport counters via
  `NetworkStatsManager.querySummaryForDevice`, minus the "reset today" baseline.
- **Without** the permission: the engine's per-read deltas are attributed to the
  transport that carried them and accumulated — no permission needed. The engine owns the
  "already handed over" watermark (`drainAttributed()`), so a re-seed in between
  contributes nothing instead of a negative (and therefore spiky) delta.

### Verify it yourself

Settings → **Measurement Engine** shows the engine's raw inputs: active counter source,
read cadence, last window, session samples, and the kernel interface totals
(`wlan0`, `rmnet_data0`, …). Nothing in that panel is estimated.

### Background behaviour (Android 15/16)

`dataSync` foreground services get a daily budget. When the system calls
`onTimeout`, the service schedules a restart **with an exponential backoff**
(5 s → 120 s, at most 6 attempts) instead of hammering the OS with instant
restarts, gives up cleanly after that, and lets the 60-minute WorkManager
watchdog (`SpeedKeepAliveWorker`) take over. The watchdog also restarts the
service if it was killed (app update / device reboot — the boot receiver
restarts it too).

---

## Features

- ⚡ Live ↓/↑ speed, measured twice a second, with digit-roll animation
- 🧪 **Active speed test**: ping · jitter · download · upload (Mbps) + result history,
  multi-server failover, mobile-data warning, shareable result
- 👥 Per-network split: real bytes attributed to Wi-Fi vs mobile while they happen
- 📊 Session total, session peak, 1-minute average and 30-second sparkline
- 🌐 **English + हिन्दी** — every string is an Android resource, so a new language is a
  new `values-xx/strings.xml` and nothing else
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
attribution, drain-once accounting, implausible-counter guard), `SourceHealthTest`
(counter-source switching), `SpeedTestEngineTest` (server failover, gauge scale, result
round-trip), `ExampleUnitTest` (formatters, bits/bytes modes, `/proc/net/dev` parser,
jitter, result serialization), `AccountingTest` (reset baselines, clamping, midnight
rollover), `HistoryDatabaseTest` (Room), `PreferencesManagerTest` (DataStore round-trip
for every setting, under Robolectric) and `DashboardUiSmokeTest` (Robolectric Compose
render).

The instrumented suite (`./gradlew :app:connectedAndroidTest`) adds
`SpeedTestSmokeTest`, a **network-gated** real 2-second download against the default
endpoint — it is skipped (not failed) when the device is offline.

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
│   ├── SpeedTestEngine.kt     # active ping/download/upload (multi-server, abortable)
│   ├── SourceHealth.kt        # pure counter-source switch decision (unit-tested)
│   └── ServiceScheduler.kt    # WorkManager keep-alive + boot handling
└── ui/
    ├── components/            # AppBackground, GlassCard, GlassChip, AppIcon
    ├── res/values/            # strings.xml (+ values-hi) — all user-visible copy
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
