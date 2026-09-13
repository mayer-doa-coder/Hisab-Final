# Current Phase

M0 — Setup (Steps 1–22)

In progress — Steps 1–15 and 20 checked off. Remaining: 16–19, 21, 22.

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build the skeleton on both sides, including the specific things that are expensive to retrofit later: Bangla-first localization, minimal authentication with server-derived tenant identity, and the real sync foundation (contract + plumbing, not a throwaway version). Full reasoning: `DECISIONS.md` D014–D024.

## Current Step
Step 16 — Build the pull endpoint (GET /sync/changes?after=<cursor>). Steps 18–19 are the device/minSdk and research-data decisions — D025 already covers minSdk.

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
- Step 9 — language switching via AndroidX `AppCompatDelegate` (D014, not a custom mechanism). Bangla is pinned on first run in `HisabApplication` so the phone's own language never decides it. Persists via `AppLocalesMetadataHolderService` below API 33 and `localeConfig` on 33+.
- Step 10 — `scripts/check-localization.sh`, wired into CI. Enforces Bangla as primary, not just key parity: fails on a Bangla key with no English, an English key with no Bangla original (backwards for this project), an empty Bangla string, and a `values-bn/` folder (Bangla belongs in the default `values/`). Ignores commented-out strings. All five paths tested by deliberately breaking each one.
- Steps 11–12 — `server/src/modules/auth/`: `POST /auth/login` (email + password → session token, `crypto.scrypt` hashing, constant-time compare) and a `requireAuth` preHandler that every protected route uses. `GET /auth/me` proves it: shop_id always comes from the token, never a client-supplied field (D015) — verified by passing `?shop_id=shop-2` alongside a shop-1 token and confirming shop-1's data still comes back, on both the test suite and a real running server via curl. Users/shops are in-memory for now, seeded with two demo accounts (D027) — Postgres arrives with real entities in M1, not before.
  - One real bug caught and fixed: `requireAuth` was declared as a plain 2-argument sync function returning `void`, which is neither of Fastify's two valid preHandler shapes (`async (request, reply)` or `(request, reply, done)`). It silently hung forever on the success path — reproduced against a real running server with `curl`, not just in tests, before fixing it to `async`.
- Steps 13–15 — the sync foundation, first half.
  - Step 14 (envelope shape): `server/src/domain/syncEvent.ts` defines eventId, entityType, entityId, operation, payload, baseRevision, clientTimestamp. `SyncOutboxEntity` on Android mirrors it field-for-field (D026: one shape, no mapper needed since nothing diverges).
  - Step 15 (push endpoint): `POST /sync/push` behind `requireAuth` (extracted into its own `requireAuth.ts` once sync needed the same guard as auth did — D026, a real second use case showing up). An in-memory processed-event log makes it idempotent per D004 — pushing the same eventId twice, in two separate requests, only applies it once. Verified in the test suite (46 server tests total, 0 failures) and against a real running server via curl.
  - Step 13 (local tables): `SyncOutboxEntity`/`SyncOutboxDao` and `SyncMetadataEntity`/`SyncMetadataDao` (Room, in `data/sync/`), added via KSP 2.3.12 with Room 2.8.5 — first Room setup in this project. Schema exported to `android/app/schemas/` per PRD section 22. **Verified on a real physical phone** (Samsung Galaxy A15, Android 16), not just compiled: 9 instrumented tests, 0 failures. Covers the unique-eventId constraint, insertion order, status filtering, update, delete, and a real file-backed database that survives a close/reopen with data intact.
    - Two real bugs, caught only by actually running the tests on hardware, fixed in order: (1) a test method used an expression body ending in a call that returns `Boolean`, so Kotlin inferred the method's return type as `Boolean` instead of `Unit` — JUnit 4 requires `void`, so the test failed to even load until the return type was declared explicitly. (2) that same test then asserted the database was open immediately after being built, but Room opens its connection lazily, so the flag is genuinely false until a real query happens — the assertion was moved to after the first insert, where it's actually true.
  - A device-connection detour along the way: an Android emulator halted mid-boot twice and was abandoned in favor of the physical phone, which needed a full adb server restart to clear a stuck "unauthorized" state before it would accept the RSA debugging key.

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
