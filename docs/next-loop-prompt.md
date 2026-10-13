# Next iteration — brainstorm + loop prompt

## How this was produced

Same process as the previous loops: one agent, four independent perspective rounds
(Product → Engineering → UI → QA/Performance), then merged into one prioritized list.
The loop prompt at the bottom re-runs the identical process so the project keeps
improving indefinitely.

## What this iteration changed (context for the next one)

- **Speed-test resilience**: multi-server failover (Cloudflare → Hetzner → OVH), a
  Settings picker, per-phase server reporting, metered-network confirmation, abort on
  transport change, no-connectivity guard, stream ramp-up and a server-checked upload.
- **Accounting honesty**: `SpeedEngine.drainAttributed()` owns the "already handed over"
  watermark; an implausible counter jump is refused like a backwards counter.
- **Lifecycle**: `onTimeout` now restarts with an exponential backoff instead of in a
  tight loop.
- **Engineering safety**: `SourceHealth` extracted and tested, DataStore round-trip
  tests, `GaugeScale`, network-gated instrumented smoke test, instrumented package-id
  test fixed.
- **Premium pass**: gauge with tick scale + needle sweep, phase cross-fade, value
  landings, share-result intent, two-column dashboard ≥ 600 dp, breathing "waiting"
  state, month-vs-month recap.
- **Localization**: every user-visible string moved into resources; **`values-hi`
  (Hindi) shipped**.

---

## Perspective 1 — Product

- **History has no depth.** The rows are not tappable: the app records months of real
  per-day data and shows it as a flat list. A per-day detail sheet (mobile vs Wi-Fi split,
  share of the month, best/worst day) is the single biggest "unlocks the data you already
  have" feature left.
- **A 6-month trend chart** (real Room sums, drawn with `Canvas`, no dependency) turns
  the History tab from a receipt into an insight. Same for a 7-day / 30-day average card.
- **Export / backup**: `ACTION_CREATE_DOCUMENT` + a CSV writer is ~40 lines, no
  dependency, and it is what makes people trust a data app with their history.
- **Home-screen widget** — still the best premium-feel item left. Correction to the
  previous loop: it does **not** need Glance. A `RemoteViews` + `AppWidgetProvider`
  ("today's total", refreshed by the existing 15 s accounting loop) adds zero
  dependencies.
- **Auto re-test** (opt-in): a WorkManager periodic test while charging on an unmetered
  network, with a notification only when the result drops below a threshold. This is the
  natural "ISPs degrade over the day" feature, and all the building blocks exist.
- **More locales**: Hindi is shipped; Marathi, Tamil, Telugu and Bengali are the next
  reach wins and are now *only* a translation task (no code changes — that was the whole
  point of the strings refactor).

## Perspective 2 — Engineering

- **VPN over cellular is attributed to "Wi-Fi"** (`resolveTransport()` returns `VPN`
  before it looks at the underlying transport, and `Transport.VPN.isWifiBucket` is true).
  On a phone using a VPN over mobile data, the daily split silently counts mobile bytes as
  Wi-Fi. `NetworkCapabilities` exposes both bits in the same callback, so the fix is to
  prefer `TRANSPORT_CELLULAR` when it is present alongside `TRANSPORT_VPN`.
- **Month prefixes are computed once in `HistoryViewModel.init`** — the "this month" and
  "last month" totals hold a stale `date LIKE 'YYYY-MM-%'` query if the app stays open
  across a month boundary (the day rollover *is* handled, the month rollover is not).
- **The DB is upserted every 15 s even when nothing changed** (integrated path). Skipping
  the write when the entity equals the previous one removes ~5 760 pointless Room
  transactions a day — the same "only touch the OS when something visible changed" rule
  the notification already follows.
- **`SpeedTestEngine` picks the first server that answers, not the best one.** Probing all
  candidates concurrently and choosing the lowest measured round trip would both pick a
  closer server and make the failover decision cost one RTT instead of N.
- **Upload still counts bytes the socket accepted.** `speed.cloudflare.com/__up` returns
  the number of bytes it received; parsing that (and preferring the server's count, which
  is strictly more honest) would close the last known optimism in the test.
- **Two "reset today" implementations** (`UsageResetter.resetToday` used by the
  view-models, `SpeedMeterService.resetToday`) can drift; the service one also owns live
  accumulators. Worth merging behind one entry point.
- **`publishLive` writes `_networkTypeFlow.value` unconditionally every publish.**
  `MutableStateFlow` does not dedupe equal values, so an unchanged transport re-emits once
  a second and re-composes its collectors. A one-line `if (value != current)` guard fixes
  it.
- **Dead code**: `SpeedEngine.resetSession` (test-only), `LiveSpeed.isIdle`,
  `SpeedSample.isZero`, `ThroughputResult.megabytesPerSecond`, `InterfaceStat.kind`
  (captured, never rendered).

## Perspective 3 — UI / Premium

- **Touch targets**: the `SHARE` / `CLEAR` / `TEST HISTORY` chips are ~24 dp tall — below
  the 48 dp guidance. They are the most-tapped small controls on the speed-test screen.
