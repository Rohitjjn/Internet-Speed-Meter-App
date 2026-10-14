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

- **Latency + jitter** — 5 round trips on a **kept-alive** connection after a throw-away
  warm-up request, so a "ping" is a round trip and never a TLS handshake.
- **Download** — 4 parallel streams from the selected endpoint for 8 s.
- **Upload** — the server first has to **acknowledge** a fixed 32 KiB POST with a 2xx/3xx
  (an endpoint that only discards bytes, or a captive portal, fails the phase instead of
  producing a fantasy number); then 3 parallel chunked POST streams for 6 s.
- Result = `bits × 8 ÷ seconds`, shown in **Mbps** (the unit ISPs advertise), with the
  last 20 results kept on-device. The divisor is the window that *actually carried data*:
  a short **grace period** (1.5 s download / 1 s upload — TCP slow start and connection
  ramp-up) is excluded when at least one full second remains after it, otherwise the span
  from the first byte is used. Streams are **joined** before the final byte count is read,
  so in-flight bytes are never lost, and the live needle is fed measured intervals too —
  TCP/TLS setup and coroutine scheduling can never deflate or inflate the number.
- The previous result is drawn as a **ghost needle** on the dial and summarised as a
  "↑ 12 % vs last test" chip — both from the stored history, never extrapolated.

Three rules protect the user and the result:

| Rule | Why |
|---|---|
| **Endpoint profiles with fallback** — `AUTO` uses `speed.cloudflare.com`, and retries on the LibreSpeed public backend when that host cannot answer the latency probe (corporate DNS, some ISPs). A custom `http(s)` host can be configured (Cloudflare- or LibreSpeed-style layout) | A blocked host must not make the feature dead — and the result card always names the host that produced the numbers |
| **Metered-data confirmation** — on mobile data, a metered Wi-Fi hotspot or a VPN over either (`ConnectivityManager.isActiveNetworkMetered`) the app asks first with a real estimate (~`line speed × 8 s + 6 s`, floor 5 MB, +10 % overhead) and notes when Data Saver is on | The test really transfers data; a silent 30 MB is not acceptable |
| **Abort on network change** — the phases poll a cooperative abort signal **and** a sentinel disconnects the live sockets the moment it flips, so a Wi-Fi → mobile switch stops the run in well under a second even inside a blocking read | Otherwise the result would mix two connections *and* keep spending mobile data |
| **Fallback only on unreachability** — `AUTO` moves to the second host only when the *latency probe* fails; a failed download/upload is reported as such | Retrying a whole download on another host would just spend more data |

The test runs **only** when you tap the button; it sends no identifiers and stores
nothing but the four numbers you can see. Results can be shared as plain text (no SDK).
Every rule above is unit-tested with a fake connectivity probe and a fake runner
(`SpeedTestViewModelTest`); the real engine maths (`computeThroughput`) is covered by
`SpeedTestMathTest`.

### Daily usage ("Mobile today" / "Wi-Fi today")

- With **Usage access** granted (Settings → Apps → Special access → Usage access): real
  system-wide per-transport counters via
  `NetworkStatsManager.querySummaryForDevice`, minus the "reset today" baseline.
- **Without** the permission: the engine's per-read deltas are attributed to the
  transport that carried them and accumulated — no permission needed.

### Verify it yourself

Settings → **Measurement Engine** (collapsed by default, with a one-line status) shows the
engine's raw inputs: active counter source, read cadence, last window, session samples,
and the kernel interface totals (`wlan0`, `rmnet_data0`, …). Nothing in that panel is
estimated. History → **share** exports the stored days as CSV (raw bytes, no rounding),
through the system share sheet — no file, no storage permission.

> Note on `NetworkStatsManager`: on API ≤ 28 the mobile query runs with a `null`
> subscriber id (the app deliberately has no `READ_PHONE_STATE`) and a few ROMs answer `0`
> for it. With Usage access granted the system counters are authoritative, so such a device
> shows what the system reports — the app never substitutes an estimate. Without the
> permission the daily cards come from the engine's own per-transport attribution instead.

### Background behaviour (Android 15/16)

`dataSync` foreground services get a daily budget. When the system calls `onTimeout`, the
service **stops at once** (it must, or the app is reported as not responding) and hands
the restart to a one-shot WorkManager request (30 min, then exponential back-off
10 → 20 → 40 min if the budget has not rolled over) — an in-process restart from the
background would be refused anyway, and a tight loop would only burn the remaining budget.
A 60-minute WorkManager watchdog (`SpeedKeepAliveWorker`) restarts the service if it was
killed (e.g. after an app update or a device reboot — the boot receiver restarts it too).

### Home-screen widget

**Today's usage** (2×1, resizable) shows today's real total with the mobile / Wi-Fi split
and the time it was rendered. Plain `RemoteViews` (no Glance): the running service pushes
the row it just wrote (at most once a minute, only when a widget is placed), a 30-minute
`WidgetRefreshWorker` covers the case the service is not running, and the launcher's own
update period re-renders it too. Tapping it opens the app.

---

## Features

- ⚡ Live ↓/↑ speed, measured twice a second, with digit-roll animation
- 🧪 **Active speed test**: ping · jitter · download · upload (Mbps), instrument gauge with
  needle + labelled scale, shareable text result, result history
