# Next iteration — brainstorm + loop prompt

## How this was produced

This iteration was executed by **one agent** (no multi-model panel was
available in the sandbox), so the "multi-model brainstorm" below is a
structured **multi-perspective analysis** performed in a single pass:
Product, Engineering, UI, and QA/Performance each got an independent
round, then the findings were merged into a prioritized list. The loop
prompt at the bottom is designed so the *next* agent re-runs the exact same
process (audit → fix → enhance → premium polish → CI → brainstorm → next
prompt), keeping the project improving indefinitely.

## Perspective 1 — Product

- **One-tap "speed test" mode**: real meters (Speedtest, nPerf) also run an
  active benchmark. This app is intentionally permission-light (no
  `INTERNET`), so an active test would be an *opt-in* feature. **Needs user
  decision** — default recommendation: keep the app passive-only.
- **Session stats**: "this session downloaded X at avg Y" — cheap, useful,
  no new permissions.
- **Connection quality hints**: slow-connection warning, current network
  sub-type (LTE/5G) when available via `SignalStrength`.
- **Hindi localization**: user base is Indian; strings are all in
  `strings.xml`-style literals — a `hi-rIN` values folder is a clean win.
- **Home-screen widget**: "today's total" data tile (Room-backed, no live
  speed) — premium feel, no battery cost.

## Perspective 2 — Engineering

- **`NetworkCallback` instead of 1 Hz transport polling**: register a
  `ConnectivityManager.NetworkCallback` in the service so transport
  changes (Wi-Fi ⇄ mobile) are attributed to the *correct* counter within
  milliseconds, not up to a second late.
- **Average speed (1 min)** alongside peak on the hero card.
- **Persist last speed** so the notification icon is meaningful immediately
  on service start (currently shows 0 for the first second).
- **Unit-test the accounting logic**: `ResetOffsets.appliesTo`,
  `TransportBytes` arithmetic, midnight rollover, external-delete reseed.
- **R8 report check**: verify no warnings on the release build in CI and
  that WorkManager/Room keep rules are the *only* keeps needed.

## Perspective 3 — UI / Premium

- **Animated speed digits**: a subtle tick/roll animation on the hero
  number when it changes (currently instant swap — feels static at high
  speeds).
- **Limit alerts**: vibration on the 100 % alert + a persistent banner
  instead of only a dialog.
- **Accent picker**: green/blue/violet accent for the glass theme (DataStore
  key `accent_color`).
- **Privacy page** in onboarding (what the app reads and never sends).
- **Sparkline polish**: gradient under the upload line too, tooltip on the
  last sample.

## Perspective 4 — QA / Performance

- **Compose UI smoke test** (Robolectric): dashboard renders, sparkline +
  both usage cards + bottom nav present; settings limit save round-trips.
- **Battery audit checklist**: verify the 1 Hz loop on `Dispatchers.Default`
  keeps CPU awake time < ~2 % (measure on a Pixel, not a benchmark toy).
- **OEM matrix notes**: document XIAOMI/OPPO/vivo kill behaviour and the
  Settings → Autostart buttons that already exist.
- **Lint the release build** in CI (`./gradlew :app:lintRelease` as a
  non-blocking job) to catch permission/typo regressions early.

## Prioritized for the next loop

1. `NetworkCallback`-based transport tracking (accuracy) — P0
2. Session total + 1-min average on hero card (value) — P0
3. Animated speed digits + limit vibration (premium feel) — P1
4. Accounting unit tests + Compose UI smoke test (safety) — P1
5. `hi-rIN` localization (reach) — P2
6. Today-total home-screen widget (premium) — P2
7. Accent picker (personalization) — P3
8. Lint job in CI — P3

---

## Prompt for the next loop

