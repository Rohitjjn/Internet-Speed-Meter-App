# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration (loop 5, 2026-10-14) was executed by **one agent** without an Android
toolchain in the sandbox — but, new since loop 5, with **GitHub Actions compiling and
running the unit tests on every push** and publishing readable check runs — so the "multi-model brainstorm" below is a structured
**multi-perspective analysis** done in a single pass: Product, Engineering, UI and
QA/Performance each got an independent round, then the findings were merged into one
prioritized list. The loop prompt at the bottom makes the *next* agent re-run the exact same
process (audit → fix → premium polish → verify → docs → brainstorm → next prompt) so the
project keeps improving indefinitely.

## What this iteration changed (context for the next one)

- **CI is now the compiler.** `android-build.yml` runs the unit-test job and the
  non-blocking lint job on every push (docs-only pushes skipped) and publishes condensed
  check runs ("Unit test log", "Lint log") through `.github/scripts/publish_check.py`;
  the build/release job is unchanged and dispatch-only. Loop 5 was iterated against those
  check runs: the first run exposed 4 red tests (gauge headroom, `resetSession` republish,
  `cancelTest` draining, stale cost figures) — fixed; later runs exposed that Robolectric's
  legacy graphics mode cannot answer `assertIsDisplayed` below the fold — tests assert
  presence and drive clicks through the semantics action. **101 unit tests, green.**
- **Honest "meter paused".** `startService()` returns whether the OS accepted the start;
  every refusal (timeout retry, watchdog, boot receiver, `startForeground` throwing) posts
  the "Speed meter paused" notification (channel `speed_meter_status`, id 1002) — cleared on
  the next successful `onStartCommand`; `MainActivity.onResume()` restarts the meter; the
  watchdog returns `Result.retry()` with back-off. Expedited WorkManager was examined and
  rejected: it is not an exemption from the API 31+ background-start rule.
- **Adaptive download requests** (`nextRequestBytes`, pure, tested): 20 MB first, doubled
  up to 100 MB whenever the previous body completed in < 1 s, on a kept-alive connection.
- **Pushed transport watcher**: `Connectivity.transportChanges()` → `callbackFlow` over
  `registerDefaultNetworkCallback` + 2 s safety poll; the view model aborts on the first
  transport ≠ transport-at-start. Engine grace now starts at the first byte.
- **Test isolation**: `PreferencesManager(context, store)`, `SettingsViewModel(app,
  manager)`, `TestPreferences` per-test file. New `ScreensRenderTest`,
  `TodayUsageWidgetTest`, request-sizing cases.
- **Quick Settings tile** (`SpeedMeterTile`): state = meter running, subtitle = today's
  persisted total / "Meter paused"; opens the app. Never a live speed.
- **Premium/a11y**: ≥ 10 sp everywhere (widget ≥ 11 sp), haptic tick on a new result,
  Settings column capped at 720 dp and centred, lint quick wins (dead API-23 branches,
  unused colours/drawables, redundant label, widget info attributes).
- **Battery**: the 15 s accounting pass writes the Room row only when it changed (plus a
  ~5 min self-heal write) and the DataStore "last speed" only when it changed.
- **Docs**: README, blueprint, schema, CHANGELOG (loop 5 entry). Hindi: 236 keys, parity.

> ✅ Everything in this loop was compiled and unit-tested by CI (see the check runs on the
> session branch). Nothing was run on a device or emulator — the instrumented test and the
> FGS refusal path still need a real-device pass.

---

## Perspective 1 — Product

1. **User-controlled pause.** Users who want a quiet night currently have to force-stop
   the app. `SpeedMeterService.stopService()` exists but nothing calls it. A "Pause meter"
   action (Dashboard hero overflow or Settings) + "Resume" should stop the service on
   request, remember the choice (`meter_paused_by_user` in DataStore), keep the watchdog /
   boot receiver / `onResume` from restarting it until resumed, and make the dashboard,
   notification-less state and the tile say **"Paused by you"** — distinct from the OS
   refusal notice. No numbers are shown while paused except the persisted "today".
2. **Monthly mobile warning (warn-only).** `getThisMonthTotal` exists; History pins the
   monthly total. Add an optional monthly mobile limit next to the daily one with the same
   50/90/100 % tiers, same dismissal keys per month, never blocking.
3. **Speed-test history export** (CSV of the last 20 results through the share sheet) and a
   **FileProvider-backed** CSV for both exports (`text/csv` with a real file so Drive/Files
   appear as targets; `cache/exports/`, `FileProvider` from AndroidX core — no new dep).
4. **Per-day drill-down** in History: a tap on a day expands mobile ↓/↑ and Wi-Fi ↓/↑ (the
   row already holds the four counters — zero new data).
5. **Rejected again:** scheduled/automatic speed tests (would spend data without a tap and
   contradict "INTERNET only for the manual test"); any per-app breakdown.

## Perspective 2 — Engineering