- **Accessibility copy**: several chips have `contentDescription = null` while carrying
  the only label on screen; a few status dots announce nothing.
- **Tablet/landscape is half-done**: only the dashboard goes two-column ≥ 600 dp; History
  and Settings are still a single narrow column on a 10-inch screen.
- **The gauge has no peak marker**: a small tick at the highest value reached during the
  phase would make the instrument read like a real one (and it is a real measurement
  already being tracked).
- **No "vs your last test" context** after a run — the app stores 20 results and shows
  none of them next to the new one.
- **Onboarding has no skip**: four pages, no escape hatch for a reinstall.

## Perspective 4 — QA / Performance

- **No test covers the integrated accounting path.** The accumulator maths lives inside
  `SpeedMeterService.accountUsage()`; extracting a pure `DailyAccumulator` (add deltas,
  midnight rollover, external reset detection, clamp at zero) would make it testable like
  `SpeedEngine` was.
- **`SpeedTestViewModel` is untested**: the metered-network gate, the abort-on-transport-
  change path and `shareText()` are all reachable under Robolectric (the connectivity
  checks go through `ConnectivityManager`, which Robolectric shadows).
- **`PreferencesManagerTest.defaultsAreSaneOnAFreshInstall`** depends on a pristine
  DataStore; if Robolectric ever shares the sandbox the defaults assertion flakes.
  Making each test write-then-assert (or clearing in `@Before`) removes the coupling.
- **The instrumented smoke test is not in CI** — it needs a device. A Gradle-managed
  device job would catch a broken public endpoint automatically, but it makes CI slower
  and flakier; a scheduled (nightly) workflow is the better trade.
- **Battery audit still unverified** (loop 2 flagged it, loop 3 did not run a device).
  This remains the one claim CI can never make for us.

## Prioritized for the next loop

1. **Correctness**: VPN-over-cellular attribution; month-prefix staleness; skip the
   redundant DB write (P0)
2. **History depth**: tappable day rows + per-day detail, 6-month trend chart, CSV export
   (P1)
3. **Speed-test accuracy**: lowest-RTT server selection, server-reported upload byte count
   (P1)
4. **Home-screen widget** with `RemoteViews` (no new dependency) (P1)
5. **Polish**: 48 dp touch targets, accessibility labels, two-column History/Settings,
   gauge peak marker, "vs last test", onboarding skip (P2)
6. **Engineering hygiene**: `DailyAccumulator` extraction + tests, one "reset today" path,
   dead-code removal, `networkTypeFlow` dedupe (P2)
7. **QA**: `SpeedTestViewModel` tests under Robolectric, nightly instrumented smoke job,
   real-device battery numbers (P3)

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
  HttpURLConnection against public test endpoints; the widget, if you build it,
  must use plain RemoteViews + AppWidgetProvider — not Glance).
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent cards,
  floating glass-pill nav, accent picker) — refine, don't replace.
- All user-visible copy stays in app/src/main/res/values/strings.xml (hi-rIN is
  shipped). New strings MUST be added to values/strings.xml and values-hi/
  strings.xml together; proper nouns and unit symbols stay translatable="false".
  Never show a raw English message from Context-free code — throw a marker and
  map it to a resource.
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
   (main + test + androidTest), AndroidManifest.xml, app/build.gradle.kts, root
   build.gradle.kts, gradle/libs.versions.toml, the workflow YAML, res/values
   (both locales) and all docs. List concrete problems with file:line evidence:
   bugs, race conditions, permission/OS-compliance issues (Android 15/16 FGS
   budget), performance in the 500 ms hot path, dead code, stale docs,
   API-misuse, missing tests. Verify the engine invariants still hold: 500 ms
   read loop, ~1 Hz publish, measured windows, counter-reset re-anchoring,
   per-source anchors with SourceHealth switching, transport pushed by
   NetworkCallback, real per-transport attribution with drain-once accounting.

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) correctness of the speed engine, the speed test and daily accounting
       (VPN-over-cellular attribution, month-boundary staleness, redundant
       writes),
   (b) background/lifecycle robustness (the onTimeout backoff must stay),
   (c) crash/compile safety,
   (d) then the items from docs/next-loop-prompt.md that are not yet done
       (history depth + per-day detail + CSV export + trend chart, best-server
       selection, server-reported upload bytes, RemoteViews widget, touch
       targets/accessibility, two-column History & Settings, DailyAccumulator
       extraction + tests, SpeedTestViewModel tests, nightly instrumented job).

3. PREMIUM PASS. Improve the UI where it feels dated compared to top
   speed-meter apps: motion, hierarchy, spacing, micro-copy, large-screen
   layouts, accessibility. Keep it tasteful and consistent with the glass
   system. No new dependencies.

4. VERIFY. You cannot run an Android toolchain here, so: re-read every file you
   touched; check imports/overloads/types compile (Kotlin strict); validate the
   workflow YAML parses; validate every res XML parses; make sure every
   R.string reference resolves in BOTH locales and that format strings match
   their arguments; ensure tests match the real APIs. Then run `git status` and
   confirm the tree is consistent. Update unit tests for anything you changed.

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
