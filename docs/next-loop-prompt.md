# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration (loop 6, 2026-10-14) was executed by **one agent** without an Android
toolchain in the sandbox, with **GitHub Actions compiling and running the unit tests on
every push** and publishing readable check runs ("Unit test log", "Lint log"). The
"multi-model brainstorm" below is therefore a structured **multi-perspective analysis**
done in a single pass: Product, Engineering, UI and QA/Performance each got an independent
round, then the findings were merged into one prioritized list. The loop prompt at the
bottom makes the *next* agent re-run the exact same process (audit → fix → premium polish →
verify → docs → brainstorm → next prompt) so the project keeps improving indefinitely.

## What this iteration changed (context for the next one)

- **Installs update in place now.** The reported "App not installed as package conflicts
  with an existing package" came from the workflow signing every build without a
  `KEYSTORE_BASE64` secret with a *fresh* throwaway key (debug builds used the runner's
  debug keystore). Every build — debug and release — is now signed with **one stable key**
  resolved in `app/build.gradle.kts`: `KEYSTORE_PATH`/`KEYSTORE_BASE64` → `my-upload-key.jks`
  (git-ignored, local) → committed `keystore/ci-shared.p12` (alias `upload`, password
  `android`). `version_code` is optional and defaults to the workflow run number; a *Verify
  APK signature* step prints `apksigner verify --print-certs`; `action-gh-release` gets
  `overwrite_files` (the old `overwrite` was not an input). Users must uninstall the
  throwaway-signed build **once**; README explains it and how to move to a private key.
- **User-controlled pause** (`services/MeterControl.kt`): dashboard header button + banner,
  Settings switch, durable `meter_paused_by_user`; every restart path asks `mayStart()` —
  `MainActivity`, `BootCompletedReceiver` (now `goAsync()`), `SpeedKeepAliveWorker`, the
  timeout retry, and `onStartCommand` for stale sticky restarts. UI says **"paused by you"**
  (hero label, Settings engine status, tile subtitle); no OS "paused" notice while held.
- **Screen-off cadence**: `ACTION_SCREEN_ON/OFF` runtime receiver, 500 ms on-screen / 2 s
  off, fixed-delay ticker, screen-on wakes the loop at once. Engine tests prove the rate is
  identical and the transition never spikes. Notification updates were deliberately *not*
  gated on screen state (always-on displays show the ticker icon).
- **Acknowledged upload window**: each upload stream closes its chunked body at the deadline
  and waits ≤ 3 s for the response; when all streams are acknowledged the window is first
  byte → last ack with every byte (`computeUploadThroughput`, pure, tested); otherwise the
  send-side window, `ThroughputResult.acknowledgedWindow = false`, shown on the result card.
- **Monthly warn-only limit** (mobile-only, `getThisMonthMobileTotal`, shared pure
  `util/LimitAlerts.kt`), **per-day drill-down** in History, **CSV attachments** through a
  manifest `FileProvider` (`util/CsvExport.kt`) and a new **speed-test history export**
  (`util/SpeedTestCsv.kt`).
- **Material You accent** (`AccentTheme.SYSTEM`, API 31+, primary/onPrimary only) and a
  **reduce motion** switch (`LocalReducedMotion`: splash, digit roll, breathing label).
- **`NotificationText`** pure builder + test; **`publish_check.py --self-test`** runs in the
  test job before Gradle.
