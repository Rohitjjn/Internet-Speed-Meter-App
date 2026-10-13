# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration was executed by **one agent** (no multi-model panel was available in the
sandbox), so the "multi-model brainstorm" below is a structured **multi-perspective
analysis** performed in a single pass: Product, Engineering, UI and QA/Performance each
got an independent round, then the findings were merged into a prioritized list. The loop
prompt at the bottom is designed so the *next* agent re-runs the exact same process
(audit → fix → enhance → premium polish → CI → brainstorm → next prompt), keeping the
project improving indefinitely.

## What this iteration changed (context for the next one)

- `SpeedEngine` (pure, unit-tested) now owns the measurement maths; the service runs a
  500 ms read loop and publishes ~1 Hz.
- Download/upload are independent by construction (the reported "same number" bug), the
  network type is actually published (it never was), source switching no longer flaps,
  long windows publish `0` + `PAUSED` instead of a fake average.
- The **active speed test** (ping/jitter/download/upload, Cloudflare endpoint, Mbps,
  history) exists as its own tab — `INTERNET` is now in the manifest, used only there.
- Diagnostics panel, unit switch, accent picker, limit vibration, animated digits,
  non-blocking CI lint job, and a much larger test suite.

---

## Perspective 1 — Product

- **Result sharing**: a speed test that cannot be shared is half a feature. A "Share
  result" `ACTION_SEND` text intent (no image, no SDK) is cheap and viral.
- **Auto re-test**: ISPs degrade over the day. A "remind me to test again" notification
  (or a WorkManager periodic test while charging, on Wi-Fi) would be opt-in value.
- **Test on mobile data warning**: an active test on a metered connection burns real
  megabytes (≈ 15-40 MB). A one-line "this will use ~30 MB of mobile data" confirmation
  is honest UX, not decoration.
- **Weekly/monthly recap**: the History screen has the data; a "this month vs last month"
  delta is a one-card addition.
- **Hindi (`hi-rIN`) localization** — still not done, and still the biggest reach win for
  the Indian user base. It is a *mechanical* but large refactor: every string is a
  Compose literal today. Do it as a dedicated loop (extract strings →
  `values/strings.xml` → `values-hi/strings.xml`), not as a side quest.
- **Home-screen widget** ("today's total", Room-backed) — still the best premium-feel
  item after the speed test.

## Perspective 2 — Engineering

- **`SpeedTestEngine`: no server fallback.** If `speed.cloudflare.com` is blocked
  (corporate DNS, some Indian ISPs), the test simply fails. Add a second endpoint plus a
  "custom test URL" advanced setting, and surface *which* server was used.
- **Upload accounting**: chunked POST counts bytes accepted by the socket, which can be
  slightly optimistic. Reading the response (`bytes received by the server`) and warning
  when a stream errors would tighten it.
- **Multi-stream tuning**: 4 download streams is right for fast lines; on a 1 Mbit/s line
  it adds connection setup noise. Consider ramping streams based on the first 2 s.
- **Test concurrency guard**: the test holds 3-7 sockets; it should be blocked while the
  device reports "no network" (currently it just fails after timeouts) and should stop
  automatically if the transport changes mid-test (Wi-Fi → mobile would otherwise bill
  the user's data for a download burst).
- **`SpeedEngine` hardening**: `attributedTotals()` returns a `HashMap` copy each call —
  fine at 15 s cadence, but the accounting loop could take deltas instead. Also worth
  adding a `maxSessionBytes` sanity ceiling so a kernel bug cannot produce absurd totals.
- **Notification**: `nm.notify` is skipped when nothing changed (done), but the *icon*
  bitmap cache is keyed on the string only; a unit switch mid-session invalidates it via
  `lastSpeedUnit` — verify that path on a real device.
- **Service `onTimeout` restarts itself immediately** — on Android 15+ this can burn the
  daily budget in a tight loop if the OS keeps refusing. Add a backoff (attempt counter +
  `delay` before the restart).

## Perspective 3 — UI / Premium

- **Gauge polish**: the speed-test gauge would feel like a real instrument with a needle
  sweep animation and a tick scale (0 → max) instead of only an arc fill.
- **Test-phases motion**: cross-fade the phase label and animate the metric tiles when a
  value lands (the download number currently pops in).
- **Hero card density**: with unit + 1-min average + session + window + two transport
  chips + sparkline, the card is at the edge of "too much". Consider collapsing
  "WINDOW / samples" into the diagnostics panel only.
- **Empty-and-loading states**: first run with the engine not yet started shows
  "WAITING FOR ENGINE" — a subtle shimmer would read as intent rather than emptiness.
- **Landscape / large screens**: the dashboard is a single scrolling column; a two-column
  layout above ~600 dp would look native on tablets.

## Perspective 4 — QA / Performance

- **Battery audit**: the read loop doubled to 2 Hz. Measure awake time on a Pixel
  (target < 2 % overall) with the screen off and with the notification visible — this is
  the one claim that cannot be verified in CI.
- **Instrumented test for `SpeedTestEngine`**: a small `androidTest` that runs a 2 s
  download against the real endpoint (network-gated) would catch endpoint/protocol
  breakage before users see it.
- **Test coverage gaps**: no test for `SpeedMeterService`'s source-switch decision or
  `PreferencesManager`'s DataStore round-trip (partly covered by
  `SpeedTestResult.encode/decode`). Extract the switch decision into a pure
  `SourceHealth` helper and test it.
