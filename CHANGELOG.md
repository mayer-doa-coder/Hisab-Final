# Changelog

All notable changes to Hisab are recorded here.

Versions use semantic versioning. The first public research release will be `v1.0.0` — see `docs/PHASE_GUIDE.md` M9.

## [Unreleased]

### Added
- Project documentation: PRD, architecture, data model, security, localization, research plan, and the step-by-step build plan (`docs/`).
- Decision log (`DECISIONS.md`), working rules (`CLAUDE.md`), and step tracker (`CURRENT_PHASE.md`).
- Repository skeleton: `android/`, `server/`, `research/`, `scripts/`, `.github/` folders, plus `CONTRIBUTING.md` and this file (Step 1).
- Backend module folders under `server/src/modules/`: auth, shops, products, customers, sales, inventory, baki, sync (Step 2).
- Minimal server project: TypeScript + Fastify, with `build`, `typecheck`, `start`, `test`, `lint`, and `format`/`format:check` scripts. ESLint + Prettier configured. A real (not placeholder) test for the root endpoint via Fastify's `.inject()` (Step 3).
- Android project scaffold: Kotlin + Jetpack Compose, `minSdk = 26` / `compileSdk = targetSdk = 36` (see D025 for the device-usage research behind this). AGP's built-in lint + Spotless configured for formatting/linting (Step 3).
- `DECISIONS.md` D025: minSdk frozen at API 26 based on Bangladesh Android-version market share data (StatCounter, August 2026).
- CI: `.github/workflows/ci.yml` — Android compile/unit-tests/lint and Server compile/unit-tests/lint (Step 4).
- Minimal login (`POST /auth/login`) and a `requireAuth` guard used by every protected route, deriving shop_id only from the session token, never from the request (D015). Users/shops held in memory for now (D027) — Postgres arrives with real entities in M1 (Steps 11–12).
- Shared sync event envelope (`server/src/domain/syncEvent.ts`, mirrored by Android's `SyncOutboxEntity`) and an idempotent push endpoint, `POST /sync/push` — the same event pushed twice only applies once (D004) (Steps 14–15).
- Local Room database on Android: `SyncOutboxEntity`/`SyncOutboxDao` and `SyncMetadataEntity`/`SyncMetadataDao`, with the schema exported and committed under `android/app/schemas/` (Step 13).
- Pull endpoint, `GET /sync/changes?after=<cursor>`, scoped to the requester's own shop; and a conflict check on push — an update/delete whose `base_revision` no longer matches an entity's current revision is rejected with `REVISION_CONFLICT` and never applied, never marked processed, never returned by a pull (Steps 16–17).
- `DECISIONS.md` D028: `minSdk = 26` frozen permanently (98.04% of Bangladesh Android page views in August 2026 run Android 8.0+), and the low-end reference device frozen as Tecno Spark Go 2 (3 GB RAM + 64 GB eMMC 5.1, Unisoc T7250, Android 15). Evidence CSVs saved in `research/performance/device-research/` (Step 18).
- Research data plan made concrete in `docs/RESEARCH_PLAN.md`: who may write held-out sentences, writer instructions, minimum sizes, annotation fields, a second labeler on 20%, a hash-sealed freeze, forecasting sources and anonymization, stockout-day marking, consent form contents, withdrawal, pilot device record, and dataset versioning (Step 19).
- `docs/PHASE_GUIDE.md`: Step 107 now also checks the app runs on an Android 8.0 (API 26) system image; Step 114's research layout adds `/suggestions`.
- `GET /health` behind `requireAuth`: 401 `AUTH_INVALID` without a valid token, 200 `{"status":"ok"}` with one (Step 21).
- `README.md` "Run It Yourself": what to install, and how to build, test, and run the server and the Android app, including on a real phone (Step 22).

### Fixed
- CI was silently running only 21 of the server's 64 tests. The unquoted test pattern `dist/**/*.test.js` in `server/package.json` was expanded by Linux `sh` only one folder deep, so the auth, sync, and root-endpoint tests never ran in CI. The pattern is now quoted so Node expands it; verified on Node 22 (CI's version) and Node 24 (Step 22).
- CI now builds the installable APK (`assembleDebug`) and compiles the on-phone tests, instead of only compiling Kotlin (Step 22).
- A new install could open in English on an English phone. "Pin Bangla on first run" ran in `Application.onCreate`, where AppCompat's language API silently does nothing on Android 13+ (no Activity exists yet). It now runs in `MainActivity.onCreate`, and the empty `HisabApplication` class was removed. Found by the new `HomeScreenTest` on a real Android 16 phone, and confirmed fixed by a fresh uninstall/install there (Step 20).
- Lint now reports "No issues found":
  - the "Change language" label is actually shown on the home screen;
  - `localeConfig` is marked as Android 13+ only;
  - the four "newer version available" checks, which change over time on their own, are turned off (Step 20).
