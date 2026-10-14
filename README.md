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
device counters **twice per second** while the screen is on (every **2 s** while it is
off — nobody can see the ticker then, and the maths divides by the measured window, so
only the battery cost changes) and publishes **once per second**:

| Step | What happens |
|---|---|
| read (500 ms on-screen / 2 s screen-off) | `TrafficStats.getTotalRxBytes()/getTotalTxBytes()` — or `/proc/net/dev` when a ROM restricts them |
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
- **Download** — 4 parallel streams from the selected endpoint for 8 s. Each stream asks
  for 20 MB and **doubles its next request (up to 100 MB) whenever the previous body
  finished in under a second**, reusing the kept-alive connection — so a gigabit line is
  never throttled by request setup, while a slow link keeps spending small bodies.
- **Upload** — the server first has to **acknowledge** a fixed 32 KiB POST with a 2xx/3xx
  (an endpoint that only discards bytes, or a captive portal, fails the phase instead of
  producing a fantasy number); then 3 parallel chunked POST streams for 6 s. At the
  deadline each stream ends its body and waits (≤ 3 s) for the server's response — the
  proof that everything, including what the kernel's **send buffer** still held, has
  arrived. When every stream is answered the window closes at the **last
  acknowledgement**, so buffered bytes are timed until they really landed instead of being
  counted as "sent by the deadline" (the correction matters most on slow uplinks). If a
  stream stays silent the phase falls back to the send-side window and the result card
  says so ("Upload window: send-side").
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
estimated. History → **share** exports the stored days as a real `.csv` attachment (raw
bytes, no rounding) through the system share sheet, and the speed-test history has its
own **EXPORT** (bytes/s *and* Mbps, UTC timestamps). Both files are written to the app's
private cache and handed over through a `FileProvider` — no storage permission. Tapping a
day in History opens its drill-down: download and upload per network, straight from the
four stored counters.

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

**A refused restart is never silent.** Since Android 12 a foreground service may not be
started from the background (WorkManager is *not* an exemption), and on Android 15 the
`dataSync` budget can be exhausted. Whenever a start is refused — by the timeout, by the
watchdog, by the boot receiver or by `startForeground` itself — the app posts a low-priority
**"Speed meter paused"** notification that says plainly that nothing is being counted and
that a tap resumes the meter (a notification tap, like opening the app, *is* allowed to start
the service). The notification is cleared automatically the moment the meter is running
again, and opening the app re-starts the meter on every resume.

**Pausing it yourself is a different state.** The pause button in the dashboard header
(and the *Pause meter* switch in Settings) stops the service and stores
`meter_paused_by_user`; `services/MeterControl.kt` is the single place every restart path
asks — `MainActivity.onResume`, the boot receiver, the watchdog worker, the timeout retry,
even a stale sticky restart — so a meter you paused stays paused across reboots until you
resume it. The UI then says **"paused by you"** (hero label, Settings engine status, Quick
Settings tile), nothing is measured or counted, no "meter paused" notice is posted, and
today's saved total stays visible.

### Home-screen widget

**Today's usage** (2×1, resizable) shows today's real total with the mobile / Wi-Fi split
and the time it was rendered. Plain `RemoteViews` (no Glance): the running service pushes
the row it just wrote (at most once a minute, only when a widget is placed), a 30-minute
`WidgetRefreshWorker` covers the case the service is not running, and the launcher's own
update period re-renders it too. Tapping it opens the app.

### Quick Settings tile

**Speed Meter** can be added to the notification shade. It is a launcher, not a live
meter: its state mirrors whether the meter is running and its subtitle (Android 10+) is
today's **accounting snapshot** — the running service's same-day row, otherwise the
persisted row from OS counters — or "Meter paused" / "Paused by you". It never shows a
speed, because a tile cannot follow one without keeping the process awake.

---

## Display fixes (loop 7)

The speed icon is a **white alpha mask on a transparent background**, tinted by Android.
It is cached until the displayed value/unit changes; a published bitmap is never redrawn.
This fixes the accumulated-glyph "box" caused by clearing rotating buffers with transparent
`SRC_OVER` (which does not erase pixels). Values and units are fitted independently, including
`104.9`, `MB/s` and `Mbps`.

The app's dark-mode override now updates both system bars immediately, even when the phone
uses the opposite theme. The notification **shade itself is owned by Android**: the app
uses the standard notification template, not a hard-coded dark custom layout. If an OEM
hides notification icons or notification permission is disabled, the app cannot override
that system setting. Main-screen content respects clock/cutout and keyboard insets.

