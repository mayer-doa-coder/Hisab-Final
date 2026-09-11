# Current Phase

M0 — Setup (Steps 1–22)

In progress — Steps 1–4 checked off, 18 remaining.

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build the skeleton on both sides, including the specific things that are expensive to retrofit later: Bangla-first localization, minimal authentication with server-derived tenant identity, and the real sync foundation (contract + plumbing, not a throwaway version). Full reasoning: `DECISIONS.md` D014–D024.

## Current Step
Step 5 — Write down the money/quantity/currency/time rules. (Already recorded in `docs/PRD.md` §24 and `DECISIONS.md` D019 — this step is confirming everyone building against these docs has actually read it.)

Done so far:
- Step 1 — repo folders created (`android/`, `server/`, `research/`, `scripts/`, `.github/`, plus `CONTRIBUTING.md` and `CHANGELOG.md`).
- Step 2 — 8 module folders under `server/src/modules/`: `auth/`, `shops/`, `products/`, `customers/`, `sales/`, `inventory/`, `baki/`, `sync/`. Empty (`.gitkeep` only), matching D005 and `docs/PRD.md` §23. **Check passed** — server builds with all 8 in place.
- Step 3 — Git config, formatting, and linting for both sides. **Check passed**, verified empirically (each tool was tested against deliberately bad code to confirm it actually catches problems, not just passes hollow):
  - Server: TypeScript + Fastify (D006). ESLint (`npm run lint`) + Prettier (`npm run format:check`) both genuinely catch real issues.
  - Android: Kotlin + Compose (D006), `minSdk = 26` / `compileSdk = targetSdk = 36` (D025, researched via Step 18 — pulled forward because Steps 3–4 were blocked without it). AGP's built-in `lintDebug` + Spotless (`spotlessCheck`) both genuinely catch real issues.
  - One real gap found and fixed: the `org.jlleitschuh.gradle.ktlint` Gradle plugin cannot register its check tasks under AGP 9's built-in Kotlin mode (confirmed both from the open upstream issue, ktlint-gradle#1008, and empirically — it silently checked nothing). Replaced with Spotless, which targets files by glob instead of relying on Kotlin-plugin source-set detection, and re-verified it actually works.
- Step 4 — CI. **Partly verified**: `.github/workflows/ci.yml` runs Android compile/unit-tests/lint and Server compile/unit-tests/lint as two jobs, using current action versions (checkout@v7, setup-java@v6, setup-android@v4, gradle/actions@v6, setup-node@v7) and every command in it was verified locally. Could not run the workflow itself end-to-end (tried via `act`; Docker Desktop's daemon isn't running in this environment) — **first real push to GitHub should be checked against the Actions tab** to confirm the YAML runs clean, since local command success doesn't guarantee CI-environment success (network access to install SDK platforms, container quirks, etc.).

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
