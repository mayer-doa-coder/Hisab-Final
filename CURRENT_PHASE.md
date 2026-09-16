# Current Phase

M2 — Sale and Stock (Steps 35–47)

Steps 35–38 are built. M0 (Steps 1–22) is finished and verified locally; its one remaining check needs a push: CI green on GitHub. M1 (Steps 23–34) is built, with two checks still owed — listed under "Owed from M1" below.

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build Sale and Stock end to end — the rules first, then the local tables, then the screens and the backend — as ledgers that are only ever added to, never edited.

## Current Step
Steps 35–38 are built and checked. Step 39 (New Sale screen — cash) is next.

One check is owed on Step 38: `connectedDebugAndroidTest` with the phone attached, for `SaleStockDaoTest` (14 tests) and `SaleStockMigrationTest` (2). They compile, and the domain rules underneath them pass on the JVM, but the insert/read check Step 38 asks for is a real run on hardware.

One repository chore before the next push: `android/app/schemas/com.hisab.app.data.HisabDatabase/3.json` is new and untracked, so `scripts/check-room-schema.sh` fails until it is committed. That is the check doing its job, not a defect.

### Owed from M1
1. **Step 28** — add, edit and search on the phone with airplane mode on. Nothing in the local path touches the network, but the check is a real run, not an argument.
2. **Step 33 by hand** — a product made on the server appearing on the phone after a sync. Steps 32 and 34 were already seen by hand (a phone-made product reached the server; an edit made on the PC won over a stale phone edit).

Device suite at the end of M1: **61 tests, 0 failures, 0 skipped** on the Galaxy A15, including three end-to-end tests that ran against the real server and Postgres over `adb reverse`.

### Owed from M0
- Push, then confirm CI is green and that the server job's "Unit tests" step reports the full count, not 21 (Step 22).

Two CI failures were fixed on the way here, neither caused by this work:
- The Android job died inside `android-actions/setup-android@v4`, which still asks for the legacy `tools` package that Google no longer serves. The action is now told to install nothing (`packages: ''`) and `platform-tools` moved into the explicit, pinned `sdkmanager` step.
- `npm run format:check` was failing on two committed server sync files Prettier had never been run over. Reformatted; whitespace only.

## M2 — Sale and Stock, done so far
- Step 35 — the stock functions (`domain/Stock.kt`, `server/src/domain/stock.ts`): `restock`, `sell`, `returnStock`, `damage`, `correctStock`, `calculateCurrentStock`, plus `stockShortfall`. Stock is the sum of movements and nothing else (D020) — there is no stock column anywhere, on either side. A correction is a shelf count (what is actually there) rather than a typed difference, so the number a person enters is one they can see. A sale is never blocked by low stock and stock may go negative (D031, researched against how commercial POS systems handle this).
- Step 36 — the sale functions (`domain/Sale.kt`, `server/src/domain/sale.ts`): `calculateLineTotal`, `calculateSaleTotal`, `completeCashSale`, `completeCreditSale`, `reverseSale`. A line total rounds half away from zero, per line, so a reversal gives back exactly what it charged and the printed lines add up to the printed total (D032). A completed sale is one value holding the sale, its lines, its stock movements and (on credit) its baki entry, and that value refuses to exist if any of them is missing or disagrees — D021 enforced by shape rather than by remembering. A reversal is a second, opposite sale, never an edit (D033).
- Step 37 — one fixture file, `fixtures/m2_sale_stock.tsv`, read at run time by both suites (D034). 29 cases covering line totals, sale totals, stock balances, corrections, credit sales and reversals. **Proved by breaking it**: a number was changed in the file and both the Kotlin and the TypeScript suite failed on that case, then passed again when it was restored.
- Step 38 — `sale`, `sale_item` and `stock_movement` tables in Room (database version 3, hand-written `MIGRATION_2_3`, no destructive fallback), with schema 3 exported. `sale_item` has a foreign key to `sale`, so a line cannot exist without its sale. Current stock is a `SUM` query with `COALESCE`, so a product that never moved reads as zero — D020 written in SQL. There is no update and no delete on either DAO: confirmed history is never rewritten.
- Test counts after this work: **100 Android unit tests** and **142 server tests**, 0 failures on both (read from the result XML, not from "BUILD SUCCESSFUL"). Android lint reports no issues; Spotless and ESLint pass.

