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