Reduce motion observes the OS animator-duration setting while the UI is open. The Settings
switch shows when the system forces it on, without overwriting your saved preference.
Splash/digits, gauge/metrics, collapsible cards, stage/page fades, intro dots and nav pills
honour it; actual sampling and speed-test timings never change.

Repeated Start taps cannot create multiple metered confirmation lookups or reopen a dismissed
one. On the next CSV export, app-owned CSV files older than 24 hours are removed from the
private export cache; recent shared files and unrelated files are left alone.

## Features

- ⚡ Live ↓/↑ speed, measured twice a second, with digit-roll animation
- 🧪 **Active speed test**: ping · jitter · download · upload (Mbps), instrument gauge with
  needle + labelled scale, shareable text result, result history
- 🌐 Endpoint choice with automatic fallback (Cloudflare → LibreSpeed) + custom host
- 👥 Per-network split: real bytes attributed to Wi-Fi vs mobile while they happen
- 📊 Session total, session peak, 1-minute average and 30-second sparkline
- 📱 Mobile & Wi-Fi daily usage cards (real counters)
- 🗓 History (all recorded days, newest first) with a per-day drill-down (↓/↑ per
  network) + pinned monthly total, `.csv` export via the share sheet, clear option;
  two-column on tablets / landscape
- 🏠 Home-screen widget with today's real total (mobile / Wi-Fi split) + Quick Settings tile
- ⏯ **Pause / resume the meter yourself** — a durable switch no restart path overrides;
  the UI always says whether a pause is yours or the OS's
- 🎚 Optional daily **and monthly** mobile **warning** limits (MB/GB) — one alert each at
  50 % / 90 % / 100 % with a buzz; the app never blocks data