- 🌐 Endpoint choice with automatic fallback (Cloudflare → LibreSpeed) + custom host
- 👥 Per-network split: real bytes attributed to Wi-Fi vs mobile while they happen
- 📊 Session total, session peak, 1-minute average and 30-second sparkline
- 📱 Mobile & Wi-Fi daily usage cards (real counters)
- 🗓 History (all recorded days, newest first) + pinned monthly total, CSV export via the
  share sheet, clear option; two-column on tablets / landscape
- 🏠 Home-screen widget with today's real total (mobile / Wi-Fi split)
- 🎚 Optional daily mobile **warning** limit (MB/GB) — alerts at 50 % / 90 % / 100 %
  with a buzz; the app never blocks data
- 🌗 Glassy light + dark theme (GPT-style glassmorphism) + accent picker
- 🔔 Status-bar notification with live speed + custom speed glyph icon
- 🌐 Fully localizable: **every** user-facing string lives in `values/strings.xml` with a
  Hindi counterpart in `values-hi/` (229 keys, parity enforced by `StringResourceParityTest`)
- ♿ Accessibility: content descriptions on the live numbers, the gauge and every icon
  button; polite live regions for phase/status changes; selection semantics on pickers
- 🧾 Privacy: no `ACCESS_WIFI_STATE` / `READ_PHONE_STATE`; `INTERNET` exists **only**
  for the manual speed test, no analytics, no accounts, everything on-device

---

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)
(2025.x or newer) with SDK 36 (API 36.1) — Android Studio installs it
automatically on first sync.

1. Open the project directory in Android Studio.
2. Let it sync (Gradle 9.8.1 wrapper + AGP 9.1.1, JDK 17+ — CI uses 21).
3. Run the `app` configuration on an emulator or device (min API 24).

> Old AI Studio step (`.env` / `GEMINI_API_KEY`) is no longer needed —
> this build has no API keys and no `.env`.

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

Covers the measurement core without a device: `SpeedEngineTest` (independent directions,
measured windows, suspended-process guard, counter resets, implausible-delta ceiling,
per-transport attribution, injected monotonic clock, bounded history over a 20-minute run),
`SourceHealthTest` (the flap-free source-switch rule), `SpeedTestViewModelTest` (metered
confirmation, AUTO fallback only on unreachability, explicit endpoint never falls back,
abort on transport change, previous result for the ghost needle, STOP → idle),
`SpeedTestMathTest` (grace-window throughput, "vs last" percentages, CSV export),
`TestServerTest` (endpoint URLs, fallback order, data-cost estimate), `SettingsViewModelTest`
(endpoint auto-save round-trips), `ExampleUnitTest` (formatters, bits/bytes modes,
`/proc/net/dev` parser, gauge scale, jitter, result serialization), `AccountingTest` (reset
baselines, clamping, midnight rollover), `PreferencesManagerTest` (DataStore round-trips),
`HistoryDatabaseTest` (Room), `StringResourceParityTest` (English ⇄ Hindi key and
format-argument parity) and `DashboardUiSmokeTest` (Robolectric Compose render).

On a device (network-gated, skips without a connection):

```bash
./gradlew :app:connectedDebugAndroidTest   # SpeedTestEngineInstrumentedTest: download, latency, upload ack, abort
```

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
│   ├── SpeedMeterService.kt   # 500 ms read loop + 15 s accounting + notification + widget push
│   ├── SpeedEngine.kt         # pure measurement maths (unit-tested, no Android)
│   ├── SourceHealth.kt        # pure source-switch rule (TrafficStats ⇄ /proc/net/dev)
│   ├── TrafficCounterReader.kt# TrafficStats / /proc/net/dev + per-interface parser
│   ├── SpeedTestEngine.kt     # ping/download/upload measurement, grace window, upload ack,
│   │                          #   abort signal + socket sentinel, SpeedTestRunner interface
│   ├── TestServer.kt          # endpoint profiles (Cloudflare/LibreSpeed/custom) + cost
│   ├── NetworkMonitor.kt      # Connectivity (transport / metered / Data Saver), injectable
│   └── ServiceScheduler.kt    # WorkManager keep-alive, timeout retry with back-off
├── widget/
│   ├── TodayUsageWidget.kt    # AppWidgetProvider + RemoteViews renderer
│   └── WidgetRefreshWorker.kt # 30-min refresh from the persisted daily row
├── util/
│   ├── Formatting.kt          # SpeedFormatter / BytesFormatter / DateFormatter / DeltaMath
│   └── UsageCsv.kt            # pure CSV builder for the History export
└── ui/
    ├── components/            # AppBackground, GlassCard, GlassChip, AppIcon
    ├── screens/               # Dashboard, Speed Test, History, Settings, Intro, Splash
    ├── theme/                 # glass palettes (light + dark), Material 3
    ├── ../res/values-hi/      # Hindi for every string (parity-tested)
    ├── ../res/layout + xml/   # widget layout + provider info
    ├── util/                  # secondary()/dlColor()/ulColor()/toBytes()
    └── viewmodel/             # Dashboard / SpeedTest / History / Settings VMs
```

Docs: [`docs/database-schema.md`](docs/database-schema.md) ·
[`docs/project-blueprint.md`](docs/project-blueprint.md) ·
[`CHANGELOG.md`](CHANGELOG.md)

---

*Created with [Google AI Studio](https://ai.studio/apps/a5912416-2185-4907-b8ac-ffadd5ddd026)*