- **R8/lint**: `proguard-rules.pro` still keeps `com.example.services.SpeedKeepAliveWorker`
  (it does exist) but the keeps should be re-checked against the minified release build;
  the new lint job will surface `NewApi` issues (e.g. `VIBRATOR_MANAGER_SERVICE`) — either
  annotate or accept as warnings.
- **OEM matrix notes**: document XIAOMI/OPPO/vivo behaviour for the *speed test* (they can
  kill the foreground service mid-test → the test must fail cleanly, which it does).

## Prioritized for the next loop

1. `SpeedTestEngine` resilience: second endpoint + "~N MB of data" confirmation +
   abort on transport change (correctness/cost) — P0
2. `SourceHealth` extraction + tests, `onTimeout` backoff, `PreferencesManager` round-trip
   test (engineering safety) — P0
3. Gauge needle/tick polish + phase cross-fade + share-result intent (premium feel) — P1
4. `hi-rIN` localization as a dedicated strings refactor (reach) — P1
5. Today-total home-screen widget (premium) — P2
6. Monthly recap card in History (value) — P2
7. Tablet/landscape layout + hero card density pass (polish) — P3
8. Instrumented (network-gated) speed-test smoke test (QA) — P3

---

## Prompt for the next loop

```text
You are continuing the development loop for this Android app (Internet
Speed Meter — Kotlin + Jetpack Compose + Room + WorkManager + DataStore, repo
root is the current working directory; work ONLY on the current session branch).

NON-NEGOTIABLES (never break these):
- Every number the user sees must come from a real OS counter (TrafficStats,
  /proc/net/dev, NetworkStatsManager) or from a real measurement the app
  performed (the opt-in speed test). No simulated data, no dummy seeding, no
  fabricated splits, no averaged fake across a suspension: if the OS froze the
  process, the meter shows 0 and flags PAUSED.
- Download and upload must stay independent: one coherent (rx, tx) read of one
  counter source per tick, two separate deltas. A regression test
  (SpeedEngineTest.downloadAndUploadAreNeverMirrored) must keep passing.
- The "add-on data cap / freeze" feature stays REMOVED. The app only ever warns
  (50/90/100% of an optional daily mobile limit) and never blocks data.
- Keep it lightweight & private: no ACCESS_WIFI_STATE, no READ_PHONE_STATE, no
  analytics/accounts/third-party SDKs. INTERNET exists ONLY for the manual
  speed test and the UI must keep saying so.
- No new dependencies without a very strong reason (the speed test uses plain
  HttpURLConnection against the public Cloudflare endpoint).
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent cards,
  floating glass-pill nav, accent picker) — refine, don't replace.
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml)
  working: manual dispatch with build_type/version_name/version_code/tag/
  release_title/release_notes/create_release inputs; unit-test job; the
  non-blocking lint job; keystore from KEYSTORE_BASE64 secret or throwaway
  keytool key; APK uploaded to the build artifact and (optionally) the GitHub
  Release (overwrite same tag).
- All work commits to the current session branch. Do not create or push other
  branches.

PROCESS — run these steps IN ORDER and complete all of them:

1. AUDIT (fresh eyes). Read the ENTIRE codebase: every .kt file under app/src
   (main + test), AndroidManifest.xml, app/build.gradle.kts, root
   build.gradle.kts, gradle/libs.versions.toml, the workflow YAML, and all
   docs. List concrete problems with file:line evidence: bugs, race conditions,
   permission/OS-compliance issues (Android 15/16 FGS budget), performance in
   the 500 ms hot path, dead code, stale docs, API-misuse, missing tests.
   Verify the engine invariants still hold: 500 ms read loop, ~1 Hz publish,
   measured windows, counter-reset re-anchoring, per-source anchors, transport
   pushed by NetworkCallback, real per-transport attribution for accounting.

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) correctness of the speed engine, the speed test and daily accounting,
   (b) background/lifecycle robustness (incl. onTimeout backoff),
   (c) crash/compile safety,
   (d) then the items from docs/next-loop-prompt.md that are not yet done
   (speed-test resilience + data-cost confirmation + abort on transport change,
   SourceHealth extraction, gauge polish, share result, hi-rIN localization,
   widget, monthly recap, tablet layout, instrumented smoke test).

3. PREMIUM PASS. Improve the UI where it feels dated compared to top
   speed-meter apps: motion (needle sweep, phase cross-fade, value landings),
   hierarchy, spacing, micro-copy. Keep it tasteful and consistent with the
   glass system. No new dependencies.

4. VERIFY. You cannot run an Android toolchain here, so: re-read every file you
   touched; check imports/overloads/types compile (Kotlin strict); validate the
   workflow YAML parses; validate XML parses; ensure tests match the real APIs.
   Then run `git status` and confirm the tree is consistent. Update unit tests
   for anything you changed.

5. DOCS. Update README.md, CHANGELOG.md (new dated entry),
   docs/project-blueprint.md and docs/database-schema.md so they describe the
   app AS IT IS NOW, not as it was.

6. NEXT LOOP. Re-run the same multi-perspective brainstorm (Product,
   Engineering, UI, QA/Performance — independent rounds, then merged
   priorities), rewrite docs/next-loop-prompt.md with the new findings and a
   NEW next-loop prompt in one code block that instructs the same process to
   repeat again (audit → fix → premium → verify → docs → brainstorm → next
   prompt), preserving the non-negotiables and the "repeat the loop"
   instruction.

7. COMMIT. Stage and commit everything on the current session branch with a
   clear message (e.g. "loop N: <top 3 changes>"), then push.

End with a short summary (Hinglish is fine): what you audited, what you fixed,
what you improved, what you deliberately left for later, and the top 3 items
from the new next-loop prompt.
```
