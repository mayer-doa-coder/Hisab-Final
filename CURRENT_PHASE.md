# Current Phase

M0 — Setup (Steps 1–22)

Steps 1–22 all verified locally. One check is left, and only a push can do it: CI green on GitHub for these changes (Step 22).

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build the skeleton on both sides, including the specific things that are expensive to retrofit later: Bangla-first localization, minimal authentication with server-derived tenant identity, and the real sync foundation (contract + plumbing, not a throwaway version). Full reasoning: `DECISIONS.md` D014–D024.

## Current Step
Step 22, last check — push these changes and confirm the CI run is green (`gh run list --limit 1`). Also confirm the server job's "Unit tests" step now reports 68 tests, not 21. Then M0 is done, and the next step is M1, Step 23.

Done so far:
- Step 1 — repo folders created (`android/`, `server/`, `research/`, `scripts/`, `.github/`, plus `CONTRIBUTING.md` and `CHANGELOG.md`).
- Step 2 — 8 module folders under `server/src/modules/`: `auth/`, `shops/`, `products/`, `customers/`, `sales/`, `inventory/`, `baki/`, `sync/`. Empty (`.gitkeep` only), matching D005 and `docs/PRD.md` §23. **Check passed** — server builds with all 8 in place.
- Step 3 — Git config, formatting, and linting for both sides. **Check passed**, verified empirically (each tool was tested against deliberately bad code to confirm it actually catches problems, not just passes hollow):
  - Server: TypeScript + Fastify (D006). ESLint (`npm run lint`) + Prettier (`npm run format:check`) both genuinely catch real issues.
  - Android: Kotlin + Compose (D006), `minSdk = 26` / `compileSdk = targetSdk = 36` (D025, researched via Step 18 — pulled forward because Steps 3–4 were blocked without it). AGP's built-in `lintDebug` + Spotless (`spotlessCheck`) both genuinely catch real issues.
  - One real gap found and fixed: the `org.jlleitschuh.gradle.ktlint` Gradle plugin cannot register its check tasks under AGP 9's built-in Kotlin mode (confirmed both from the open upstream issue, ktlint-gradle#1008, and empirically — it silently checked nothing). Replaced with Spotless, which targets files by glob instead of relying on Kotlin-plugin source-set detection, and re-verified it actually works.
- Step 4 — CI. `.github/workflows/ci.yml` runs Android compile/unit-tests/lint and Server compile/unit-tests/lint as two jobs, plus the localization check. Two runner failures were hit and fixed: scripts run via `bash …` rather than `./…`, and a `chmod +x gradlew` step. This machine has `core.fileMode=false`, so git drops the executable bit; the modes are also set to `100755` in the index, but the CI `chmod` is what actually guarantees it.
- Step 5 — money/quantity/currency/time rules as real code on both sides (`server/src/domain/`, `android/.../domain/`): Money as integer poisha, Quantity as integer ×1000, explicit BDT currency, `occurredAt` separate from `serverReceivedAt`.
- Step 6 — IDs and conflict rule: `generateId()` (built-in UUID, no dependency) and the stale-revision write check.
- Step 7 — layering, deliberately kept small (D026, which replaces D023's longer mapper chain): Android is `ui/` + `domain/` + `data/`, server is `domain/` + `modules/<name>/`. One shape end to end; a mapper only where shapes actually differ. `HomeScreen` moved into `ui/` so the structure is real rather than aspirational.
- Step 8 — Bangla string resources in the default `values/`, English in `values-en/`.
- Step 9 — language switching via AndroidX `AppCompatDelegate` (D014, not a custom mechanism). Bangla is pinned on first run so the phone's own language never decides it (now done in `MainActivity.onCreate` — see Step 20 below for why `HisabApplication` couldn't do it). Persists via `AppLocalesMetadataHolderService` below API 33 and `localeConfig` on 33+.
- Step 10 — `scripts/check-localization.sh`, wired into CI. Enforces Bangla as primary, not just key parity: fails on a Bangla key with no English, an English key with no Bangla original (backwards for this project), an empty Bangla string, and a `values-bn/` folder (Bangla belongs in the default `values/`). Ignores commented-out strings. All five paths tested by deliberately breaking each one.
- Steps 11–12 — `server/src/modules/auth/`: `POST /auth/login` (email + password → session token, `crypto.scrypt` hashing, constant-time compare) and a `requireAuth` preHandler that every protected route uses. `GET /auth/me` proves it: shop_id always comes from the token, never a client-supplied field (D015) — verified by passing `?shop_id=shop-2` alongside a shop-1 token and confirming shop-1's data still comes back, on both the test suite and a real running server via curl. Users/shops are in-memory for now, seeded with two demo accounts (D027) — Postgres arrives with real entities in M1, not before.
  - One real bug caught and fixed: `requireAuth` was declared as a plain 2-argument sync function returning `void`, which is neither of Fastify's two valid preHandler shapes (`async (request, reply)` or `(request, reply, done)`). It silently hung forever on the success path — reproduced against a real running server with `curl`, not just in tests, before fixing it to `async`.
