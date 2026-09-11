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