- Kept `mipmap-anydpi-v26`: renaming it to `mipmap-anydpi`, as lint suggests, breaks the build with AGP 9.4, so that one warning is ignored in `android/app/lint.xml` (Step 20).

### Added (M1 — Product, Steps 23–27)
- `product` table in Room (database version 2, with a hand-written migration and the exported schema committed), reached only through `ProductRepository`: device-generated id (D018), revision from 1 upward, stale-revision writes rejected (D017), deletion as a tombstone, aliases trimmed and de-duplicated (Steps 23–24).
- Product list screen reading the local database as a live query, with search over names and aliases in the same query (Steps 25, 27).
- Add/Edit product screen — name, other names, unit, selling price, optional purchase price, deactivate/reactivate, delete with confirmation. Saves locally with no network call in the path (Step 26).
- Claymorphism design system in `ui/theme/` — `ClayCard`, `ClayButton`, `ClayChip`, `ClayTextField`, `ClayText` (`DECISIONS.md` D029).
- Money typed and shown in either Bangla or English digits, always stored as integer poisha (`domain/MoneyFormat.kt`).
- Per-script fonts extended to text the user typed: `scriptAwareText` splits a string word by word, so "চিনি 1kg" renders each part in its own font, including inside text fields (D010).
- Tests: 34 new unit tests (money parsing/formatting, form validation, script splitting) and 4 new instrumented test classes (DAO, repository, the 1→2 migration, and the product screens end to end).

### Fixed (M1 test reliability)
- The device test run could hang for good: two home-screen tests asked for the Activity right after a language change, which restarts it, and that wait has no timeout. Each test now does its work in one block and leaves the language on Bangla, and runs are given a per-test time limit.
- A sync test asserted the wrong thing: it told the stand-in server to report a new position while giving it nothing to send, so the server correctly reported the old one.

### Fixed (CI)
- The Android job failed inside `android-actions/setup-android@v4` before any of our own steps ran: `Warning: Failed to find package 'tools'`, then `sdkmanager ... failed with exit code 1`. The action's default `packages` input is still `tools platform-tools`, and Google has stopped serving the legacy standalone `tools` bundle (replaced by `cmdline-tools`), so sdkmanager now treats it as a missing package and exits 1. Not caused by anything in this repository - it broke on its own when the remote SDK index changed. The action is now given `packages: ''` so it installs nothing itself, and `platform-tools` moved into the existing explicit, version-pinned `sdkmanager` step, so everything the build needs is named in one place.
- `npm run format:check` was failing on two committed server files that Prettier had never been run over (`src/modules/sync/syncService.ts`, `src/modules/sync/routes.test.ts`). Reformatted; the change is whitespace only (`git diff -w` is empty) and all 142 server tests still pass.

### Added (M3 — baki functions, Steps 48-51)
- The baki ledger as plain functions on both sides (`android/.../domain/Baki.kt`, `server/src/domain/baki.ts`): `addCredit`, `receivePayment`, `reverseEntry`, `calculateBalance`, `isOverdue` (and `overdueAmount`, which it is defined by). What a customer owes is only ever the sum of their entries; an entry is never edited, a mistake is undone by writing the opposite one (D001, Steps 48-49).
- Three new baki entry types beside `credit_sale` and `reversal`: `credit` (baki added by hand), `payment`, and `entry_reversal`. `reference` is never free text - a sale id, an entry id, or null (D041).
- `reverseEntry` refuses a credit sale's entry, which is undone only together with its sale and stock (D021), and refuses to reverse a reversal.
- A payment larger than what is owed is recorded and the balance goes negative, rather than being refused - the money is in the shopkeeper's hand either way (D031's reasoning, D041).
- Overdue: undone credits drop out, payments settle the oldest debt first, and what is still unpaid on a credit past its due date is overdue. Due today is not yet overdue; no due date is never overdue. "Today" is passed in, never read from the clock (D041).
- `fixtures/m3_baki.tsv`: 27 shared cases (balance, overdue, refused), read at run time by both the Kotlin and TypeScript suites, so neither side can drift. Includes the plan's own example - credit 500, payment 200, credit 100 gives 400. Proved by breaking it: a changed number and a wrong settlement order each failed the suites on exactly the case written for them.
- Room `CustomerDaoTest` (Step 50) and `BakiEntryDaoTest` (Step 51): insert and read back for every entry type, the tombstone and revision fields, name search in Bangla and English, the balance query agreeing with the domain function, and a check that neither table has a stored balance column. Neither table needed a schema change - both came in M2 (D035) - so the database is still version 4.
- Tests: 27 new Android unit tests (154 in total, 0 failures) and 55 new server tests (0 failures).
- On the Galaxy A15: the full on-device suite ran 175 tests with 0 failures and 0 skipped, including the 29 new Room tests and the end-to-end sync tests against the real server and Postgres (so the new entry types did not break sync).