- 31 new EN/HI string pairs (267 keys, parity-tested). Docs: README (new "Signing &
  installing updates"), CHANGELOG (loop 6), blueprint, schema.

> ✅ Everything in this loop was compiled and unit-tested by CI (see the check runs on the
> session branch): **118 unit tests, green; lint 0 errors** (the 25 remaining notes are the
> dependency-version and launcher-icon warnings listed under Engineering #1). The first push
> exposed one compile error and two red tests (a renamed field, an integer-truncation
> artefact in a test fixture, a dispatcher race between the DataStore write and the in-memory
> mirror — fixed by flipping the mirror first). Nothing was run on a device or emulator — the install-over-update path,
> the screen-off receiver, the FileProvider share and the Material You swatch still need a
> real-device pass.

---

## Perspective 1 — Product

1. **Last-known speed with its timestamp** on the 2×1 widget's sibling (a 4×1 variant):
   "↓ 12.4 Mbps · ↑ 3.1 Mbps · as of 14:32". Needs a `last_speed_at` key written together
   with `last_speed_down/up` (every 15 s) and the rule "never without the time". Also lets
   the tile say "as of HH:mm" instead of only today's total.
2. **Pause with a timer** ("Pause for 1 h / until tomorrow / until I resume"): the plumbing
   (`MeterControl`, `meter_paused_by_user`) exists; add `meter_resume_at` and a WorkManager
   one-shot that calls `MeterControl.resume()` — a resume is a *user-initiated* start only
   if it happens from an allowed context, so schedule it as a notification ("Meter resumes
   now — tap to confirm") when the direct start is refused.
3. **Daily digest notification (opt-in, local)**: "Yesterday: 1.2 GB mobile · 4.8 GB Wi-Fi",
   posted by the existing accounting pass at the first tick after midnight. No network.
4. **Import of a CSV export** (restore history after the one-time uninstall): parse the
   `UsageCsv` header, upsert rows, never overwrite today's live row. Document "export →
   uninstall → install → import".
5. **Rejected again:** scheduled/automatic speed tests, per-app breakdown, any cap/freeze,
   cloud sync.

## Perspective 2 — Engineering

1. **Dependency refresh, one group per push, CI-verified**: AGP 9.1.1 → 9.4.x, Kotlin /
   compose plugin 2.2.10 → 2.4.x, Compose BOM 2024.09 → 2026.09, lifecycle 2.8.7 → 2.11,
   navigation 2.8.9 → 2.10, Room 2.7.0 → 2.8.5, WorkManager 2.10.1 → 2.12, DataStore 1.1.7
   → 1.2.1, coroutines 1.10.2 → 1.11, Robolectric 4.16.1 → 4.17. Read the check runs after
   each bump; lint still flags `OldTargetApi` — review targetSdk 37 before bumping.
2. **Private signing key path**: `gh secret` is not reachable from the agent sandbox, so the
   stable key is committed. Add a workflow input `keystore_source: shared|secret` that fails
   loudly when `secret` is requested but `KEYSTORE_BASE64` is absent, and print the
   certificate SHA-256 in the release notes so a user can compare before installing.
3. **`handleTimeout` behind a small interface** so Robolectric can drive stop + schedule +
   notify with `Robolectric.buildService`; same for the screen receiver (inject the
   interactive-state source so a unit test can flip it and assert the cadence).
4. **Tile freshness**: `SpeedMeterTile` reads the Room row once per `onStartListening`; when
   the service runs it could use `todayUsageFlow` (≤ 15 s newer). Keep "persisted or
   in-memory, never live".
5. **Second tap during the metered confirmation** launches a second `loadCandidates()`
   coroutine in `SpeedTestViewModel.requestTest()`; guard with `pendingConfirmationMb != null`.
6. **CsvExport cache hygiene**: `cache/exports/` keeps one file per export; delete files older
   than a day on the next export (or on app start) so a heavy user never accumulates them.
7. **Upload ack wait on a dead socket**: `UPLOAD_ACK_WAIT_MS` is 3 s after a 6 s window; if a
   server never answers the phase now takes up to ~9 s. Consider reading the response on a
   separate watchdog so an abort (transport change) during the wait returns immediately —
   verify the sentinel already covers it (it disconnects live sockets) and add a test.

## Perspective 3 — UI / Premium

1. **Per-phase peaks on the metric tiles** — only after a 1 s smoothing window exists (raw
   250 ms interval peaks are bursty and would exceed the headline number).
2. **Dashboard landscape**: two columns start at 600 dp; verify phones in landscape
   (≈ 640–780 dp wide, 360 dp tall) — the hero should not push the usage cards below the
   fold; consider hero-left / cards-right there.
3. **Widget preview image** for launchers below API 31 (`previewImage`): a static drawable
   with dashes, never digits. And the **4×1 last-known-speed widget** from Product #1.
4. **System reduce-motion**: also honour `Settings.Global.ANIMATOR_DURATION_SCALE == 0`
   (OS-level "remove animations") as if the in-app switch were on; show the switch as
   "forced by system" then.
5. **Edge-to-edge on targetSdk 36**: verify the Settings text fields with the IME open
   (`imePadding`) and the floating nav over gesture insets on a 3-button-nav device.
6. **Paused banner polish**: the dashboard banner could show *since when* ("Paused by you
   since 22:10") — needs a `meter_paused_at` key, written by `MeterControl.pause()`.

## Perspective 4 — QA / Performance

1. **Robolectric `@GraphicsMode(NATIVE)`** for the render tests → real layout, so
   `assertIsDisplayed`, `performScrollTo` and **font-scale configs** (`fontScale = 1.3f /
   1.5f` via `@Config(qualifiers)`) become meaningful; add a 1.5× render of Dashboard, Test
   and Settings asserting nothing is clipped.
2. **Service tests with `Robolectric.buildService`**: screen-off cadence (flip the injected
   interactive state, assert `READ_INTERVAL_MS` vs `SCREEN_OFF_READ_INTERVAL_MS`), the
   pause safety net in `onStartCommand`, and `MeterControl.pause()` clearing the OS notice.
3. **Instrumented tests in CI**: a dispatch-only job with an emulator
   (`reactivecircus/android-emulator-runner`, API 34 image) running
   `SpeedTestEngineInstrumentedTest` plus an install → update smoke (`adb install -r` of two
   consecutive builds) that would have caught the signature mismatch.
4. **Battery audit on a device**: `dumpsys batterystats` for 1 h with the screen off,
   before/after the screen-off cadence (expect ~4× fewer counter reads).
5. **R8**: narrow `proguard-rules.pro` keeps and verify the release build on dispatch still
   starts (the signing change touched the release config).

---

## Prioritized for the next loop

1. **Read the check runs first.** Open the latest run on the session branch and start from a
   green baseline (fix anything red before touching features) — P0
2. **Dependency refresh, one group per push, CI-verified** (AGP/Kotlin/Compose BOM first,
   then Room/WorkManager/DataStore, then test libs; targetSdk 37 review last) — P1
3. **Service-level tests** (`Robolectric.buildService`: cadence switch, pause safety net,
   `handleTimeout` behind an interface) + `requestTest()` double-tap guard + CsvExport cache
   hygiene — P1
4. **Last-known speed with timestamp** (`last_speed_at`) → 4×1 widget + tile "as of HH:mm",
   widget preview image — P2
5. **Pause with a timer / "paused since"** (`meter_resume_at`, `meter_paused_at`) — P2
6. **System reduce-motion + landscape hero/cards split + IME/edge-to-edge check** — P2
7. **CSV import (restore after uninstall)** + daily digest notification (opt-in) — P3
8. **`@GraphicsMode(NATIVE)` 1.5× font-scale render tests; emulator job on dispatch with an
   install → update smoke; R8 keeps review; `keystore_source` workflow input** — P3

---

## Prompt for the next loop

```text
You are continuing the development loop for this Android app (Internet Speed Meter —
Kotlin + Jetpack Compose + Room + WorkManager + DataStore, repo root is the current working
directory; work ONLY on the current session branch).

NON-NEGOTIABLES (never break these):
- Every number the user sees must come from a real OS counter (TrafficStats, /proc/net/dev,
  NetworkStatsManager) or from a real measurement the app performed (the opt-in speed test).
  No simulated data, no dummy seeding, no fabricated splits, no averaged fake across a
  suspension: if the OS froze the process, the meter shows 0 and flags PAUSED. A widget or
  tile may show a *last known* value only together with its timestamp.
- Download and upload must stay independent: one coherent (rx, tx) read of one counter
  source per tick, two separate deltas. SpeedEngineTest.downloadAndUploadAreNeverMirrored
  must keep passing. The screen-off cadence (2 s) must keep measuring the same rate as the
  500 ms on-screen cadence (SpeedEngineTest.aTwoSecondScreenOffCadenceMeasuresTheSameRate).
- The "add-on data cap / freeze" feature stays REMOVED. The app only ever warns (one dialog
  per 50/90/100 % tier of the optional daily and monthly mobile limits, util/LimitAlerts.kt)
  and never blocks data.
- Keep it lightweight & private: no ACCESS_WIFI_STATE, no READ_PHONE_STATE, no
  analytics/accounts/third-party SDKs. INTERNET exists ONLY for the manual speed test and
  the UI must keep saying so. The test must keep asking before it spends metered data
  (mobile, metered Wi-Fi, VPN over either), must keep aborting when the transport changes
  mid-run, must keep requiring the upload acknowledgement, must keep closing the upload
  window at the last server acknowledgement when every stream was answered (send-side
  fallback flagged as acknowledgedWindow = false), and AUTO may fall back only when the
  first host is unreachable.
- No new dependencies without a very strong reason (plain HttpURLConnection; the endpoint
  set is TestServer + SpeedTestServers; the widget is RemoteViews, not Glance; CSV sharing
  is the manifest FileProvider from AndroidX core).
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent cards, floating
  glass-pill nav, accent picker incl. the wallpaper/Material You swatch on API 31+,
  instrument gauge with ghost needle, reduce-motion switch) — refine, don't replace.
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml) working: the
  unit-test job (which first runs `publish_check.py --self-test`) and the non-blocking lint
  job run on EVERY push and publish the "Unit test log" / "Lint log" check runs via
  .github/scripts/publish_check.py; the build/release job runs on manual dispatch only with
  build_type/version_name/version_code(optional → run number)/tag/release_title/
  release_notes/create_release inputs; EVERY build (debug and release) is signed with the
  one stable key resolved by app/build.gradle.kts (KEYSTORE_PATH/KEYSTORE_BASE64 →
  my-upload-key.jks → committed keystore/ci-shared.p12) so a newer APK always installs over
  the previous one — never go back to a per-run throwaway key or an unsigned/debug-keystore
  build; the signature is verified in the job; APK uploaded to the build artifact and
  (optionally) the GitHub Release (overwrite_files on the same tag). Raw Actions logs may
  be unreachable — read the check runs
  (`gh api repos/<owner>/<repo>/commits/<sha>/check-runs`) and iterate until green.
- A paused meter is always labelled: "paused" by the OS (refused foreground start, frozen
  process — with the "Speed meter paused" notice) or "paused by you" (MeterControl,
  meter_paused_by_user — every restart path must keep asking MeterControl.mayStart());
  never a silent stop and never a stale number without its timestamp.
- Every user-facing string is a resource: `values/strings.xml` and `values-hi/strings.xml`
  must keep identical key sets and format arguments (StringResourceParityTest enforces it).
  Nothing below 10 sp.
- Keep both `onTimeout` overrides (API 35+ two-arg, deprecated one-arg for API < 35); the
  service must stop immediately on timeout and leave restarts to WorkManager.
- All work commits to the current session branch. Do not create or push other branches.

PROCESS — run these steps IN ORDER and complete all of them:

1. AUDIT (fresh eyes). First open the latest CI run on the session branch and read its
   check runs: start from green. Then read the ENTIRE codebase: every .kt file under
   app/src (main + test + androidTest), AndroidManifest.xml, app/build.gradle.kts, root
   build.gradle.kts, gradle/libs.versions.toml, gradle.properties, proguard-rules.pro, the
   workflow YAML and .github/scripts/publish_check.py, all resources (values, values-hi,
   values-night, layout, xml, drawable) and all docs. List concrete problems with file:line
   evidence: bugs, race conditions, permission/OS-compliance issues (Android 15/16 FGS
   budget, API 31+ background FGS start, the "meter paused" refusal path, the user pause),
   performance in the 500 ms / 2 s hot path and the 15 s accounting pass, battery, dead
   code, stale docs, API misuse, missing tests. Verify the engine invariants still hold:
   500 ms on-screen / 2 s screen-off read loop, ~1 Hz publish, measured windows,
   counter-reset re-anchoring, per-source anchors, SourceHealth probing, transport pushed
   by NetworkCallback, real per-transport attribution, and the speed-test rules (endpoint
   resolution, metered confirmation, abort on transport change, upload acknowledgement +
   acknowledged window, grace-window throughput, fallback only on unreachability).

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) anything red or flaky in CI, and every compile/test error a push reveals,
   (b) correctness of the speed engine, the speed test and daily/monthly accounting,
   (c) background/lifecycle robustness and battery (pause safety net, timeout path,
       screen-off receiver), the signing/update path (never regress "installs over the
       previous build"),
   (d) then the items from docs/next-loop-prompt.md that are not yet done (dependency
       refresh one group per push, service-level Robolectric tests, requestTest double-tap
       guard, CsvExport cache hygiene, last-known speed with timestamp → 4×1 widget + tile,
       pause timer / paused-since, system reduce-motion, landscape split, CSV import, daily
       digest, NATIVE-graphics render tests at 1.5× font scale, emulator job with an
       install → update smoke, R8 keeps review).
   Push after each coherent batch and READ the check runs before the next batch.

3. PREMIUM PASS. Improve the UI where it still feels dated compared to top speed-meter
   apps: motion (respecting reduce motion), hierarchy, spacing, micro-copy, accessibility
   (font scale, labels, state descriptions, live regions, touch targets). Keep it tasteful
   and consistent with the glass system. No new dependencies.

4. VERIFY. If a toolchain is available, run `./gradlew :app:testDebugUnitTest` and
   `:app:lintDebug` and fix everything. If not: re-read every file you touched; check
   imports/overloads/types compile (Kotlin strict); validate the workflow YAML parses (no
   tabs, all inputs present, the test job runs on push, the build job still resolves the
   stable key); run `python3 .github/scripts/publish_check.py --self-test`; validate every
   XML resource parses and that values/ and values-hi/ have identical key sets and format
   arguments; ensure tests match the real APIs (names, parameter order, defaults); run a
   crude brace/paren balance check over app/src; then push and READ the "Unit test log"
   and "Lint log" check runs of that commit — the loop is not done while they are red. Run
   `git status` and confirm the tree is consistent. Update unit tests for anything you
   changed and add tests for anything you fixed.

5. DOCS. Update README.md, CHANGELOG.md (new dated entry), docs/project-blueprint.md and
   docs/database-schema.md so they describe the app AS IT IS NOW, not as it was.

6. NEXT LOOP. Re-run the same multi-perspective brainstorm (Product, Engineering, UI,
   QA/Performance — independent rounds, then merged priorities), rewrite
   docs/next-loop-prompt.md with the new findings and a NEW next-loop prompt in ONE code
   block that instructs the same process to repeat again (audit → fix → premium → verify →
   docs → brainstorm → next prompt), preserving the non-negotiables and the "repeat the
   loop" instruction.

7. COMMIT. Stage and commit everything on the current session branch with a clear message
   (e.g. "loop N: <top 3 changes>"), then push.

End with a short summary (Hinglish is fine): what you audited, what you fixed, what you
improved, what you deliberately left for later, and the top 3 items from the new next-loop
prompt.
```