1. **Screen-off cadence.** The 500 ms read loop runs while the screen is off, when nothing
   can display the number (the notification is not visible either). Register
   `ACTION_SCREEN_OFF/ON` (runtime receiver, no permission) and read every 2 s while off;
   the engine already uses measured elapsed time, so the maths is unaffected (`SpeedEngine`
   test with a 2 s window must pass). Document it as "500 ms while the screen is on".
2. **Upload still counts socket-accepted bytes.** The last send buffer (typically 64 KiB–
   1 MiB per stream) is counted as uploaded; on slow uplinks that inflates a 6 s result by
   up to a few percent. Options: subtract `Socket.sendBufferSize` per stream at the end, or
   end the measured window at the last *acknowledged* chunk via a chunked response read.
   Decide, implement, test with a fake stream.
3. **Dependency refresh, one group per push now that CI compiles:** AGP 9.1.1 → 9.4.x,
   Kotlin / compose plugin 2.2.10 → 2.4.x, Compose BOM 2024.09 → 2026.09, lifecycle 2.8.7 →
   2.11, navigation 2.8.9 → 2.10, Room 2.7.0 → 2.8.5, WorkManager 2.10.1 → 2.12, DataStore
   1.1.7 → 1.2.1, coroutines 1.10.2 → 1.11, Robolectric 4.16.1 → 4.17, test core/runner
   1.7.0. Each bump is its own commit; read the check runs before the next one. Lint also
   flags `OldTargetApi` — review targetSdk 37 behaviour changes before bumping.
4. **Extract the notification text builder** out of `SpeedMeterService` into a pure
   function (speed strings + today + paused suffix) and unit-test it; same for the
   `handleTimeout` decision (stop + schedule + notify) behind a small interface so
   Robolectric can drive it with `Robolectric.buildService`.
5. **Tile freshness**: `SpeedMeterTile` reads the Room row once per `onStartListening`;
   when the service is running it could use `todayUsageFlow` (in-memory, newer by ≤ 15 s).
   Minor; keep the "persisted or in-memory, never live" rule.
6. **`requestTest()` is guarded by `testJob?.isActive`** (verified) — fine; but a second tap
   during the metered confirmation launches a second `loadCandidates()` coroutine. Guard
   with `pendingConfirmationMb != null`.

## Perspective 3 — UI / Premium

1. **Material You accent** ("System" option in the accent picker using
   `dynamicLightColorScheme` / `dynamicDarkColorScheme` on API 31+, no dependency) —
   keeps the glass system, makes the app feel native on Pixels.
2. **Per-phase peaks on the metric tiles** — only after a 1 s smoothing window exists
   (raw 250 ms interval peaks are bursty and would exceed the headline number).
3. **Dashboard landscape**: the Dashboard uses `maxWidth >= 600.dp` for two columns; verify
   phones in landscape (≈ 640–780 dp wide, 360 dp tall) — the hero should not push the
   usage cards below the fold; consider a hero-left / cards-right split there.
4. **Widget preview image** for launchers below API 31 (`previewImage`): a static drawable
   with dashes, never digits.
5. **Reduced motion**: read `Settings.Global.ANIMATOR_DURATION_SCALE`; when 0, skip the
   breathing/needle animations (Compose does not do this for you).
6. **Edge-to-edge on targetSdk 36**: verify the Settings text fields with the IME open
   (`imePadding`) and the floating nav over gesture insets on a 3-button-nav device.

## Perspective 4 — QA / Performance

1. **Robolectric `@GraphicsMode(NATIVE)`** for the render tests → real layout, so
   `assertIsDisplayed`, `performScrollTo` and **font-scale configs** (`fontScale = 1.3f /
   1.5f` via `@Config(qualifiers)`) become meaningful; add a 1.5× render of Dashboard,
   Test and Settings asserting nothing is clipped (`assertHeightIsAtLeast` on key rows).
2. **Instrumented tests in CI**: a dispatch-only job with an emulator
   (`reactivecircus/android-emulator-runner`, API 34 image) running
   `SpeedTestEngineInstrumentedTest`; keep it out of the push path (10+ min).
3. **Battery audit on a device**: Battery Historian / `dumpsys batterystats` for 1 h with
   the screen off, before/after the screen-off cadence change.
4. **R8**: narrow `proguard-rules.pro` keeps (Room/DataStore do not need broad keeps on
   AGP 9) and verify the release build on dispatch still starts.
5. **`publish_check.py`**: add a tiny self-test (sample Gradle log → expected summary) so
   the CI reporter cannot silently regress.

---

## Prioritized for the next loop

1. **Read the check runs first.** Push a no-op is not needed — open the latest run on the
   session branch and start from a green baseline (fix anything red before touching
   features) — P0
2. **User-controlled pause** (stop/resume, remembered, respected by watchdog/boot/`onResume`,
   "Paused by you" everywhere incl. the tile) — P1