### Fixed (found while writing the server twin)
- JavaScript's `Date.parse` accepts 2026-02-30 and quietly turns it into March 2, where Kotlin's `LocalDate` refuses it. The new `baki.ts` round-trips a date and requires it to come back unchanged. The older `saleValidation.ts` still uses the loose check for a pushed sale's due date; a phone cannot produce such a date, so it is left as it was.

### Fixed (due date on New Sale)
- The due-date field now accepts 8 plain digits (`20261015`) as well as `2026-10-15`. The Samsung number pad on the reference phone does have a "-" key, so the hyphenated form could be typed there, but a plain number pad (Gboard's, for one) has none, and the hint promised a format such a keypad could not produce. The hint now shows the digits form. Verified on the Galaxy A15: typing `20261015` saved a credit sale due 2026-10-15 that reached Postgres unchanged.

### Added (M2 — reversal, the backend, and sync, Steps 43-47)
- Reversing a sale from the transaction history (Step 43). Tapping a sale opens it in full; undoing it records a second, opposite sale beside it — the stock comes back as `return` movements and, for a credit sale, the customer's baki goes down by exactly what the sale added. The original is never edited or deleted, and the list marks it reversed. The confirmation says in plain words what will happen, including how much comes off whose baki.
- The reversal, its stock and its baki are written in one database transaction together with the check that the sale had not already been undone (Step 44, D021), so a double tap cannot reverse twice and a failure part way leaves nothing behind. An on-phone test forces the last write to fail and confirms none of it was kept.
- Damaged/lost and "count the shelf" on the Stock screen, so every movement type the PRD requires is reachable. A shelf count records the difference, never overwriting the number (D040).
- `sale`, `sale_item`, `stock_movement`, `customer` and `baki_entry` in Postgres (migration 004), each scoped by shop, with a sale reversible only once (Step 45).
- Sale and stock endpoints (Step 46): `POST /sales`, `POST /sales/:id/reversal`, `GET /sales`, `GET /sales/:id`, `GET /stock`, `GET /stock/:productId`, `POST /stock/movements`. The shop always comes from the session token, and the server computes every total with the same shared rules the phone uses — a total sent by a caller is thrown away. The guard runs before body validation, so a caller with no token learns nothing about the schema.
- Sales, stock changes and customers now sync (Step 47). A sale travels as one event carrying its lines, movements and baki entry, and the server applies it — and records the event as applied — in one transaction, so it can never hold half a sale (D038). Pulls merge all four tables through one shared change counter (D039), and the phone now follows pages to the end instead of stopping after the first 500 changes.
- `DECISIONS.md` D038-D040 record those three choices and what was rejected.

### Fixed
- The phone pulled only the first page of changes and then reported itself in sync. A phone offline for a busy week would have quietly missed everything past the first 500 changes.
- The sync route tests asked for "everything" in one pull, which silently stopped being true as the local test database grew past a page. They now read every page, so they keep their meaning on a developer's machine as well as on CI's empty database.

### Fixed (device run, Steps 39-42)
- `SaleFlowTest` never ran on the phone: its `@Before` method was written `fun createDatabase() = runBlocking { ... }`, whose last line returns a `StockMovement`, and JUnit refuses a class whose setup returns a value ("Method createDatabase() should be void"). All ten of its tests silently dropped out of the device run, which reported 93 tests instead of 102. Fixed with an explicit `: Unit`. The same mistake had happened once before, in M0.
- New Sale: the corner of the cart summary said "Total" — the same word as the line under it — so nothing told a shopkeeper that tapping it opens the cart, the only way to reach the −/+ buttons and Remove. It now says "See items added" / "যোগ করা পণ্য দেখুন".
- New Sale: a sale confirmed with the cart open left it open, so the first product tapped for the next customer swapped the product list out for the cart. The view now returns to the product list whenever the cart is empty.
- After these fixes the full device suite passed on the Galaxy A15: 102 tests, 0 failures, 0 errors, 0 skipped. (`ProductScreenTest` had also failed once, because the notification shade was open over the app; it passed unchanged once the shade was closed.)

### Added (CI reliability)
- `scripts/check-junit-methods.sh`, run in CI right after the Android compile: reads the compiled test classes and fails if any `@Test`/`@Before`/`@After` method returns a value, which JUnit would refuse at run time on a phone that CI never has. Proven against the broken `SaleFlowTest` (it named the exact method) and the fixed one (passes, 139 classes checked).
- `scripts/phone-db.sh`: shows what the app on a USB-connected phone has stored — stock per product, sales, stock movements, baki entries with a check that each equals its sale total, what each customer owes, and what is waiting to sync — without Android Studio. It copies the database off the phone and reads the copy with Node's built-in SQLite, so nothing new is installed and nothing on the phone changes. It copies the write-ahead log too, because recent rows live there; a test showed a copy of `hisab.db` alone loses them.
- `scripts/ci-local.sh`: runs every check GitHub CI runs, with the same commands and in the same order, and prints one pass/fail summary instead of stopping at the first failure. Every CI failure so far came from checking less than CI does — Prettier over one folder instead of the whole server, a green run trusted while it ran 21 of 64 tests, a no-op build read as "no warnings" — so "passes locally" now means the whole set. It also clears `server/dist` first, the way a fresh CI checkout starts, so a deleted test cannot keep running as a stale compiled file.
- `scripts/report-unit-tests.sh`, run as a new "Unit test count" step in the Android CI job: prints how many Android unit tests actually ran, taken from the JUnit XML, and fails if none did. Gradle prints "BUILD SUCCESSFUL" either way, so until now the Android job never showed a count. Proven against real results (125), an empty folder, a file with zero tests, and a planted failure.

### Added (M2 — Sale and Stock screens, Steps 39-42)
- New Sale screen, cash and baki in one (Steps 39-40). The product list is the body of the screen, so adding an item is one tap and tapping again adds one more rather than a second line; the total, the payment choice and the confirm button stay pinned at the bottom; quantity is set with 48 dp buttons or by typing. Confirming writes the sale, its lines, its stock movements and (on baki) the customer's entry in one database transaction (D021), then clears the cart and confirms in place. Full reasoning for the layout: D037.
- Stock screen (Step 41): every product with the stock its movements add up to, from one query rather than one per product. Restocking happens in place and shows what is on the shelf now and what it will become before anything is written. Negative stock is shown, in red, with a line saying what it usually means (D031).
- Transaction history (Step 42): sales and stock movements on one list, newest first. A sale's own stock movement is left out, because the sale is already listed. Rows show when something actually happened, never when the server saw it (D019), with today and yesterday named rather than dated.
- Home rearranged by how often each thing is done: New Sale large and first, Stock and History as tiles, Products and Sync below.
- `customer` and `baki_entry` tables (database version 4, hand-written `MIGRATION_3_4`), plus a find-or-create-by-name lookup. These belong to M3 by the build plan and came forward because a credit sale cannot create its baki entry without them - D035 records that and the one limit it carries.
- `QuantityFormat`: quantities read and typed in the language's own digits, trailing zeros dropped, built from integer scaled units with BigDecimal and never floating point.
- `calculateCartTotal`, deliberately the same expression as `calculateSaleTotal`, so the running total on screen cannot drift from the total the sale is saved with.
- `DECISIONS.md` D035-D037: why the customer and baki tables came forward; why sale and stock changes are not queued for sync until the endpoints exist (D036); and how the New Sale screen is laid out, with the cart and touch-target research behind it.
- Tests: 25 new Android unit tests (125 in total, 0 failures) and 26 new instrumented tests covering each step's own check - a cash sale reducing stock, a credit sale creating its baki entry, a restock showing immediately, and history ordering. The instrumented suite compiles but has not yet run on a device.

### Added (M2 — Sale and Stock, Steps 35-38)
- Stock as a ledger, on both sides (`android/.../domain/Stock.kt`, `server/src/domain/stock.ts`): `restock`, `sell`, `returnStock`, `damage`, `correctStock`, `calculateCurrentStock`, and `stockShortfall`. No stock column exists anywhere - current stock is always the sum of the movements (D020). A correction is a shelf count rather than a typed difference, so the number a person enters is one they can see (Step 35).
- Sales as plain functions, on both sides (`android/.../domain/Sale.kt`, `server/src/domain/sale.ts`): `calculateLineTotal`, `calculateSaleTotal`, `completeCashSale`, `completeCreditSale`, `reverseSale`. A completed sale is a single value holding the sale, its lines, its stock movements and (on credit) its baki entry, and it refuses to exist unless all of them agree - so there is no way to build a reversal that restores stock and leaves the baki standing (D021, Step 36).
- `fixtures/m2_sale_stock.tsv`: 29 shared fixture cases, read at run time by both the Kotlin and the TypeScript test suites, so neither side can drift by keeping its own numbers (D034, Step 37).
- `sale`, `sale_item` and `stock_movement` tables in Room - database version 3, hand-written `MIGRATION_2_3`, schema 3 exported. `sale_item` has a foreign key to `sale`; current stock is a `COALESCE(SUM(...))` query, which is D020 written in SQL. Neither DAO has an update or a delete, because confirmed history is never rewritten (Step 38).
- `BakiEntry` shape on both sides, with the entries a credit sale and its reversal produce. The baki ledger functions and its table are still M3 - only what a credit sale needs exists now (Step 36, D021).
- Tests: 45 new Android unit tests and 68 new server tests (100 and 142 in total, 0 failures), plus 16 new instrumented tests awaiting a device run.
- `DECISIONS.md` D031-D034: a sale is never blocked by low stock and stock may go negative; line totals round half away from zero, per line; a reversal is a second, opposite sale rather than an edit; one fixture file read by both languages.
- `docs/DATA_MODEL.md`: Sale gains `payment`, `customer_id` and `reverses_sale_id`, which PRD section 8 requires and the first draft of that file had not named (D033).

### Added (M1 — Sync, Steps 31–34)
- `scripts/check-room-schema.sh`, run in CI after the Android build: the declared database version must match the newest exported schema, every version step must have a registered migration, and the exported files must match the committed ones. Verified by deliberately changing an entity without a migration and watching it fail (Step 31).
- Every product write queues a sync event in the same database transaction as the change itself (D003), so there can never be a change with nothing to send (Step 32).
- `SyncEngine` on the phone: send what is queued, then pull what the server has and save it locally, remembering the cursor. A refused change is kept and marked `conflict` rather than dropped or retried blindly (Steps 32–34).
- `SyncApi` over `HttpURLConnection` and `org.json` — no HTTP or JSON library (D006). The INTERNET permission was added for this; plain HTTP is allowed in debug builds only, so a release build cannot send a shop's data unencrypted.
- A Sync screen: server address, email, password, "sync now", and how many changes are waiting. Background syncing on a schedule is still M4.
- Server: pushed events now write real product rows, and a pull reads the product table, so a product created through `POST /products` reaches the phone too. Applied event ids and the change cursor moved from memory into Postgres (migrations 002, 003), so a restart no longer forgets what was already applied.
- Migrations now take a database lock while they run: two servers — or two test files, which is how this was found — could otherwise both try to create the same table.

### Added (M1 — Product on the server, Steps 29–30)
- Postgres, from `server/migrations/*.sql` applied by a small runner (`npm run migrate`) that records each applied file and runs it in a transaction. First migration creates `product` with money as `BIGINT` poisha, aliases as a real array, and checks for a non-blank name and non-negative prices (Step 29).
- Product endpoints behind the login guard, with the shop taken only from the session token (D015): `POST /products` (idempotent on a repeated id), `PUT /products/:id` (full replace, refuses a stale `baseRevision` with `REVISION_CONFLICT`), and `GET /products?q=&includeInactive=` searching name and aliases (Step 30).
- `DECISIONS.md` D030: local portable Postgres for development, a throwaway container in CI, a hosted database such as Supabase only for the deployed pilot; plain SQL migrations rather than a migration library; and the schema conventions.
- CI now starts a `postgres:18` service and runs the migrations before the tests, which is what proves they still apply to an empty database.
- README: how to set up and run the local Postgres, with no password anywhere.

### Added (Step 20)
- `HomeScreenTest` (on-phone): opens in Bangla even on an English phone, the language button switches both ways, and the choice survives closing and reopening the app. 13 on-phone tests total, 0 failures.
