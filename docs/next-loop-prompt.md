# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration (loop 4, 2026-10-14) was executed by **one agent** without an Android
toolchain in the sandbox, so the "multi-model brainstorm" below is a structured
**multi-perspective analysis** done in a single pass: Product, Engineering, UI and
QA/Performance each got an independent round, then the findings were merged into one
prioritized list. The loop prompt at the bottom makes the *next* agent re-run the exact same
process (audit → fix → premium polish → verify → docs → brainstorm → next prompt) so the
project keeps improving indefinitely.

## What this iteration changed (context for the next one)

- **Android 15 compliance**: `onTimeout` now stops the service immediately and schedules a
  WorkManager retry with exponential back-off (the inline 20 s background restart was
  refused on API 35+ and `stopSelf()` was never reached).
- **Speed-test engine**: socket-disconnecting abort sentinel (sub-second abort inside
  blocking reads), streams joined before the counter is read (no lost in-flight bytes),
  grace-window throughput (`computeThroughput`, pure + tested), kept-alive latency probes,
  **upload acknowledgement** (32 KiB POST must get a 2xx/3xx), typed failures
  (`SpeedTestException`) with localized messages, fallback **only** on an unreachable first
  host. The brainstormed "stream ramp" was rejected on purpose (it deflates fast lines).
- **Testability**: `SpeedTestRunner` interface + `Connectivity` interface, injected into
  `SpeedTestViewModel`; `SpeedTestViewModelTest` pins every rule with fakes. New
  `SpeedTestMathTest`, `SettingsViewModelTest`, `StringResourceParityTest`, two
  `SpeedEngineTest` cases (injected clock, bounded history), an upload-ack instrumented case.
- **Metered rule widened**: VPN over mobile and metered Wi-Fi hotspots now ask first
  (`isActiveNetworkMetered`), the dialog names the transport and notes Data Saver.
- **Hindi pass 2 complete**: 229 keys, every screen, parity-tested.
- **Widget**: `TodayUsageWidget` (RemoteViews) + `WidgetRefreshWorker` (30 min) + service
  push (≤ 1/min, only when placed).
- **Export**: History → CSV through the share sheet (`UsageCsv`, raw bytes).
- **Premium pass**: ghost needle + "vs last test" chip, breathing "WAITING FOR ENGINE",
  tap-to-save endpoint options with a "Saved: …" summary, collapsible Settings groups,
  two-column Test/History, accessibility (descriptions, live regions, roles/selection).
- **Also fixed**: locale-dependent date keys (`Locale.US`), `SpeedEngine` session start
  flag + injectable clock, the Dark Mode row padding, permission re-check on resume
  (Settings + Intro), the limit dialog printing `2.0 GB`, the cancel-vs-abort race in the
  view model.

> ⚠️ Nothing in this loop was compiled (no JDK/SDK in the sandbox). The first job of the next
> loop is to get a compile signal — see priority 1.

---

## Perspective 1 — Product

- **A compile/test signal per push is the most valuable "feature" right now.** The
  workflow only runs on manual dispatch, so two loops of large Kotlin changes have never
  been built by CI. A `push`/`pull_request` trigger that runs *only* the unit-test job
  (guarded so the build/release job still needs its inputs) turns every loop into a
  verified loop.
- **Monthly mobile warning.** Indian packs are per-day *and* per-month; the app warns on a
  daily limit only. A monthly *warning* (50/90/100 %, never blocking) reuses
  `getThisMonthTotal` and the existing alert plumbing — pure data, no new permission.
- **Quick Settings tile** (`TileService`, framework class, no dependency): one tap from the
  shade to open the speed test, with today's total as the subtitle. Premium, cheap, honest.
- **A "last known speed" widget variant (4×1)** — the service already publishes a steady
  speed once a second; a widget cannot follow that (battery), but a once-a-minute push with
  an explicit "as of 12:40" label is honest and useful. Must never show a stale number
  without the timestamp.
- **Test-history export**: usage days export to CSV, the 20 speed-test results do not yet.
  Same share-sheet path, one more pure builder.

## Perspective 2 — Engineering

