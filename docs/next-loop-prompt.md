# Next iteration — loop 8

## Loop 7 (2026-10-14): what actually shipped

One agent performed a targeted audit of the reported dark/status-bar regressions and the
highest-value next-loop items. This is a **single-agent, multi-perspective review**, not a
claim that several models reviewed the app. The baseline (`01741c8`) had 118 passing tests
and no lint errors. No local Java/Android toolchain or emulator was available; verification
uses the repository's GitHub Actions checks, not an unperformed device run.

### Findings with baseline evidence

| Baseline file:line | Problem | Change |
|---|---|---|
| `services/SpeedMeterService.kt:913` | `drawColor(TRANSPARENT)` uses SRC_OVER, so it does not erase previous digits. Two reused bitmaps eventually look like a box. | `SpeedIconRenderer`: fresh transparent 96×96 mask only on label changes, never mutate a published bitmap; fitted number/unit rows. Native pixel tests exercise 100 updates. |
| `MainActivity.kt:56` (`enableEdgeToEdge` call) | System-bar contrast was selected once from the system, independently of the app's dark override. | Reapply `SystemBarStyle` from the effective app theme; API 26/29/35 contrast tests. |
| `ui/components/Glass.kt` (`AppBackground`) | Only muted coloured glows in dark mode; missing the requested soft white rounded backdrop. | Restored a static translucent white rounded glow below the status area. |
| `ui/screens/MainScreen.kt:254` | All content insets zeroed; fixed 24 dp headers can overlap clocks/cutouts. | Top/horizontal safe-drawing insets, consume padding once, IME padding in content; nav still handles the bottom. |
| `ui/viewmodel/SpeedTestViewModel.kt:159–166` | Only the running test was guarded, not the asynchronous metered-confirmation lookup or already-open prompt. | Dedicated cancellable confirmation Job + pending guard + cancellation check; stale confirm does nothing. |
| `util/CsvExport.kt:23` | Old daily export files never removed. | Best-effort next-export cleanup, own CSV files only, older than 24 h. |
| `ui/theme/Theme.kt` (`LocalReducedMotion`) | Only the in-app preference was respected; several decorative animations ignored even that. | Observe system animation scale, OR it with the user setting, show the forced state; finite motion helper used across navigation, gauge, metrics and expandable cards. |
| `tile/SpeedMeterTile.kt` (`onStartListening`) | Always read Room despite a fresher same-day accounting snapshot; captured running flag could be stale after IO. | Prefer same-day in-memory accounting, persisted fallback; re-read running state at render. |

No changes to counter maths, upload acknowledgement logic, persistence schema, permissions,
signing, dependency versions or release workflow. No simulated values introduced.

### Verification and remaining acceptance

- First implementation batch: **128 tests passed**, lint **0 errors**, 25 existing warnings.
- Final code batch (`dd1f549`, Actions run `38518726888`): **130 tests passed, 0 failed,
  0 skipped; lint 0 errors**, the same 25 existing warnings. Both readable check logs
  reviewed after completion.
- XML and workflow YAML parse, EN/HI keys/format arguments (268), `git diff --check`,
  check-publisher self-test run locally.
- **Not device-tested**: OEM status-bar rendering, app/system theme combinations while
  changing live speeds, AOD, 3-button versus gesture navigation, cutouts, IME and font-scale
  geometry. Native bitmap testing proves mask correctness, not a particular ROM's layout.
- **Deferred deliberately**: dependency refresh, 4×1 timestamped speed widget, timer pause,
  CSV import, daily digest, service timeout/screen lifecycle tests, emulator install/update
  workflow, full 1.5× native screen render suite. These are roadmap items, not shipped UI.

## Fresh perspectives

### Product
1. **Timestamped last-known speed before a new speed widget.** `last_speed_down/up` has no
   timestamp and the service still restores it on startup. Old values must not masquerade
   as live readings after a long stop. Add an atomic snapshot with measurement time, define
   expiry, and only then ship the 4×1 widget. Show date as well as time for older samples.
2. **Pause timer / paused-since** remains useful, but timer expiry cannot bypass Android's
   background foreground-service restrictions. A refusal must produce a tap-to-resume notice.
3. CSV restore is valuable for users changing signing keys; stage preview/validation and
   never overwrite today's active accounting row. Digest notifications stay strictly opt-in.

### Engineering
1. **Service-level seams and races.** Test timeout stop/schedule/notify, screen receiver
   cadence, user-pause safety and startup/shutdown writes. `MeterControl.isPausedByUser`
   can load concurrently with pause/resume; detached `onDestroy` speed writes also deserve
   ordering tests before adding snapshot timestamps.
2. **Notification invariants.** Keep alpha-mask ownership and bounded allocation explicit.
   Never reintroduce transparent SRC_OVER as an erase, adaptive icons, hard-coded dark
   notification layouts, or a live-speed widget without freshness metadata.
3. **Dependency refresh separately**, one related group per green CI commit. Confirm
   published versions and toolchain requirements rather than copying speculative numbers.
   Keep the stable signing certificate; add explicit shared/secret selection only with
   fail-fast validation and an install-over-update test.
4. Cache cleanup is currently synchronous with the small CSV export. If export histories
   become large, move IO off the main thread and use unique filenames so repeat same-day
   shares cannot replace a URI already being read.

### UI / accessibility
1. Real-device matrix for **app light/dark × OS light/dark**, 60+ changing speed updates,
   byte/bit units, foreground/background and screen-off/on. SystemUI owns the shade; explain
   OEM notification-icon settings rather than pretending the app controls them.
