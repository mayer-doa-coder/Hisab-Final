# Current Phase

M0 — Setup (Steps 1–22)

In progress — Step 1 checked off, 21 remaining.

`docs/PHASE_GUIDE.md` has the exact steps, in order, each with its own check. This file just tracks which step you're on — the step list itself lives in one place only, so don't copy it here.

## Objective
Build the skeleton on both sides, including the specific things that are expensive to retrofit later: Bangla-first localization, minimal authentication with server-derived tenant identity, and the real sync foundation (contract + plumbing, not a throwaway version). Full reasoning: `DECISIONS.md` D014–D024.

## Current Step
Step 2 — Create empty backend module folders. See `docs/PHASE_GUIDE.md` M0 for what it involves and how to check it.

Done so far:
- Step 1 — repo folders created (`android/`, `server/`, `research/`, `scripts/`, `.github/`, plus `CONTRIBUTING.md` and `CHANGELOG.md`). Each new folder holds a `.gitkeep` so it survives a fresh clone.

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