## M1 — Product, done so far
- Steps 23–24 — `product` table in Room (`data/product/`), reached only through `ProductRepository`, which owns the rules: device-made id (D018), revision starting at 1, a stale-revision write rejected instead of applied (D017), deletion as a tombstone, and aliases trimmed and de-duplicated. Database version 2 with a hand-written migration (no destructive fallback), matching the exported schema exactly.
- Step 25 — Product list screen, reading the database as a live query, so a write appears by itself.
- Step 26 — Add/Edit screen: name, other names, unit, selling price, optional purchase price, plus deactivate/reactivate and delete. Saving writes locally and returns; nothing waits on a network.
- Step 27 — search over name and aliases, in the same query that lists products.
- Step 29 — `product` in Postgres: `server/migrations/001_create_product.sql`, applied by a small runner that records what it has applied (`npm run migrate`). **Checked on a real empty database**: the migration applied cleanly, running it again did nothing, and the table came out with the expected columns, checks and index. Postgres itself runs locally from the portable binaries, with CI using a throwaway container (D030).
- Step 30 — product endpoints, all behind the login guard: `POST /products`, `PUT /products/:id`, `GET /products?q=&includeInactive=`. The shop always comes from the token, never the request. A stale `baseRevision` is refused with `REVISION_CONFLICT` (409); an unknown or another shop's product gives 404, which also avoids telling one shop that another shop's product exists. Verified by 16 tests and by a walkthrough against a running server with curl.
  - Design point found while testing by hand: an edit was a PATCH whose missing fields fell back to defaults, so leaving out the purchase price silently cleared it. It is now a PUT that requires every field — a caller who forgets one gets a 400 instead of losing data.
- Step 31 — `scripts/check-room-schema.sh`, run in CI right after the Android build (which re-exports the schema). It checks that the declared database version matches the newest exported schema, that every version step has a migration registered on the builder, and that the exported files match what is committed. **Proved by breaking it on purpose**: added a column to the Product table with no new version, and the check failed; restored it, and the check passed again.
- Step 32 — every product write now queues a sync event in the same database transaction (D003), so a change and the record of it to send are saved together or not at all. `SyncEngine` sends what is queued, then pulls. A refused change is kept and marked rather than dropped.
- Step 33 — a pull writes the server's products into the phone's database, including deletions, and remembers the cursor so the next sync only asks for what is new. Server side, a pull reads the product table itself, so a product made straight through `POST /products` reaches the phone too.
- Step 34 — the conflict path, end to end: this phone edits from revision 1, another device got there first, and the push comes back `REVISION_CONFLICT` with the other device's version arriving in the same sync.
- Sync is something the user asks for, on a Sync screen (server address, email, password, "sync now", and how many changes are waiting). Doing it automatically in the background is M4.
- Server side of sync moved off in-memory state: applied event ids and the change cursor now live in Postgres, so a restart no longer forgets what was already applied (migrations 002 and 003).
- Design: claymorphism, written once in `ui/theme/` and used by every screen (D029).
- Money: integer poisha throughout; shown in the digits of the language on screen (১২.৫০ / 12.50) and typed in either.
- Fonts: app labels take the language's font; anything the shopkeeper typed is split per script, per word, so "চিনি 1kg" renders each part in its own font — including while typing (D010).

## M0 — Setup, done

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
Anything in M2 (Steps 35–47 of `docs/PHASE_GUIDE.md`) — the sale and stock rules, their Room tables, the New Sale/Stock/History screens, reversal, the Postgres tables and the sale and stock endpoints. Finishing the two M1 checks above also stays in scope.

## Not Allowed Right Now
- Customer and Baki screens, and the baki functions (`addCredit`, `receivePayment`, `calculateBalance`, `isOverdue`) — M3, Steps 48–61. A credit sale must still create its BakiEntry correctly, which is why the entry's shape exists now and the ledger functions do not.
- Ask Hisab, forecasting, suggestions — M5/M6.
- Full security hardening (rate limiting, token rotation, threat testing) — still M7.

## Definition of Done for M2
Every check in Steps 35–47 passes — see `docs/PHASE_GUIDE.md` for each one individually.

## Next
Step 39 (New Sale screen — cash), then credit (40), Stock (41), History (42), reversal (43–44), and the backend half: the Postgres tables (45), the endpoints (46) and the four end-to-end workflows (47). The BakiEntry table arrives with Step 40, since a credit sale has to store the entry it creates.

## Update This File
Move "Current Step" forward as each step is checked off. Update the M-number at the top once a milestone finishes. This file always answers "what step am I on, right now" — the how lives in `docs/PHASE_GUIDE.md`.
