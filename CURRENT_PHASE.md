# Current Phase

M3 — Customer, Baki, Payment (Steps 48–61) — **in progress: Steps 48–57 built, Step 58 next**

M2 (Steps 35–47) is complete. M0 (Steps 1–22) is finished and verified locally; its one remaining check needs a push: CI green on GitHub. M1 (Steps 23–34) is built, with two checks still owed — listed under "Owed from M1" below.

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build the customer and baki side end to end — the ledger rules first, then the tables, then the screens and the backend — as a ledger that is only ever added to: what a customer owes is the sum of their entries and never a number anyone edits.

## Current Step
**Steps 48–57 are built. Step 58 (Postgres and the endpoints for baki) is next.**

- **Step 48 — the baki functions. Check passed.** `domain/Baki.kt`: `addCredit`, `receivePayment`, `reverseEntry`, `calculateBalance`, `isOverdue` (and `overdueAmount`, which it is defined by). 23 unit tests, 0 failures. Decisions and what was rejected: D041.
- **Step 49 — the same cases on the backend. Check passed.** `server/src/domain/baki.ts` has the same functions. `fixtures/m3_baki.tsv` (27 cases) is read by both `BakiFixtureTest.kt` and `bakiFixture.test.ts`; the plan's own example, credit 500, payment 200, credit 100, gives 400 on both. **Proved by breaking it:** a changed fixture number failed both suites on that case, and a wrong settlement order (earliest-due instead of earliest-recorded) failed both suites on exactly the case written for it; restored, both pass. 55 new server tests, 0 failures.
- **Step 50 — the Customer table in Room. Check passed on the phone.** The table has existed since M2 (D035) with `revision`, `updatedAt` and `deletedAt`, so no schema change and the database is still version 4. `CustomerDaoTest`: 13 of 13 passed on the Galaxy A15.
- **Step 51 — the BakiEntry table in Room. Check passed on the phone.** `BakiEntryDaoTest`: 16 of 16 passed. It covers all five entry types round-tripping through the table, the sum query agreeing with `calculateBalance`, and that neither table has a balance column.
- **Step 52 — the Customer List screen. Check passed.** Shows customers offline, ordered overdue-first then largest-baki then name, with filters and a search over name and phone. A card totals what is owed. Adding a customer with a taken name is answered, not merged (D035, D042).
- **Step 53 — the Customer Details screen. Check passed.** The balance is `calculateBalance` over the customer's entries, never stored. The ledger is newest first, each line ending on the balance right after it, so the top line's number is the number shown above it.
- **Step 54 — the Add Baki screen. Check passed.** Adding credit offline updates the balance immediately — the form and the screen behind it share one live view model.
- **Step 55 — the Receive Payment screen. Check passed.** Same mechanism. A full-amount payment is one tap. Overpaying is recorded, shown as an advance, never blocked (D041).
- **Step 56 — the exact balance fixture on the real screens. Check passed, on the phone.** Credit 500, receive 200, credit 100 more: `CustomerFlowTest.fiveHundredThenTwoHundredReceivedThenOneHundredMoreIsExactlyFourHundred` shows ৳400.00 on screen and confirms the stored entries sum to 40000 poisha.
- **Step 57 — reverse one of those entries. Check passed, on the phone.** Each hand-written credit or payment has a small "Undo" link. It asks first, showing what the balance will become, then writes the opposite entry; the original stays in the ledger, marked. `BakiRepository.reverse` answers "already undone?" from stored rows in one transaction. **Proved by breaking it:** with that check removed, a second undo went through and eight undos at once wrote eight opposite entries instead of one; restored, all 9 repository tests pass. The plan's own check: 500, 200 received, 100 more is 400; undo the payment and the balance reads 600 with both entries still listed; undo the 100 and it reads 500. Decisions and what was rejected (swipe, long-press): D043.

**Checked on this tree (2026-09-25), all from result files rather than "BUILD SUCCESSFUL":**
- `ci-local.sh`, run in full on the final tree: **all 13 steps pass** - 198 Android unit tests and 272 server tests, 0 failures; Android lint "No issues found"; Spotless, ESLint, Prettier, the localization, Room-schema and JUnit-signature checks. For the record, one earlier full run failed the Lint step on a formatting violation in a test file I had edited by script after the last Spotless run. Reading the lint report afterwards also showed two warnings in my own new code (a Compose `modifier` parameter out of order, and a boxed `Long` state), which did not fail anything but were fixed so the report is clean again.
- **On-phone suite (Galaxy A15, Android 16): 191 tests, 0 failed, 0 skipped**, across 25 classes, with the dev server and `adb reverse tcp:3000 tcp:3000` up. Includes 45 tests across `CustomerDaoTest`, `BakiEntryDaoTest`, `BakiRepositoryTest` and `CustomerFlowTest`, the last of which drives the real Customers, Details, Add Baki and Receive Payment screens end to end, including the due-date-makes-it-overdue check and the taken-name dialog.
- Screens checked by hand on the phone in both languages: the claymorphism list, details, and both forms; a Bangla date shown in Bangla digits; an overpayment previewed and its explanation scrolled into view above the keyboard.

