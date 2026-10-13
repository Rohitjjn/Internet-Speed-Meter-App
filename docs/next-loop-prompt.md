# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration was executed by **one agent** (no multi-model panel was available in the
sandbox), so the "multi-model brainstorm" below is a structured **multi-perspective
analysis** performed in a single pass: Product, Engineering, UI and QA/Performance each
got an independent round, then the findings were merged into a prioritized list. The loop
prompt at the bottom is written so the *next* agent re-runs the exact same process
(audit → fix → premium polish → verify → docs → brainstorm → next prompt), keeping the
project improving indefinitely.

## What this iteration changed (context for the next one)

- **Speed-test resilience**: endpoint profiles (`TestServer`) with `AUTO` fallback
  (Cloudflare → LibreSpeed), an explicit host choice, and a custom `http(s)` URL with two
  URL flavours. The result card always names the host that produced the numbers.
- **Metered-data confirmation** before a test on mobile data, with a real estimate
  (`TestDataCost`: measured speed × the actual phase budgets, 5 MB floor, +10 % overhead).
- **Abort on network change**: `TestAbortSignal` is polled by every socket loop, so a
  Wi-Fi → mobile switch stops the run in a fraction of a second instead of mixing two
  connections and spending mobile data.
- **`SourceHealth`** — the counter-source switch rule extracted from the service into a
  pure, unit-tested class (rate-limited probes, flap-free switching, UNSUPPORTED ≠ 0).
- **`SpeedEngine` plausibility ceiling** — an absurd single delta (> 2 GiB) is refused and
  re-anchored instead of being charged to the session/daily totals.
- **`onTimeout` backoff** — delayed restart (20 s, max twice), then a one-shot WorkManager
  retry (30 min), instead of a tight restart loop against the Android 15+ FGS budget.
- **Premium pass**: instrument gauge (labelled tick scale + needle + latency on the dial),
  phase cross-fade, animated metric landings, share-result intent, two-column dashboard
  from 600 dp, monthly recap ("vs last month"), settings server picker + diagnostics rows.
- **Localization started**: the whole status-bar notification, the network names and the
  navigation are resource-backed with `values-hi`; the rest of the copy is still Kotlin
  literals.
- **Tests**: `SourceHealthTest`, `TestServerTest`, `PreferencesManagerTest`, an
  implausible-delta case in `SpeedEngineTest`, a gauge-scale case, and a network-gated
  instrumented smoke test for the live endpoint. `ExampleInstrumentedTest` was asserting
  the namespace instead of the applicationId (it failed on every device) — fixed.

---

## Perspective 1 — Product

- **The test screen is the product; it has no "vs last test" context.** 20 results are
  stored, yet a finished test shows an absolute number with nothing to compare against.
  A one-chip delta ("↑ 12 % vs your last test") is a genuine value add and pure data.
- **A home-screen widget ("today's total") is still the best premium item left.** Room is
  already the source; a `RemoteViews` widget with a 30-minute `WorkManager` refresh needs no
  new permission and no new dependency.
- **Hindi is only half done.** The notification (always visible) is translated, but the
  Intro/Dashboard/Test/History/Settings copy is still English literals. For the Indian user
  base this is the single biggest reach item and it is mechanical: extract → `values/` →
  `values-hi/`.
- **Export**: users ask "how much did I use this month?" — a local CSV/text export of the
  daily table (share sheet, no storage permission) answers it and stays private.
- **Test on a metered connection while a data-saver is active**: Android's own Data Saver
  is a strong signal the user does *not* want extra traffic; the app should mention it in
  the confirmation dialog (not silently obey it — the user is the boss).

## Perspective 2 — Engineering

- **`SpeedTestViewModel` has no test.** The metered-confirmation rule, the fallback
  ordering and the abort path are all logic that must be pinned. The blocker is that the
  VM calls `NetworkMonitor` statically — extract a tiny injectable interface
  (`Connectivity` / `TransportProbe`) with an Android default, then test the VM with
  Robolectric.
