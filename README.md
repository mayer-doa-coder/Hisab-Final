# Hisab

An offline-first retail assistant for small shops (দোকান) in Bangladesh. See `docs/PRD.md` for the full product requirements.

## Repository Layout

```text
/                       ← files read on every task, kept short
  CLAUDE.md             ← rules for working on this codebase
  CURRENT_PHASE.md      ← what is allowed to be worked on right now
  DECISIONS.md          ← settled decisions and why (do not reopen without a new entry)
  README.md             ← this file
  LICENSE

/docs                   ← the actual specifications, one file per topic
  PRD.md                ← full product requirements (the single source of truth for scope)
  PHASE_GUIDE.md         ← the build plan as small numbered steps, one at a time, Android + backend together
  VIBE_CODING_GUIDE.md   ← how to work with Claude on this project
  ARCHITECTURE.md        ← short system overview, points back to PRD.md for detail
  DATA_MODEL.md          ← the tables/entities Hisab uses
  SECURITY.md            ← security requirements, points back to PRD.md for detail
  RESEARCH_PLAN.md       ← how each research question gets tested
  LOCALIZATION.md        ← bilingual UI and font rules
```

## Rule: One Document, One Home

Every topic has exactly one file. Nothing is duplicated:

- Root files are short and answer "what can I do right now / what have we decided."
- Everything under `docs/` describes *what Hisab is and how it is built*. If you need detail on a topic, there is exactly one file for it — never a second copy or summary of an existing one.
- There is no separate `/ADR` folder — `DECISIONS.md` at the root is the one decision log.
- If a new topic needs a document, add exactly one file for it and link to it from here. Do not create a second file that duplicates or summarizes an existing one.

## Where To Start
1. Read `docs/PRD.md` for what Hisab must do.
2. Read `CURRENT_PHASE.md` for what is in scope right now.
3. Read `docs/PHASE_GUIDE.md` for the build plan — small numbered steps, checked off one at a time, Android and backend together, not backend-then-frontend or the reverse.