**One flaky failure, recorded rather than hidden:** `aPastDueDateMakesTheCustomerOverdueEverywhere` failed once across five on-phone runs, with a `NullPointerException` inside Compose's own layout code (a `TreeMap` used by the UI framework, not this project's code) while the phone was receiving message notifications. It passed on every other run, including immediately before and after. Not reproduced. See D042.

**Two traps hit while running the on-phone suite, worth knowing:**
- The device run can report `BUILD SUCCESSFUL` while running **zero** tests. Cause: the Gradle test engine starts its own `adb`, which can fail if another process holds its default port, or if two adb servers end up fighting over the one USB device. Fix: `./gradlew --stop`, one adb server on the default port, rerun. Always parse `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` and check the test count is greater than zero.
- When the dev server is not running, the end-to-end sync tests fail as `AssumptionViolatedException: needs the development server` rather than being skipped cleanly — they did not run, and prove nothing until `npm start` and `adb reverse tcp:3000 tcp:3000` are both up first.

**The on-phone suite uninstalls the app and wipes its data when it finishes.** The app was reinstalled with `installDebug`, empty; `phone-db.sh` confirms no database exists on the phone until it is opened once.

**Not done here, on purpose:** the Postgres `baki_entry_type_known` check still allows only `credit_sale` and `reversal`. Nothing writes the other three types yet (no sync is queued for them — see D042), so nothing can fail. The migration that widens it belongs with Step 58.

**Baki entered on these screens is not queued for sync yet, on purpose (D042):** the server has no endpoint for a standalone baki entry until Step 58. `BakiRepository.addCredit`/`receivePayment` write locally only.

**Known gap, not part of M3's steps:** two phones that are both offline can each create a customer with the same name, and the server accepts both, splitting that person's baki across two rows. Merging on the server is not safe by itself: `sale.customer_id` and `baki_entry.customer_id` are foreign keys, so a phone whose customer row was merged away would have its credit sale refused for good. A real fix needs the server to tell the phone which customer won and the phone to rewrite its own rows. This is D035's known limit reaching the sync path. The owner chose on 2026-09-23 to leave it for M3, where customers get phone numbers and a picker (D035), and it overlaps the two-device work in M4 (Steps 71–72).

## M2 — how it finished
## M2 — how it finished
Steps 43–47, the last of M2:

- **Step 43 — reversal.** A sale opens from the history and can be undone; the undo is a second, opposite sale, never an edit (D033). Stock comes back, the list marks the original reversed, and a sale cannot be undone twice.
- **Step 44 — the credit-sale rule.** The reversal's sale, stock and baki are written in one transaction with the "already undone?" check. A test makes the last write fail on purpose and confirms nothing at all was kept — there is no way to end up with stock restored and baki still owed.
- **Step 45 — Postgres.** Migration 004 adds sale, sale_item, stock_movement, customer and baki_entry. Checked on a brand-new empty database, run twice to confirm it does nothing the second time, and on the existing development and test databases.
- **Step 46 — endpoints.** Sales, reversal and stock, all shop-scoped. Proved by breaking it: with the cross-shop customer check removed, the test that one shop cannot put baki on another shop's customer fails; restored, it passes.
- **Step 47 — sync.** All four workflows run end to end on the phone against the real server and Postgres, with the stock ledger compared on both sides after each.

**Checked, all of it:** 146 on-phone tests (Galaxy A15, server running over `adb reverse`), 125 Android unit tests, 217 server tests, 0 failures anywhere; lint, Spotless, ESLint and Prettier clean; the Room schema and JUnit-signature checks pass.

**Device suite: 102 tests, 0 failures, 0 errors, 0 skipped** on the Galaxy A15 (2026-09-17), including the checks Steps 39–42 name (`SaleFlowTest`, `StockFlowTest`, `HistoryOrderTest`) and every migration from version 1 to 4 run against real rows (`ProductMigrationTest`, `SaleStockMigrationTest`). The first run reported 93: `SaleFlowTest`'s setup returned a value, so JUnit skipped the whole class. Fixed, and now caught in CI by `scripts/check-junit-methods.sh`.