```text
You are continuing the development loop for this Android app (Internet
Speed Meter — Kotlin + Jetpack Compose + Room + WorkManager, repo root is
the current working directory; work ONLY on the current session branch).

NON-NEGOTIABLES (never break these):
- Every number the user sees must come from a real OS counter
  (TrafficStats, /proc/net/dev, NetworkStatsManager). No simulated data,
  no dummy seeding, no fabricated splits. If the OS was suspended, the
  meter shows 0, not an averaged fake.
- The "add-on data cap / freeze" feature stays REMOVED. The app only ever
  warns (50/90/100% of an optional daily mobile limit) and never blocks data.
- Keep it lightweight & private: no INTERNET, ACCESS_WIFI_STATE or
  READ_PHONE_STATE permissions; no third-party SDKs; everything on-device.
- Keep the glassy GPT-style theme (light + dark, glass cards, ink accent
  cards, floating glass-pill nav) — refine, don't replace.
- Keep the GitHub Actions workflow (.github/workflows/android-build.yml)
  working: manual dispatch with build_type/version_name/version_code/tag/
  release_title/release_notes/create_release inputs; keystore from
  KEYSTORE_BASE64 secret or throwaway keytool key; APK uploaded to the
  build artifact and (optionally) the GitHub Release (overwrite same tag).
- All work commits to the current session branch. Do not create or push
  other branches.

PROCESS — run these steps IN ORDER and complete all of them:

1. AUDIT (fresh eyes). Read the ENTIRE codebase: every .kt file under
   app/src (main + test), AndroidManifest.xml, app/build.gradle.kts,
   root build.gradle.kts, gradle/libs.versions.toml, the workflow YAML,
   and all docs. List concrete problems with file:line evidence:
   bugs, race conditions, permission/OS-compliance issues (Android 15/16
   FGS budget, background-start restrictions), performance issues in the
   1 Hz hot path, dead code, stale docs, API-misuse, missing tests.
   The real-time speed path (SpeedMeterService + TrafficCounterReader)
   must tick at a steady 1 Hz and publish real bytes/second — verify the
   deadline scheduling, source health check, and window guard still hold.

2. FIX. Implement the highest-value fixes from the audit. Prioritize:
   (a) correctness of the speed engine and daily accounting,
   (b) background/lifecycle robustness, (c) crash/compile safety,
   (d) then the items from docs/next-loop-prompt.md that were not yet done
   (NetworkCallback transport tracking, session total + 1-min average,
   animated digits, limit vibration, accounting + UI smoke tests,
   hi-rIN localization, widget, accent picker, CI lint job).
   If this loop already did some of those, pick the next unimplemented ones.

3. PREMIUM PASS. Improve the UI where it feels dated compared to top
   speed-meter apps: motion (speed digit animation, snappier page
   transitions), hierarchy, spacing, micro-copy. Keep it tasteful and
   consistent with the glass system. No new dependencies.

4. VERIFY. You cannot run an Android toolchain here, so: re-read every
   file you touched; check imports/overloads/types compile (Kotlin strict);
   validate the workflow YAML parses; validate XML parses; ensure tests
   match the real APIs. Then run `git status` and confirm the tree is
   consistent. Update unit tests for anything you changed.

5. DOCS. Update README.md, CHANGELOG.md (new dated entry),
   docs/project-blueprint.md and docs/database-schema.md so they describe
   the app AS IT IS NOW, not as it was.

6. NEXT LOOP. Re-run this same multi-perspective brainstorm (Product,
   Engineering, UI, QA/Performance — independent rounds, then merged
   priorities), rewrite docs/next-loop-prompt.md with the new findings
   and a NEW next-loop prompt in one code block that instructs the same
   process to repeat again (audit → fix → premium → verify → docs →
   brainstorm → next prompt), preserving the non-negotiables and the
   "repeat the loop" instruction.

7. COMMIT. Stage and commit everything on the current session branch with
   a clear message (e.g. "loop N: <top 3 changes>"), then push.

End with a short summary (Hinglish is fine): what you audited, what you
fixed, what you improved, what you deliberately left for later, and the
top 3 items from the new next-loop prompt.
```
