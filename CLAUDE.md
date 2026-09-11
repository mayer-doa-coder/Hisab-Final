# Hisab Development Rules

## Stack
- Android: Kotlin + Jetpack Compose.
- Local database: Room.
- Background work: WorkManager.
- Backend: TypeScript + Fastify + PostgreSQL.

## Core Architecture
- Offline-first. The app must work fully without internet.
- The local database is the source of truth for the app itself.
- Baki (customer credit) is calculated by adding up ledger entries. It is never a single editable balance.
- Stock is calculated by adding up movement entries (restock, sale, return, damage, correction). It is never a single editable number.
- Reversing a credit sale reverses the sale, its stock movement, and its baki entry together, atomically — never partially.
- Every syncable entity (not just sync events) gets a globally unique ID generated on the device when created.
- Product and Customer (mutable entities) carry a revision; a write against a stale revision is rejected, never silently overwritten.
- The backend always derives shop_id from the authenticated session. It never trusts a shop_id sent by the client.
- Sync uses a local outbox: a change is saved on the device first, then sent to the server later. This is built once as a real system from the start (M0/M1), not as a placeholder replaced later.
- The server must never apply the same sync event twice.
- Money is an integer in the smallest currency unit; quantity is an integer scaled by 1000. Never floating-point for either.
- Complementary item suggestions ("bought together") are calculated from past sales, not stored as a separate editable value.

## Language and Fonts
- Every screen, button, and message must exist in Bangla and English before it counts as done.
- Bangla is shown by default. English must always be available as a switch.
- Bangla text uses the Noto Sans Bengali font. English text uses the Merriweather font. No other font.
- Error and status codes (like sync failures) stay the same internally in both languages. Only the message shown to the user changes, and it must mean the same thing in both languages.

## Scope
- Follow `docs/PRD.md` and the milestone listed in `CURRENT_PHASE.md`.
- Build one feature at a time, but build it end-to-end: local storage, screen, and backend endpoint together, not one layer at a time across the whole app. See `docs/PHASE_GUIDE.md`.
- Do not build features that belong to a later milestone.
- No cloud AI model required for core features (search, suggestions, forecasting).
- No deep-learning models unless specifically approved.
- Add a new code library only if it is clearly necessary.
- Don't over-build architecture ahead of need. Use the simplest thing that satisfies the rules above; add structure when a real second use case shows up, not before.

## Quality
- Every calculation (stock, baki, sale total, suggestions) needs a test.
- Never change or delete confirmed transaction history.
- Never write passwords, API keys, or tokens directly in code.
- Only change files related to the current task.

## Where to Look
- Full requirements: `docs/PRD.md`
- Build order: `docs/PHASE_GUIDE.md`
- How to work with Claude on this project: `docs/VIBE_CODING_GUIDE.md`
- Architecture summary: `docs/ARCHITECTURE.md`
- Table/field list: `docs/DATA_MODEL.md`
- Security rules: `docs/SECURITY.md`
- Past decisions: `DECISIONS.md`
- What is allowed right now: `CURRENT_PHASE.md`
