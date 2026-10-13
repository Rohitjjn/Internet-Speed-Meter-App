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

The engine (`app/src/main/java/com/example/services/SpeedMeterService.kt`) is a
foreground service with a lean 1 Hz ticker:

- **Primary counter**: `TrafficStats` (total device traffic).
- **Fallback counter**: `/proc/net/dev` (kernel totals). A health check
  auto-switches between the two if a source goes stale (0/0 on cold start,
  or ≥5 stagnant ticks) — this is why the app keeps reporting on OEM ROMs
  where `TrafficStats` totals are restricted.
- **Window guard**: a delta over a >4 s window means the service was
  suspended (Doze); the app publishes `0` for that tick instead of a fake
  spike — exactly like dedicated speed apps.
- **Per-second history**: the last 60 seconds of download/upload samples are
  kept in ring buffers (`dlHistoryFlow` / `ulHistoryFlow`) and rendered as a
  live sparkline.
- **Daily usage accounting runs on a separate slow (15 s) loop** so the hot
  1 Hz path never touches Room or `NetworkStatsManager`.

### Daily usage ("Mobile today" / "Wi-Fi today")

- With **Usage access** granted (Settings → Apps → Special access → Usage
  access): real system-wide per-transport counters via
  `NetworkStatsManager.querySummaryForDevice`, minus the "reset today"
  baseline.
- **Without** the permission: every 1 s tick's bytes are attributed to the
  active transport (Wi-Fi / mobile) and accumulated — no permission, no
  `READ_PHONE_STATE`, no `INTERNET` permission at all.

### Background behaviour (Android 15/16)

`dataSync` foreground services get a daily budget. When the system calls
`onTimeout`, the service restarts itself to get a fresh budget and stops
cleanly if the OS refuses. A 60-minute WorkManager watchdog
(`SpeedKeepAliveWorker`) restarts the service if it was killed (e.g. after
app update / device reboot — the boot receiver restarts it too).

---

## Features

- ⚡ Live ↓/↑ speed (bytes-per-second, per second) with session peak
- 📈 30-second sparkline (last 60 samples)
- 📱 Mobile & Wi-Fi daily usage cards (real counters)
- 🗓 History (all recorded days, newest first) + pinned monthly total, clear option
- 🎚 Optional monthly **warning** limit (2 GB / 5 GB / 10 GB / custom) —
  alerts at 50 % / 90 % / 100 % of the limit; the app never blocks data
- 🌗 Glassy light + dark theme (GPT-style glassmorphism)
- 🔔 Status-bar notification with live speed + custom speed glyph icon
- 🧾 Privacy: no INTERNET, no ACCESS_WIFI_STATE, no READ_PHONE_STATE
  permissions

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
│   ├── PreferencesManager.kt  # DataStore: theme, limit, reset baseline
│   └── UsageResetter.kt       # single reset path (service path uses its own)
├── services/
│   ├── SpeedMeterService.kt   # the engine: 1 Hz ticker + 15 s accounting
│   ├── TrafficCounterReader.kt# TrafficStats / /proc/net/dev + parser
│   └── ServiceScheduler.kt    # WorkManager keep-alive + boot handling
└── ui/
    ├── components/            # AppBackground, GlassCard, GlassChip, AppIcon
    ├── screens/               # Dashboard, History, Settings, Intro, Splash
    ├── theme/                 # glass palettes (light + dark), Material 3
    ├── util/                  # secondary()/dlColor()/ulColor()/toBytes()
    └── viewmodel/             # Dashboard / History / Settings VMs
```

Docs: [`docs/database-schema.md`](docs/database-schema.md) ·
[`docs/project-blueprint.md`](docs/project-blueprint.md) ·
[`CHANGELOG.md`](CHANGELOG.md)

---

*Created with [Google AI Studio](https://ai.studio/apps/a5912416-2185-4907-b8ac-ffadd5ddd026)*