- Steps 13–15 — the sync foundation, first half.
  - Step 14 (envelope shape): `server/src/domain/syncEvent.ts` defines eventId, entityType, entityId, operation, payload, baseRevision, clientTimestamp. `SyncOutboxEntity` on Android mirrors it field-for-field (D026: one shape, no mapper needed since nothing diverges).
  - Step 15 (push endpoint): `POST /sync/push` behind `requireAuth` (extracted into its own `requireAuth.ts` once sync needed the same guard as auth did — D026, a real second use case showing up). An in-memory processed-event log makes it idempotent per D004 — pushing the same eventId twice, in two separate requests, only applies it once. Verified in the test suite (46 server tests total, 0 failures) and against a real running server via curl.
  - Step 13 (local tables): `SyncOutboxEntity`/`SyncOutboxDao` and `SyncMetadataEntity`/`SyncMetadataDao` (Room, in `data/sync/`), added via KSP 2.3.12 with Room 2.8.5 — first Room setup in this project. Schema exported to `android/app/schemas/` per PRD section 22. **Verified on a real physical phone** (Samsung Galaxy A15, Android 16), not just compiled: 9 instrumented tests, 0 failures. Covers the unique-eventId constraint, insertion order, status filtering, update, delete, and a real file-backed database that survives a close/reopen with data intact.
    - Two real bugs, caught only by actually running the tests on hardware, fixed in order: (1) a test method used an expression body ending in a call that returns `Boolean`, so Kotlin inferred the method's return type as `Boolean` instead of `Unit` — JUnit 4 requires `void`, so the test failed to even load until the return type was declared explicitly. (2) that same test then asserted the database was open immediately after being built, but Room opens its connection lazily, so the flag is genuinely false until a real query happens — the assertion was moved to after the first insert, where it's actually true.
  - A device-connection detour along the way: an Android emulator halted mid-boot twice and was abandoned in favor of the physical phone, which needed a full adb server restart to clear a stuck "unauthorized" state before it would accept the RSA debugging key.
- Steps 16–17 — the sync foundation, second half. `server/src/modules/sync/eventLog.ts` replaced `processedEvents.ts`, combining idempotency, conflict-checking, and pull into one store (D026 — these three interact too much to keep separate: a rejected event must never be marked processed or show up in a pull).
  - Step 17 (conflict check): an update/delete's `base_revision` is checked against a per-entity current-revision counter (using the existing `checkRevision` from `server/src/domain/revision.ts`); a stale one is rejected with `REVISION_CONFLICT` and left completely unapplied — not marked processed (so retrying the identical push is re-checked fresh, not silently treated as a duplicate) and not stored (so it can never appear in a pull). Exact Step 17 check — edit the same record twice with the same base revision, second one rejected — passes both as a unit test and through the real HTTP endpoint.
  - Step 16 (pull endpoint): `GET /sync/changes?after=<cursor>`, behind `requireAuth`, scoped to the requester's own shop (never another shop's events — D015). No cursor returns everything; the same cursor again returns nothing new; a non-numeric cursor is rejected with 400.
  - Verified at three levels: 64 server tests (25 new for this pair of steps), a full manual walkthrough against a real running server via curl (create → pull → conflicting edit → pull again, confirming the rejected edit never leaks into pull results), and — because `revision.ts` reverted to its fuller `checkRevision`/`Revisioned<T>` shape from an earlier simplification at some point outside this work — built directly on what's actually on disk rather than re-simplifying it again.
  - Two real bugs caught while writing this, both in test design, not production code: (1) a pull-endpoint test wrongly assumed the shop's event log started empty, when it is genuinely shared, process-wide state across every test in the file (matching real usage) — fixed by checking the pushed events are *present* in the pull result rather than the *only* things in it. (2) after deleting the old `processedEvents.ts`/`.test.ts`, `tsc`'s incremental build left the compiled output behind in `dist/`, so 5 dead tests from the deleted module kept silently running alongside the real ones — caught by noticing the test count didn't match expectations, fixed with a clean rebuild (`rm -rf dist`).
- Step 18 — `DECISIONS.md` D028 closes D022.
  - **minSdk:** 26, frozen permanently.
    - StatCounter CSV export, Bangladesh, mobile, August 2026: 98.04% of Android page views run Android 8.0+. Below 8.0 is 1.93%.
    - Going lower would gain at most 1.43% and would need desugaring for `java.time`, which the sync outbox already uses. Raising to API 28/29 would drop 2.4–5.5%, the oldest cheap phones.
    - Room/Compose/AppCompat were checked from their actual AAR manifests: they need API 23, so libraries don't force the choice.
  - **Low-end reference device:** Tecno Spark Go 2, 3 GB + 64 GB eMMC 5.1, Unisoc T7250, Android 15, 6.67" 720 × 1600, ৳9,999.
    - Tecno and Infinix (both Transsion) are the top brands by IDC shipments for Q3 2025 through Q2 2026.
    - Under-$100 phones are the biggest price band (41%).
    - The same chip is in the Infinix Smart 10 and Redmi A5, which are the named fallbacks.
  - Raw StatCounter CSVs saved in `research/performance/device-research/`, so the numbers stay checkable after StatCounter updates.
  - Gap found and closed: the reference device ships Android 15, so it can't prove minSdk 26 works. Step 107 now also runs the core flow on an API 26 system image.
