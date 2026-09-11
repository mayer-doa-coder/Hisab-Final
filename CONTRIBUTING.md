# Contributing to Hisab

## Before You Start
Read these three, in this order:

1. `CLAUDE.md` — the rules for working on this codebase.
2. `CURRENT_PHASE.md` — which step is being worked on right now.
3. `docs/PHASE_GUIDE.md` — the full step list, with a check for each step.

This file does not repeat those rules. If something here disagrees with them, they are correct.

## How Work Is Done
- Work on one step at a time, from `docs/PHASE_GUIDE.md`.
- A step is finished when its "Check" passes — not when the code compiles.
- Move `CURRENT_PHASE.md` forward when a step is checked off.
- Do not start work that belongs to a later milestone.

## Things That Are Already Settled
`DECISIONS.md` records decisions that should not be silently reopened — ledgers instead of editable balances, offline-first sync, Bangla-first UI, the two approved fonts, and others.

If a decision genuinely needs to change, add a new entry explaining why. Do not delete or quietly edit the old one.

## Before You Call Something Done
- Every user-facing string exists in both Bangla and English.
- Bangla text uses Noto Sans Bengali; English text uses Merriweather.
- Every calculation (stock, baki, sale total, suggestions) has a test.
- No password, API key, or token is written into the code.
- Only files related to the current step were changed.

## Commits
Keep commits small — ideally one step per commit. A clear message about what changed and why is more useful than a long one.

## Reporting a Security Problem
Do not open a public issue. See `docs/SECURITY.md`.