3. **Screen-off cadence (500 ms on / 2 s off)** with the engine test for a 2 s window — P1
4. **Upload send-buffer correction** (decide + implement + fake-stream test) — P1
5. **Monthly mobile warning (warn-only)** + per-day drill-down in History — P2
6. **FileProvider CSV + speed-test history export** — P2
7. **Dependency refresh, one group per push, CI-verified** (then targetSdk 37 review) — P2
8. **Material You accent option + reduced-motion respect** — P2
9. **`@GraphicsMode(NATIVE)` render tests at 1.5× font scale; notification-text builder
   extracted + tested; `publish_check.py` self-test** — P3
10. **4×1 widget ("last known speed · as of HH:mm" needs a `last_speed_at` key), widget
    preview image, emulator job on dispatch, R8 keeps** — P3

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
  must keep passing.
- The "add-on data cap / freeze" feature stays REMOVED. The app only ever warns
  (50/90/100 % of an optional daily — and, if added, monthly — mobile limit) and never
  blocks data.
- Keep it lightweight & private: no ACCESS_WIFI_STATE, no READ_PHONE_STATE, no
  analytics/accounts/third-party SDKs. INTERNET exists ONLY for the manual speed test and
  the UI must keep saying so. The test must keep asking before it spends metered data
  (mobile, metered Wi-Fi, VPN over either), must keep aborting when the transport changes
  mid-run, must keep requiring the upload acknowledgement, and AUTO may fall back only when
  the first host is unreachable.
- No new dependencies without a very strong reason (plain HttpURLConnection; the endpoint
  set is TestServer + SpeedTestServers; the widget is RemoteViews, not Glance).
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent cards, floating
  glass-pill nav, accent picker, instrument gauge with ghost needle) — refine, don't replace.
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml) working: the
  unit-test job and the non-blocking lint job run on EVERY push and publish the "Unit test
  log" / "Lint log" check runs via .github/scripts/publish_check.py; the build/release job
  runs on manual dispatch only with build_type/version_name/version_code/tag/release_title/
  release_notes/create_release inputs; keystore from KEYSTORE_BASE64 or a throwaway keytool
  key; APK uploaded to the build artifact and (optionally) the GitHub Release (overwrite the
  same tag). Raw Actions logs may be unreachable — read the check runs
  (`gh api repos/<owner>/<repo>/commits/<sha>/check-runs`) and iterate until green.
- A paused meter is always labelled: "paused" by the OS (refused foreground start, frozen
  process) or — if you add it — "paused by you"; never a silent stop and never a stale
  number without its timestamp.
- Every user-facing string is a resource: `values/strings.xml` and `values-hi/strings.xml`
  must keep identical key sets and format arguments (StringResourceParityTest enforces it).
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
   budget, API 31+ background FGS start, the "meter paused" refusal path), performance in
   the 500 ms hot path and the 15 s accounting pass, battery (screen-off behaviour), dead
   code, stale docs, API misuse, missing tests. Verify the engine
   invariants still hold: 500 ms read loop, ~1 Hz publish, measured windows, counter-reset
   re-anchoring, per-source anchors, SourceHealth probing, transport pushed by
   NetworkCallback, real per-transport attribution, and the speed-test rules (endpoint
   resolution, metered confirmation, abort on transport change, upload acknowledgement,
   grace-window throughput, fallback only on unreachability).

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) anything red or flaky in CI, and every compile/test error a push reveals,
   (b) correctness of the speed engine, the speed test (upload send-buffer correction) and
       daily accounting,
   (c) background/lifecycle robustness and battery (user-controlled pause that the
       watchdog/boot receiver/onResume respect; 500 ms on-screen / 2 s screen-off cadence),
   (d) then the items from docs/next-loop-prompt.md that are not yet done (monthly
       warn-only limit, per-day drill-down, FileProvider CSV + test-history export,
       dependency refresh one group per push, Material You accent, reduced motion,
       NATIVE-graphics render tests at 1.5× font scale, notification-text builder
       extraction, 4×1 widget, widget preview image, emulator job on dispatch).
   Push after each coherent batch and READ the check runs before the next batch.

3. PREMIUM PASS. Improve the UI where it still feels dated compared to top speed-meter
   apps: motion, hierarchy, spacing, micro-copy, accessibility (font scale, labels, live
   regions, touch targets). Keep it tasteful and consistent with the glass system. No new
   dependencies.

4. VERIFY. If a toolchain is available, run `./gradlew :app:testDebugUnitTest` and
   `:app:lintDebug` and fix everything. If not: re-read every file you touched; check
   imports/overloads/types compile (Kotlin strict); validate the workflow YAML parses (no
   tabs, all inputs present, the test job runs on push); validate every XML resource parses
   and that values/ and values-hi/ have identical key sets and format arguments; ensure
   tests match the real APIs (names, parameter order, defaults); run a crude brace/paren
   balance check over app/src; then push and READ the "Unit test log" and "Lint log" check
   runs of that commit — the loop is not done while they are red. Run `git status` and
   confirm the tree is consistent. Update unit tests for anything you changed and add tests
   for anything you fixed.

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