**Screens checked by hand** on the phone, in airplane mode, through the whole walkthrough (stock, cash sale, cart and quantities, baki sale with the stock warning, history, language switch). Two New Sale bugs found while writing the walkthrough were fixed first (the cart summary's corner said "Total" instead of saying it opens the cart; a sale confirmed with the cart open left the next sale stuck on the cart).

**Owed items from M2, now closed (2026-09-23):**
- The stored rows were looked at with `bash scripts/phone-db.sh`. A credit sale entered fresh that day (0.5 kg Rice to Karim, ৳25.00, due 2026-10-15) was stored as entered: the sale, a -0.5 stock movement, and a baki entry equal to the sale total. It was then synced through the app, and the same sale, baki entry (with the due date) and Karim's ৳25.00 balance were found in Postgres, with stock totals per product equal on both sides. The cash-sale and reversal rows on the phone are still the ones from 2026-09-17; only the credit sale was re-entered.
- The due-date keypad question is answered: the Samsung number pad on the Galaxy A15 **does** have a "-" key, so the hyphenated date could be typed there. A plain number pad (Gboard's, for one) has none, so the field now also accepts 8 plain digits (`20261015`) and its hint shows that form. Verified on the phone.
- `4.json` (the Room schema for version 4) is committed; the chore that was listed here is done.

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
- Step 39 — New Sale, cash. One screen: the product list is the body, so adding an item is one tap; the total, the cash/baki choice and the confirm button are pinned to the bottom in the thumb's reach; quantity is set with 48 dp −/+ buttons or by typing (D037). The running total is `calculateCartTotal`, which is the same expression `calculateSaleTotal` uses, so the number on screen cannot drift from the number saved — there is a test that says so.
- Step 40 — New Sale, baki. The same screen with the payment chip switched: it asks who owes it and an optional due date, then writes the sale, its stock movements and the baki entry in one database transaction (D021). This needed the `customer` and `baki_entry` tables, which the build plan puts in M3 — see D035 for why they came forward and what is deliberately still missing (every customer and baki screen, and the whole baki ledger API).
- Step 41 — Stock. Every product with the stock its movements add up to, in one query rather than one per product. Restocking happens in place, showing what is there now and what it will become before anything is written. Negative stock is shown as negative, in red, with a line saying what it usually means — not hidden and not clamped to zero (D031).
- Step 42 — Transaction history. Sales and stock movements on one list, newest first, told apart by a coloured label and by which way the number points. A sale's own stock movement is left out, because the sale is already in the list. Every row shows when it actually happened, never when the server saw it (D019). Nothing on the screen edits anything.
- Home was rearranged by how often each thing is done: New Sale is one large button at the top, Stock and History are tiles beside each other, Products and Sync are quieter and below.
- Sale and stock changes are **not** queued for sync yet, on purpose — the server has no endpoints for them until Step 46, and a queued event it refuses would stick in the outbox forever and make the "waiting to send" count wrong (D036).
- Test counts after this work: **125 Android unit tests** and **142 server tests**, 0 failures on both (read from the result XML, not from "BUILD SUCCESSFUL"). Android lint reports no issues; Spotless and ESLint pass. 44 instrumented tests are written and compile, awaiting a device.
- Known gap worth naming: `scripts/check-localization.sh` only reads `<string>`, so a `<plurals>` block would slip past the Bangla/English parity check. Nothing uses plurals yet — "Items: 3" is labelled rather than counted to avoid needing one — but the first real plural needs the script extended first.

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
M3 (Steps 48–61 of `docs/PHASE_GUIDE.md`) — Steps 48–57 are built (above); what remains is the Postgres and endpoint work (Step 58), and the UX, bilingual and font passes (Steps 59–61). Finishing the two M1 checks above also stays in scope.

## Not Allowed Right Now
- Ask Hisab, forecasting, suggestions — M5/M6.
- Background sync, retries and multi-device convergence — M4. What exists now is a sync a person asks for.
- Full security hardening (rate limiting, token rotation, threat testing) — still M7.

## Definition of Done for M2
Every check in Steps 35–47 passes — see `docs/PHASE_GUIDE.md` for each one individually. Done.

## Next
1. **Step 58 — Postgres and the endpoints.** Widen `baki_entry_type_known` to all five types, add the baki table's shop-scoped endpoint(s), and start queueing `BakiRepository`'s writes (`addCredit`, `receivePayment` and now `reverse`) for sync (D042, D043). The server also needs its own guarantee that an entry is undone at most once.
2. Steps 59–61: a UX pass over Add Baki, Receive Payment and Undo (tap counts, touch targets, an offline indicator; note the Undo link makes each ledger row taller), a bilingual completeness sweep of every M1–M3 screen, and a check that a mixed Bangla/English string renders both fonts correctly.

Two things M2 leaves for later, on purpose:
- **Two devices reversing the same sale while offline.** The server refuses the second one (`ALREADY_REVERSED`), so the shops agree; the phone whose reversal was refused keeps its own copy until M4's conflict work (Steps 71–72) decides what a device does with a refused change.
- **The older Product and sync routes still check the login after validating the body**, so an unauthenticated caller with a malformed body gets 400 rather than 401. The new sale and stock routes check first. Tidying the older ones belongs with M7's security pass (Step 96).

## Update This File
Move "Current Step" forward as each step is checked off. Update the M-number at the top once a milestone finishes. This file always answers "what step am I on, right now" — the how lives in `docs/PHASE_GUIDE.md`.