- 🌗 Glassy light + dark theme (GPT-style glassmorphism) + accent picker, including a
  **wallpaper (Material You) accent** on Android 12+, a soft white rounded glow in dark
  mode, and **reduce motion** (also honours the phone's Remove animations setting)
- 🔔 Status-bar notification with live speed + transparent, fitted number/unit glyphs;
  clock, battery and navigation icons follow the app's effective light/dark theme
- 🌐 Fully localizable: **every** user-facing string lives in `values/strings.xml` with a
  Hindi counterpart in `values-hi/` (268 keys, parity enforced by `StringResourceParityTest`)
- ♿ Accessibility: content descriptions on the live numbers, the gauge and every icon
  button; polite live regions for phase/status changes; selection and state semantics on
  pickers and switches ("paused by you" / "running", expanded / collapsed); no text below
  10 sp; one haptic tick when a test result lands
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
`SpeedTestMathTest` (grace-window throughput, adaptive request sizing, "vs last"
percentages, CSV export),
`TestServerTest` (endpoint URLs, fallback order, data-cost estimate), `SettingsViewModelTest`
(endpoint auto-save round-trips), `ExampleUnitTest` (formatters, bits/bytes modes,
`/proc/net/dev` parser, gauge scale, jitter, result serialization), `AccountingTest` (reset
baselines, clamping, midnight rollover), `PreferencesManagerTest` (DataStore round-trips),
`HistoryDatabaseTest` (Room), `StringResourceParityTest` (English ⇄ Hindi key and
format-argument parity), `TodayUsageWidgetTest` (the widget's `RemoteViews` carry the
real total) and the Robolectric Compose renders `DashboardUiSmokeTest` + `ScreensRenderTest`
(Settings and the idle speed-test screen against fakes).

Tests never touch the app's real DataStore: `PreferencesManager` takes its `DataStore`
as a constructor parameter and `TestPreferences` hands each test an isolated file.

On a device (network-gated, skips without a connection):

```bash
./gradlew :app:connectedDebugAndroidTest   # SpeedTestEngineInstrumentedTest: download, latency, upload ack, abort
```

---

## Build & release (GitHub Actions)

`.github/workflows/android-build.yml` does two things:

- **On every push** (any branch; Markdown/docs-only pushes are skipped) it compiles the
  app and runs the unit tests plus a non-blocking lint pass. Both publish their condensed
  output as **check runs** ("Unit test log", "Lint log" — compiler errors, failing tests,
  lint warnings) with inline annotations, so a failure is readable from the commit's
  checks tab without downloading the raw log (`.github/scripts/publish_check.py`, stdlib
  only).
- **Manually** (*Actions → Android Build & Release → Run workflow*) it additionally builds
  and optionally releases the APK, with these inputs:

| Input | Meaning |
|---|---|
| `build_type` | `release` (signed, minified) or `debug` |
| `version_name` | e.g. `1.1.0` |
| `version_code` | integer, e.g. `2`; **blank → the workflow run number**, so every later build is an update of the previous one |
| `tag` | release tag (blank → `v<version_name>`) |
| `release_title` | blank → `v<version_name>` |
| `release_notes` | Markdown body for the release |
| `create_release` | `yes` → create/update the GitHub release with the APK |

The manual run executes the unit tests, builds the APK, verifies its signature
(`apksigner`, printed in the job summary), uploads it as a build artifact **and**
(optionally) to a GitHub Release. Re-running with the same tag **overwrites** the APK
asset in place.

### Signing & installing updates

Android only installs an APK **over** an existing one when both carry the same signing
certificate. Every CI build — `debug` and `release`, from any branch — is therefore signed
with **one stable key**, in this order of precedence:

1. `KEYSTORE_PATH` / `KEYSTORE_BASE64` — your own key (see below);
2. `my-upload-key.jks` in the repository root (ignored by Git, for local builds);
3. **`keystore/ci-shared.p12`**, a self-signed key committed to the repository
   (alias `upload`, store and key password `android`; SHA-256
   `75:87:DA:90:08:98:65:F6:CF:E3:A8:51:D8:88:04:BB:6F:05:10:97:A3:3E:DB:03:27:76:AA:7C:43:77:2E:26`).

So a build downloaded from Actions or from a Release **updates** the previous one in place
— history, settings and today's totals survive. Two things to know:

- **One-time uninstall.** Builds made *before* this change were signed with a throwaway
  key (a fresh one per run — that is why "App not installed as package conflicts with an
  existing package" appeared). Export your history first (History → share), uninstall the
  old build once, install the new one; from then on every build updates in place.
- **The committed key is a convenience, not a secret.** Anyone can sign an APK with it, so
  treat it as a developer/CI key: it is fine for side-loaded builds you download yourself,
  not for a store listing. To switch to a private key, add the repository secrets
  `KEYSTORE_BASE64` (`base64 -w0 my-upload-key.jks`), `STORE_PASSWORD`, `KEY_ALIAS`
  (default `upload`) and `KEY_PASSWORD`; that switch costs one more uninstall, after which
  the private key is the stable one. The **Prepare signing key** step in every build prints
  which key was used.

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
│   ├── PreferencesManager.kt  # DataStore: theme, accent, reduced motion, unit, daily +
│   │                          #   monthly limits, baselines, last speed, speed-test results,
│   │                          #   meter_paused_by_user
│   └── UsageResetter.kt       # single reset path (service path uses its own)
├── services/
│   ├── SpeedMeterService.kt   # 500 ms read loop (2 s screen-off) + 15 s accounting +
│   │                          #   notification + widget push, "meter paused" notice when a
│   │                          #   foreground start is refused
│   ├── MeterControl.kt        # the user's pause switch — every restart path asks it first
│   ├── NotificationText.kt    # pure builder of the ticker's two lines (unit-tested)
│   ├── SpeedEngine.kt         # pure measurement maths (unit-tested, no Android)
│   ├── SourceHealth.kt        # pure source-switch rule (TrafficStats ⇄ /proc/net/dev)
│   ├── TrafficCounterReader.kt# TrafficStats / /proc/net/dev + per-interface parser
│   ├── SpeedTestEngine.kt     # ping/download/upload measurement, grace window, upload ack
│   │                          #   + acknowledged upload window (send-buffer correction),
│   │                          #   abort signal + socket sentinel, SpeedTestRunner interface
│   ├── TestServer.kt          # endpoint profiles (Cloudflare/LibreSpeed/custom) + cost
│   ├── NetworkMonitor.kt      # Connectivity (transport / metered / Data Saver) + pushed
│   │                          #   transportChanges() flow (NetworkCallback), injectable
│   └── ServiceScheduler.kt    # WorkManager keep-alive, timeout retry with back-off
├── widget/
│   ├── TodayUsageWidget.kt    # AppWidgetProvider + RemoteViews renderer
│   └── WidgetRefreshWorker.kt # 30-min refresh from the persisted daily row
├── tile/
│   └── SpeedMeterTile.kt      # Quick Settings tile (state = meter running, subtitle = today)
├── util/
│   ├── Formatting.kt          # SpeedFormatter / BytesFormatter / DateFormatter / DeltaMath
│   ├── LimitAlerts.kt         # pure warn-only limit rule (daily + monthly)
│   ├── UsageCsv.kt            # pure CSV builder for the History export
│   ├── SpeedTestCsv.kt        # pure CSV builder for the speed-test history export
│   └── CsvExport.kt           # FileProvider share of a .csv (text fallback)
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
