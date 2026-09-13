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