- Step 19 — `docs/RESEARCH_PLAN.md` Research Data Plan rewritten to concrete rules. Answer to the Step 19 check: **held-out sentences may only be written by someone who is not the rule developer, has never seen the rule code, dev set, or system output, and knows Bangla shop talk.** The file stays with them, and only its hash is committed until Step 108. Also covers:
  - writer instructions (situations, not example sentences) and minimum sizes;
  - annotation fields and a second labeler on 20%;
  - forecasting sources (real / synthetic / public, always reported separately), anonymization, stockout-day marking;
  - the consent form and withdrawal;
  - the pilot device record;
  - dataset versioning, and deleting raw exports 12 months after release.
- Step 20 — the home screen opens in Bangla and switches to English and back. **Verified on the real phone** (Galaxy A15, Android 16, phone language set to English), after a full uninstall and fresh install: opens in Bangla → tap English → English → close and reopen, still English → tap বাংলা → Bangla → reopen, still Bangla. No crashes. Screenshots confirm Noto Sans Bengali for Bangla and Merriweather for English.
  - Real bug found and fixed: Bangla was pinned in `HisabApplication.onCreate`, where AppCompat's language API silently does nothing on Android 13+ (no Activity exists yet), so a new install on an English phone would have opened in English. Now pinned in `MainActivity.onCreate`; the empty Application class was removed.
  - The bug was found by the new `HomeScreenTest` (4 on-phone tests), which now guards it. The full on-phone suite is 13 tests, 0 failures, read from the result XML — not just "BUILD SUCCESSFUL".
  - Lint is now "No issues found": the unused "Change language" string is shown as a label, and `localeConfig` is marked Android 13+. Four time-dependent "newer version exists" checks are off, because they change on their own without any code change. Renaming `mipmap-anydpi-v26` as lint suggested broke the build (verified with a clean build), so it's kept and ignored in `android/app/lint.xml`.
- Step 21 — `GET /health` behind `requireAuth`. No token → 401 `AUTH_INVALID`; fake token → 401; real token without "Bearer " → 401; real token → 200 `{"status":"ok"}`. 4 tests, plus the same checked against a real running server with curl.
- Step 22 — clean-machine check.
  - **README was missing the instructions.** It had no install/build/run steps at all, so this check couldn't pass. Added "Run It Yourself": what to install, server steps with curl examples, Android build steps, phone steps, and what CI runs.
  - **Checked on a fresh copy** containing only the files git would give a new checkout (no `node_modules`, no `build/`, no `local.properties`), following only the README:
    - Server, with an empty npm cache: install, build, 68 tests / 0 failures, lint, format, start, `/` and `/health` with and without a token.
    - Android, with an empty Gradle folder (Gradle 9.6 and JDK 17 downloaded by themselves): localization check, APK built, 21 unit tests / 0 failures, lint "No issues found", Spotless, on-phone tests compile.
  - **Real CI bug found and fixed.** GitHub CI had been silently running only 21 of the 64 server tests. On Linux, `sh` expands the unquoted `dist/**/*.test.js` only one folder deep, so every auth, sync, and root-endpoint test was skipped while CI showed green. Confirmed from the actual CI log of the last green run ("tests 21"). The pattern is now quoted so Node expands it: 68 tests run under `sh` on both Node 22 (CI's version) and Node 24.
  - **CI now builds the real APK** (`assembleDebug`) and compiles the on-phone tests, instead of only compiling Kotlin.

## Allowed Right Now
Anything in M0 (Steps 1–22 of `docs/PHASE_GUIDE.md`) — Bangla/English setup, minimal auth, the sync foundation, domain conventions, device/minSdk research, the research data plan, one screen, one endpoint.

## Not Allowed Right Now
- Real product features (Product, Sale, Baki, etc.) — that's M1 (Step 23 onward), right after this.
- Full security hardening (rate limiting, token rotation, threat testing) — that's M7. M0 only needs the minimum auth so nothing later is built on a trust hole.

## Definition of Done for M0
Every check in Steps 1–22 passes — see `docs/PHASE_GUIDE.md` for each one individually.

## Next
Once Step 22 checks out, M1 — Product (Step 23 onward): build the Product feature end-to-end, the first feature to actually flow through the sync foundation built here.

## Update This File
Move "Current Step" forward as each step is checked off. Update the M-number at the top once a milestone finishes. This file always answers "what step am I on, right now" — the how lives in `docs/PHASE_GUIDE.md`.