2. New insets need a 3-button/gesture + cutout/landscape + IME test at 1.0/1.3/1.5 font scale.
   Native screen render tests should assert geometry, not only semantics-node existence.
3. Keep the restored white light subtle and static. Verify contrast on every accent; the
   wallpaper swatch should never determine notification glyph tint.
4. A compact hero-left / usage-right phone landscape layout and static widget previews
   (dashes, not fake numbers) are the next premium additions.

### QA / performance
1. Existing native tests render pixels, but do not emulate OEM icon shrinking/tinting.
   Add instrumentation capture on API 26/34/35+ and physical devices where possible.
2. Add service tests before touching lifecycle paths. Preserve the 20 engine tests for
   independent directions, measured windows and 2 s screen-off cadence.
3. Add dispatch-only emulator testing for signed install → update, timeout/pause behaviour
   where supported, and R8 startup. Do not change signing to make an update test pass.
4. Measure allocation/wakeups on a device: new icon allocation is ~36 KiB per changed label,
   no allocation for unchanged labels. Do not trade back correctness for unsafe buffer reuse.

## Merged priorities

1. **P0:** device visual regression matrix + native font/IME/inset tests.
2. **P1:** timestamped speed snapshot, startup freshness, then 4×1 widget (never timestamp a
   stale sample with the time it was rendered).
3. **P1:** service lifecycle/pause race tests and fixes, then emulator install/update CI.
4. **P2:** dependency groups verified individually, private signing selector, R8 review.
5. **P2:** timer pause, CSV import, landscape layout, opt-in digest and widget previews.

## Prompt for the next loop

```text
Continue Internet Speed Meter (Kotlin + Compose + Room + WorkManager + DataStore).
Work only on the current session branch. Read docs/next-loop-prompt.md first.

NON-NEGOTIABLES
- Real OS counters or real opt-in test measurements only. Never seed dummy history,
  fabricate directions or average across suspension. One coherent rx/tx source read,
  independent deltas, measured windows, reset re-anchoring and per-source anchors.
- Keep 500 ms on-screen / 2 s screen-off reads, ~1 Hz publish, transport callbacks and
  15 s accounting. Preserve the direction, pause/window and cadence regression tests.
- No add-on cap/freeze. Daily/monthly mobile limits only warn at 50/90/100 percent.
- Privacy: no analytics/accounts, READ_PHONE_STATE or ACCESS_WIFI_STATE. INTERNET is for
  manual tests only. No scheduled tests. Ask before metered transfers; guard pending
  confirmation requests; abort on network change; require upload acknowledgement and
  last-ack window when possible (flag send-side fallback). AUTO fallback only when the
  latency endpoint is unreachable, never after already spending download/upload data.
- No new dependency without strong justification. Keep HttpURLConnection, RemoteViews
  and FileProvider. Every displayed last-known speed must carry its measurement timestamp;
  old unknown-time preferences are not fresh measurements.
- Preserve glass light/dark themes, restored white rounded ambient glow, accent picker,
  ink cards, floating pills, gauge/ghost needle and effective system/user reduce-motion.
  System-bar contrast follows APP theme. Notification small icons are transparent white
  alpha masks: never mutate published bitmaps or use transparent SRC_OVER as a clear.
- Keep user pause durable; every restart asks MeterControl. Distinguish OS pause from
  paused-by-you. Both onTimeout overloads stop immediately and leave retries to WorkManager;
  refused background starts show a truthful tap-to-resume notice.
- Every user-facing string in EN + HI resources with identical keys/arguments; no text
  below 10 sp. Keep safe system/keyboard insets and accessibility state descriptions.
- Keep push-triggered tests/lint and readable check runs. Manual build/release workflow
  continues signing debug AND release with one stable certificate (secret/private/shared
  resolution unchanged). Never introduce throwaway signing or quietly change certificates.
  APK signature verification and optional release asset overwrite must keep working.
- Commit/push ONLY the current session branch; do not create/switch/push another branch.

PROCESS (repeat this whole loop)
1. AUDIT: read latest CI checks first. Review code, tests, manifest/resources, build config,
   workflow, signing and docs, prioritizing the actual user reports and merged priorities
   above. Record concrete file:line evidence. Be honest about review/device coverage;
   do not claim an exhaustive audit or multiple model reviewers if neither happened.
2. FIX: start from green and implement the highest-value correctness and lifecycle fixes,
   then a coherent subset of the remaining roadmap. Push each coherent code batch and
   read Unit test log / Lint log check output before continuing. Fix red/flaky checks.
3. PREMIUM: refine rather than replace the theme. Verify app/OS contrast combinations,
   mask stability through repeated updates, accessible font scales, cutouts/IME/landscape
   and system/user reduced motion. Keep sampling timings independent from UI animation.
4. VERIFY: run local Gradle unit tests/lint if possible. Otherwise validate XML, EN/HI
   format parity, YAML, imports and diffs; run publish_check.py --self-test, then use
   GitHub Actions for compilation, tests and lint. Native pixel tests are not device
   acceptance: explicitly list untested ROM/device behaviour. Read every final check.
5. DOCS: update README, CHANGELOG, blueprint and schema to reflect shipped behaviour,
   test results and deferred features, not aspirational claims.
6. NEXT LOOP: independently review Product, Engineering, UI and QA/Performance viewpoints,
   merge priorities, then rewrite this file with new evidence and one new prompt block
   repeating audit → fix → premium → verify → docs → brainstorm → next prompt.
7. COMMIT: stage, commit and push to the current session branch, confirm a clean tree and
   green checks. End with a short Hinglish summary of fixes, verification, deliberately
   deferred scope and the top three next priorities. Never say a device test ran if not.
```