- **Verify the WorkManager → FGS start path on API 31+/34+/35+.** `SpeedKeepAliveWorker`
  calls `startForegroundService` from a plain (non-expedited) worker. Background FGS starts
  are restricted on API 31+ and WorkManager is not in the exemption list unless the work is
  **expedited with `getForegroundInfo()`** (which, on API 34+, also needs the
  `dataSync` type declared for WorkManager's own foreground service). Today the failure is
  swallowed (`SpeedMeterService.startService` catches it, the worker then returns
  `Result.retry()`), so the watchdog may silently never bring the meter back.
  Device verification + `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` + foreground
  info is the likely fix.
- **Transport watcher polls during the test** (`currentTransport()` every 700 ms). A
  `registerDefaultNetworkCallback` scoped to the run would be push-based and zero-poll,
  consistent with how the passive meter does it. Keep the poll as a fallback for ROMs that
  drop callbacks.
- **Adaptive request size for the download phase.** Each stream requests a fixed ~20 MB
  body (`SpeedTestEngine.kt`, download loop); on a gigabit line the run becomes request-bound (many short requests, more
  per-request latency). Doubling the requested size when a request completes in < 1 s (and
  halving when it takes > 4 s) keeps the pipe full on fast lines and the first byte early
  on slow ones. Pure control logic, testable.
- **`PreferencesManager` should accept a `DataStore` instance** (constructor parameter
  with the singleton as default). Robolectric tests currently share one DataStore file per
  JVM and have to restore defaults in `tearDown`; a per-test
  `PreferenceDataStoreFactory.create(scope, produceFile = tmp)` isolates them properly.
- **CSV export as a real file** via `FileProvider` (already in `androidx.core`, no new
  dependency): write to `cacheDir`, share `text/csv` with a content URI so "Save to Drive /
  Files" targets appear. Keep the plain-text fallback.
- **Settings `customServerUrl` draft is seeded once** from the stored value; if the stored
  URL changes elsewhere (restore, future sync) the field keeps the old draft. Low impact;
  seed on every stored change while the field is not focused.
- **R8 keep rules are broad** (`com.example.data.** { *; }`). Room's own consumer rules
  cover entities/DAOs; narrowing would shrink the release APK a bit. Low priority; verify
  with a release build first.

## Perspective 3 — UI / Premium

- **Smallest text sizes (8.5–9 sp) are below the 11–12 sp floor** recommended for
  accessibility and clip at font scale ≥ 1.3. Audit every `fontSize < 10.sp`, bump labels
  to ≥ 10 sp and test the hero/gauge at 1.3× and 1.5× font scale (Robolectric `qualifiers`
  can do this).
- **Haptics on test completion** (`LocalHapticFeedback`, no permission): one light tick when
  the result lands, matching the existing limit-alert buzz language.
- **Settings is still single-column on tablets/landscape**; the other three screens are
  two-column from 600 dp. Same `BoxWithConstraints` pattern.
- **Phase peak on the metric tiles**: the phase loop already measures the per-interval
  rate; showing "peak 120 Mbps" under the final average is real data users like.
- **Widget preview** uses the live layout with placeholder dashes; a `previewImage` for
  pre-API-31 launchers would make the picker look finished.
- **Collapsed-by-default groups** hide the endpoint options from first-time users; the
  header summary ("Saved: AUTO · …") mitigates it. Consider auto-expanding the server
  group once when the last test used the fallback host.

## Perspective 4 — QA / Performance

- **Battery/awake-time audit on a real device is still unverified** (500 ms reads, screen
  off, `/proc/net/dev` fallback). Target < 2 %. CI cannot check this.
- **Compose smoke tests for the other screens.** Only the dashboard renders under
  Robolectric; a Settings render (collapsibles, tap-to-save) and a Speed Test idle render
  would catch composition crashes in files that were rewritten blind.
- **Widget render test**: `TodayUsageWidget.buildViews(...).apply(context, null)` under
  Robolectric, assert the texts — catches a wrong id/layout before a user does.
- **Parity test extension**: flag non-technical keys whose Hindi text equals the English
  text (lazy copies), with an allow-list for `AUTO`, `Wi-Fi`, `VPN`, `ms`, `—`.
- **Lint**: `android:tint` on the widget `ImageView` raises `UseAppTint` (RemoteViews cannot
  use `app:tint`; suppress with a comment), and the new `exported="true"` receiver should
  stay warning-free. The deprecated 1-arg `onTimeout` override must remain for API < 35.
- **Robolectric + DataStore**: the shared singleton file is a flakiness risk as more tests
  write to it (see the Engineering item); fix at the source rather than in each test.

## Prioritized for the next loop

1. **CI compile signal**: add a `push`/`pull_request` trigger that runs only the unit-test
   (+ lint) job; keep the manual build/release job and its inputs untouched; then **read the
   first CI run's output and fix every compile/test error from loops 3–4** — P0
2. **WorkManager → FGS start on API 31+/34+/35+**: expedited work + `getForegroundInfo`
   (+ `dataSync` type for WorkManager's foreground service), device-verified — P0
3. **Adaptive download request size + push-based transport watcher** (measurement
   accuracy on fast lines, zero polling) — P1
4. **`PreferencesManager(DataStore)` injection + isolated Robolectric DataStore**, then the
   Settings/Test screen render tests and the widget render test — P1
5. **Font-scale audit (≥ 10 sp floor, 1.3×/1.5× render checks) + haptic on result** — P1
6. **Monthly mobile warning (warn only) + Quick Settings tile** — P2
7. **CSV via `FileProvider` (`text/csv`) + speed-test history export** — P2
8. **4×1 widget variant with "last known speed · as of HH:mm"** — P2
9. **Two-column Settings, phase peaks on metric tiles, widget preview image** — P3
10. **Battery audit on a real device + narrower R8 keep rules** — P3

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
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml) working: manual
  dispatch with build_type/version_name/version_code/tag/release_title/release_notes/
  create_release inputs; unit-test job; non-blocking lint job; keystore from
  KEYSTORE_BASE64 or a throwaway keytool key; APK uploaded to the build artifact and
  (optionally) the GitHub Release (overwrite the same tag). Adding a push/pull_request
  trigger for the test job is allowed and wanted; the build/release job must keep requiring
  its manual inputs.
- Every user-facing string is a resource: `values/strings.xml` and `values-hi/strings.xml`
  must keep identical key sets and format arguments (StringResourceParityTest enforces it).
- Keep both `onTimeout` overrides (API 35+ two-arg, deprecated one-arg for API < 35); the
  service must stop immediately on timeout and leave restarts to WorkManager.
- All work commits to the current session branch. Do not create or push other branches.

PROCESS — run these steps IN ORDER and complete all of them:

1. AUDIT (fresh eyes). Read the ENTIRE codebase: every .kt file under app/src
   (main + test + androidTest), AndroidManifest.xml, app/build.gradle.kts, root
   build.gradle.kts, gradle/libs.versions.toml, gradle.properties, proguard-rules.pro, the
   workflow YAML, all resources (values, values-hi, values-night, layout, xml, drawable)
   and all docs. List concrete problems with file:line evidence: compile risks (loops 3–4
   were written without a toolchain — look hard for wrong imports, overloads, named
   arguments, nullability), bugs, race conditions, permission/OS-compliance issues
   (Android 15/16 FGS budget, background FGS start from WorkManager), performance in the
   500 ms hot path, dead code, stale docs, API misuse, missing tests. Verify the engine
   invariants still hold: 500 ms read loop, ~1 Hz publish, measured windows, counter-reset
   re-anchoring, per-source anchors, SourceHealth probing, transport pushed by
   NetworkCallback, real per-transport attribution, and the speed-test rules (endpoint
   resolution, metered confirmation, abort on transport change, upload acknowledgement,
   grace-window throughput, fallback only on unreachability).

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) a CI compile/test signal (push/pull_request trigger for the unit-test job) and every
       compile/test error it reveals,
   (b) correctness of the speed engine, the speed test and daily accounting,
   (c) background/lifecycle robustness (the WorkManager → FGS start path on API 31+),
   (d) then the items from docs/next-loop-prompt.md that are not yet done (adaptive request
       size, push-based transport watcher, DataStore injection + isolated tests, screen
       render tests, widget render test, font-scale audit, haptics, monthly warning, tile,
       FileProvider CSV, test-history export, 4×1 widget, two-column Settings, phase peaks).

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
   balance check over app/src. Then run `git status` and confirm the tree is consistent.
   Update unit tests for anything you changed and add tests for anything you fixed.

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