- **Upload counts bytes the socket accepted, not bytes the server confirmed.** A short
  server-side acknowledgement (one small non-chunked POST whose response body is read) would
  prove the server really received data, and it costs a few KB. Worth doing once per test,
  not per chunk.
- **Stream ramp**: 4 parallel downloads on a 1 Mbit/s line waste 3 connection setups and
  add noise. Starting with 1 stream and adding more after the first ~2 s only helps slow
  lines and costs nothing on fast ones.
- **`jobs.forEach { it.cancel() }` before reading the counter** drops in-flight bytes by a
  hair; `join` with a short timeout (or read the counter after joining) would make the last
  few KB count.
- **`SpeedEngine.sessionDurationMs` is derived from the last *sample* timestamp**
  (`anchor.atMs`), so it freezes while the process is asleep — it should come from a
  monotonic clock (`SystemClock.elapsedRealtime`-equivalent injected as a lambda, keeping
  the engine pure).
- **Settings endpoint card does not reflect "saved vs selected"**: the radio changes local
  state and a separate SAVE button commits. Auto-saving on selection (with the URL field
  keeping its explicit save) removes a footgun where the test runs on a host the UI no
  longer shows.
- **`restoreLastKnown` intentionally does not set `steadyInitialized`** — correct, but the
  interplay is subtle enough to deserve an explicit test (a stale restored value must not
  survive the first real publish; already covered — keep it that way).
- No new dependencies are needed for any of the above.

## Perspective 3 — UI / Premium

- **The dial deserves a second, dimmer needle** showing the *previous* test result — a real
  instrument convention (tell-tale/ghost needle) and a zero-cost way to add comparison.
- **"WAITING FOR ENGINE" should shimmer**, not whisper; the first three seconds are where
  the app looks broken instead of busy.
- **Accessibility**: the dial has no `contentDescription` and the live readout is not a
  live region, so TalkBack reads nothing when the speed changes. Cheap to fix, and it is
  what "premium" means to a user who relies on it.
- **Settings is a very long single column** — collapsing the diagnostics/endpoint cards
  into expandable groups would make the page feel finished rather than endless.
- **Test + History are still single-column in landscape/tablet** (Dashboard was fixed).
- **Metric tiles could show the phase's peak** instead of only the final value (real data,
  already measured by the phase loop).

## Perspective 4 — QA / Performance

- **Battery/awake-time audit is still unverified** (needs a real device): 500 ms reads with
  the screen off, notification visible, and the `/proc/net/dev` fallback source. Target
  < 2 % overall. This is the one claim CI cannot check.
- **Instrumented coverage**: the live-endpoint smoke test exists; extend it with the new
  upload-acknowledgement path (must skip, never fail, without a connection).
- **ViewModel coverage** for `SpeedTestViewModel` (metered confirmation, fallback, abort)
  and `SettingsViewModel.updateTestServer` persistence.
- **Lint/NewApi**: the `onTimeout(startId, timeoutType)` override must stay warning-free
  (overrides are safe below the API level, but the lint report should confirm it), and the
  deprecated 1-arg override must remain for API < 35.
- **OEM matrix note** (XIAOMI/OPPO/vivo): if the OS kills the FGS mid-test, the test must
  fail cleanly with a message — extend the existing behaviour note with what the user sees.
- **`SpeedEngine` history/average bounds**: add a test that the 60-sample history and the
  60 s average window stay bounded under a long synthetic run (cheap regression guard).

## Prioritized for the next loop

1. **`SpeedTestViewModelTest` + injectable connectivity probe**; assert the metered
   confirmation, the fallback ordering and the abort path (correctness) — P0
2. **Upload server acknowledgement + stream ramp + in-flight byte flush** (measurement
   accuracy) — P0
3. **Settings endpoint card: auto-save on selection, show the saved value** (footgun) — P0
4. **`SpeedEngine` monotonic session duration + bounded-history test** (correctness) — P1
5. **Hindi pass 2**: extract Intro / Speed Test / Dashboard / History / Settings copy to
   `values/` + `values-hi/` (reach) — P1
6. **Premium**: ghost needle for the previous result, "vs last test" chip, shimmer for
   `WAITING FOR ENGINE`, accessibility labels/live region (feel) — P1
7. **Home-screen widget: today's total** (premium) — P2
8. **CSV/text export of the daily table via the share sheet** (value, private) — P2
9. **Two-column Test/History layouts + collapsible Settings groups** (polish) — P3
10. **Battery audit on a real device + OEM kill-mid-test note + extended instrumented
    upload-ack test** (QA) — P3

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
  suspension: if the OS froze the process, the meter shows 0 and flags PAUSED.
- Download and upload must stay independent: one coherent (rx, tx) read of one counter
  source per tick, two separate deltas. SpeedEngineTest.downloadAndUploadAreNeverMirrored
  must keep passing.
- The "add-on data cap / freeze" feature stays REMOVED. The app only ever warns
  (50/90/100 % of an optional daily mobile limit) and never blocks data.
- Keep it lightweight & private: no ACCESS_WIFI_STATE, no READ_PHONE_STATE, no
  analytics/accounts/third-party SDKs. INTERNET exists ONLY for the manual speed test and
  the UI must keep saying so. The test must keep asking before it spends metered data and
  must keep aborting when the transport changes mid-run.
- No new dependencies without a very strong reason (the speed test uses plain
  HttpURLConnection against a public endpoint; the endpoint set is TestServer +
  SpeedTestServers).
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent cards, floating
  glass-pill nav, accent picker, instrument gauge) — refine, don't replace.
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml) working: manual
  dispatch with build_type/version_name/version_code/tag/release_title/release_notes/
  create_release inputs; unit-test job; non-blocking lint job; keystore from
  KEYSTORE_BASE64 or a throwaway keytool key; APK uploaded to the build artifact and
  (optionally) the GitHub Release (overwrite the same tag).
- Keep the extracted resources (`values/strings.xml`, `values-hi/strings.xml`) in sync:
  every key added to `values/` must have a Hindi counterpart.
- All work commits to the current session branch. Do not create or push other branches.

PROCESS — run these steps IN ORDER and complete all of them:

1. AUDIT (fresh eyes). Read the ENTIRE codebase: every .kt file under app/src
   (main + test + androidTest), AndroidManifest.xml, app/build.gradle.kts, root
   build.gradle.kts, gradle/libs.versions.toml, gradle.properties, the workflow YAML, the
   resources (values, values-hi) and all docs. List concrete problems with file:line
   evidence: bugs, race conditions, permission/OS-compliance issues (Android 15/16 FGS
   budget), performance in the 500 ms hot path, dead code, stale docs, API misuse, missing
   tests. Verify the engine invariants still hold: 500 ms read loop, ~1 Hz publish, measured
   windows, counter-reset re-anchoring, per-source anchors, SourceHealth probing,
   transport pushed by NetworkCallback, real per-transport attribution for accounting, and
   the speed-test rules (endpoint resolution, metered confirmation, abort on transport
   change).

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) correctness of the speed engine, the speed test and daily accounting,
   (b) background/lifecycle robustness,
   (c) crash/compile safety,
   (d) then the items from docs/next-loop-prompt.md that are not yet done (ViewModel tests
   + injectable connectivity, upload server acknowledgement, stream ramp, in-flight byte
   flush, Settings endpoint auto-save, monotonic session duration, Hindi pass 2, ghost
   needle + "vs last test" chip + shimmer + accessibility, widget, export, two-column
   Test/History, collapsible Settings).

3. PREMIUM PASS. Improve the UI where it still feels dated compared to top speed-meter
   apps: motion (ghost needle, value landings, shimmer), hierarchy, spacing, micro-copy,
   accessibility labels and live regions. Keep it tasteful and consistent with the glass
   system. No new dependencies.

4. VERIFY. You cannot run an Android toolchain here, so: re-read every file you touched;
   check imports/overloads/types compile (Kotlin strict); validate the workflow YAML parses
   (no tabs, all inputs present); validate every XML resource parses and that values/ and
   values-hi/ have identical key sets; ensure tests match the real APIs (names, parameter
   order, defaults); run a crude brace/paren balance check over app/src. Then run
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
