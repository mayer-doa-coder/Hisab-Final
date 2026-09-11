# Decisions

This is the project's one architecture-decision log (there is no separate ADR folder — this file is it). Each entry records what we decided, what we rejected (if anything), and why. Once written here, a decision should not be silently reopened — if it needs to change, add a new entry explaining why, do not delete the old one.

---

## D001 — Baki Ledger
Decision: Customer baki is calculated by adding up append-only BakiEntry records.
Rejected: A single editable "balance" field on the customer.
Reason: A single editable number can be changed by mistake or a bad sync. A ledger keeps full history and can always be checked.

---

## D002 — Stock Ledger
Decision: Product stock is calculated by adding up StockMovement records (restock, sale, return, damage, correction).
Rejected: A single editable "stock count" field on the product.
Reason: Same as baki — stock must never silently change without a record of why.

---

## D003 — Sync Method
Decision: Use an outbox pattern. Every local change writes a sync event in the same database transaction as the change itself. A background job sends these events to the server later.
Reason: If the internet is down, nothing is lost, because the change is already saved locally.

---

## D004 — Duplicate Sync Events
Decision: Every sync event gets a unique ID. The server checks this ID and applies each event only once, even if it is sent more than once.
Reason: A weak connection can cause the same request to be sent twice. This must never create two sales from one sale.

---

## D005 — Backend Structure
Decision: One backend codebase (a modular monolith) with separate modules: auth, shops, products, customers, sales, inventory, baki, sync.
Rejected: Splitting the backend into separate microservices.
Reason: Microservices add operational complexity that is not needed at this scale.

---

## D006 — Technology Choices
Decision: Android app in Kotlin + Jetpack Compose + Room. Backend in TypeScript + Fastify + PostgreSQL.
Reason: These are well-supported and well-documented, and they keep the number of dependencies low.

---

## D007 — Money Values
Decision: Store money as integers (in the smallest currency unit) or another decimal-safe format. Never use floating-point numbers for money.
Reason: Floating-point math can produce small rounding errors in money calculations.

---

## D008 — Forecasting Method
Decision: Compare lightweight forecasting methods (naive, moving average, EWMA, seasonal naive, Croston) using real sale history before picking one for production.
Reason: The goal is a method that works well on a phone with limited data, not the most advanced method possible.

---

## D009 — Bilingual UI
Decision: Every user-facing screen ships with both Bangla and English text from the first version. Bangla is shown by default; English is always available.
Reason: Most shopkeepers use Bangla day to day, but English must not be a "later" task that leaves gaps in the app.

---

## D010 — Fonts
Decision: Use only Noto Sans Bengali for Bangla text and Merriweather for English text, everywhere in the app.
Rejected: One "universal" font for both scripts, or extra decorative fonts.
Reason: These two fonts render Bangla and English correctly. More fonts add app size and visual inconsistency for no real benefit.

---

## D011 — Safety and Status Codes
Decision: Codes for things like sync failures or login errors (for example SYNC_CONFLICT, AUTH_INVALID) never change based on language. Only the Bangla/English message shown to the user changes, and both messages must mean the same thing.
Reason: Logs, debugging, and support need one stable code, no matter which language the shop owner is using.

---

## D012 — Complementary Item Suggestions
Decision: Suggest "often bought together" items using simple co-occurrence counting over past sales (for example, how often Coke was bought with Biryani), calculated on the device.
Rejected: A machine-learning recommendation model, or sending purchase data to a server to compute suggestions.
Reason: Counting is simple enough to work well here, keeps the feature fully offline, and avoids the cost and privacy risk of a bigger model.

---

## D013 — Feature-First Build Order
Decision: Build one feature at a time, end-to-end (local storage + screen + backend endpoint, together). Android and backend work happen in parallel per feature, not backend-then-frontend or domain-logic-then-UI across the whole app.
Rejected: The original layer-by-layer plan (pure domain logic in isolation, then database, then a backend-less Android MVP, then backend starting much later).
Reason: Working features, visible early, matter more right now than getting the full architecture right up front. The non-negotiable rules (ledgers not editable balances, local-first save, outbox sync, bilingual UI) still apply to every feature as it is built — they are just no longer separate phases that block starting the next feature.

---

## D014 — Localization Mechanism
Decision: Use Android's built-in per-app language APIs (AndroidX `AppCompatDelegate` per-app language support, backed by `LocaleManager` on newer Android versions) for Bangla/English switching and persistence, not a custom language-state system.
Reason: Android already solves "remember the user's chosen language across app restarts, independent of the phone's system language" correctly. Building a custom version duplicates a solved problem and is more likely to have bugs.

---

## D015 — Auth and Tenant Isolation Timing
Decision: A minimal authentication and shop-membership system exists from the first backend endpoint (M0/M1), not deferred to a later milestone. The backend always derives which shop a request belongs to from the authenticated session — it never trusts a shop_id supplied by the client in a request body or query string.
Rejected: Letting early endpoints (M1–M3) trust a client-supplied shop_id "temporarily," with real authorization added later.
Reason: An API that trusts a client-supplied tenant ID is a real security hole, not a placeholder detail — it lets one shop's app read or write another shop's data just by changing a request parameter. The full hardening (rate limiting, token rotation, threat testing, secret scanning) still happens later, in M7 — this decision only concerns when the *basic* "who is this request for" check exists, which is from the start.

---

## D016 — Sync Is Built Once, Not Twice
Decision: The sync foundation — client-generated entity IDs, the SyncOutbox table, the event envelope shape, the server's processed-event log, the push endpoint, and the pull/cursor endpoint — is designed and built as part of M0/M1, and used for real starting with the first feature (Product). M4 only hardens this existing system (retries, backoff, failure recovery, multi-device convergence); it does not build sync from scratch.
Rejected: A throwaway "push and hope, retry later" mechanism for M1–M3, replaced by a real outbox/idempotency system in M4.
Reason: Offline synchronization is a central research contribution of this project (RQ1). Building a disposable version first and replacing it later means redoing work and risks the early features being quietly built around assumptions (e.g. "the backend is always reachable soon") that don't hold once real sync exists.

---

## D017 — Conflict Policy for Mutable Entities
Decision: Every mutable, syncable entity (Product, Customer, and any future Shop settings) carries a server-assigned `revision` integer and an `updated_at` timestamp. A client update must send the `base_revision` it edited from. If the server's current revision no longer matches `base_revision`, the write is rejected with a language-neutral `REVISION_CONFLICT` status; the client re-fetches the current version and either reapplies the edit or shows the shopkeeper a simple message ("this item changed — reload?"). Deletions set a `deleted_at` tombstone rather than removing the row, so deletion itself is a synchronizable, conflict-checked event.
Rejected: Leaving conflicting concurrent edits (e.g. two phones renaming the same product offline) undefined, or silently picking "whichever sync event arrives last."
Reason: Append-only ledgers (Sale, StockMovement, BakiEntry) never have this problem — they're never edited in place. Mutable entities like Product and Customer are, and without an explicit rule, two offline edits to the same product can silently overwrite each other with no record that it happened.

---

## D018 — Client-Generated Global IDs for Every Syncable Entity
Decision: Every entity that can be created offline and synced (Product, Customer, Sale, StockMovement, BakiEntry — not just SyncOutbox events) gets a globally unique ID generated on the device at creation time (e.g. UUID/ULID). That ID is the entity's permanent primary key, locally and on the server — there is no separate local-ID-to-server-ID mapping step.
Reason: Without a client-generated ID, an entity created offline has no stable identity until the server assigns one, which complicates every screen that references it before the first successful sync, and complicates retry logic (was this exact product already created, or should a duplicate be made?). A client-generated ID sidesteps that entirely.

---

## D019 — Domain Value Precision: Money, Quantity, Currency, Time
Decision:
- **Money**: stored as an integer in the currency's smallest unit (e.g. poisha for BDT — 1 BDT = 100 poisha). Never a floating-point type, anywhere (device, API, database).
- **Quantity**: stored as an integer scaled by 1000 (3 decimal places), so 0.5 kg = 500, 1.25 kg = 1250, 3 pieces = 3000. Also never floating-point.
- **Currency**: an explicit `currency` field (default `"BDT"`) rather than an assumption baked into the code, even though V1 only supports BDT.
- **Time**: every transaction records `occurred_at` (when it actually happened, set by the device, possibly offline) separately from `server_received_at` (when the backend actually processed the sync event). `created_at` on a locally-originated record equals `occurred_at`. Every Shop has a `timezone` field — do not assume server time or UTC display is correct for the shop.
Reason: Floating-point money or quantity produces rounding errors that compound over many transactions. A missing currency field bakes in an assumption that's annoying to remove later. Treating server-receipt time as transaction time is actively wrong for offline sales that sync hours later — see D016.

---

## D020 — Current Stock Is Derived, Never Stored
Decision: `current_stock` is not a column on Product. It is always computed as `Σ StockMovement.quantity_delta` for that product — as a query, a view, or a cache that is provably kept in sync with the movements, never as an independently editable field.
Reason: PRD section 9 already requires this conceptually (see D002); this entry makes explicit that "current stock" listed under Product Management (PRD section 7) is a capability the system supports, not a field the Product entity independently stores. Storing it as its own field would create two sources of truth that can drift apart.

---

## D021 — Credit-Sale Reversal Is Atomic
Decision: Reversing a credit sale must, in one atomic operation, reverse all three of: the Sale record, its StockMovement(s), and its BakiEntry. Partial reversal (e.g. stock restored but baki left standing) is not a valid state.
Reason: A credit sale is written as Sale + StockMovement + BakiEntry together (PRD section 8). If reversal doesn't undo all three together, a shopkeeper could restore inventory while the customer still owes money for it, or clear the debt while the stock count stays wrong.

---

## D022 — minSdk Is Decided From Research, Not Guessed
Decision: The Android app's minimum supported SDK version (minSdk), and the specific low-end reference device(s) used for resource-efficiency evaluation (exact model, Android version, RAM, SoC, storage), are chosen based on research into the actual devices Bangladeshi small-shop owners use, and then frozen — not picked arbitrarily at setup time.
Reason: "At least one low-end Android device class" isn't reproducible on its own. Picking minSdk by guesswork risks either excluding real target users (too high) or committing to unnecessary legacy support (too low).

---

## D023 — Room Entities, API DTOs, and Postgres Rows Are Not the Same Shape
Decision: The Android Room entity, the API request/response DTO, and the PostgreSQL row for a given concept (e.g. Product) are connected by explicit mappers, not assumed to be identical structures:
```text
Room entity ↔ mapper ↔ domain model ↔ mapper ↔ API DTO ↔ backend domain ↔ PostgreSQL row
```
Reason: They represent the same domain concept but don't need to store the same fields — Android-specific sync/local-state fields shouldn't leak into the server schema, and server-only fields (e.g. audit metadata) shouldn't leak into the UI model.

---

## D024 — Backup Is Not the Same Thing as Sync
Decision: Synchronization and backup are treated as separate concerns with separate guarantees. Sync propagates every change — including an accidental deletion — to every device. A real backup must allow restoring shop data to an earlier point in time, independent of what has since synced.
Reason: If sync is the only safety net, a mistaken bulk-delete or bad migration propagates everywhere and there is nothing to recover from. See `PRD.md` section 20 and `docs/PHASE_GUIDE.md` M4/M7/M9 for where backup-related work happens.

---

## D025 — minSdk Frozen at API 26 (Android 8.0)
Decision: Hisab's Android app targets `minSdk = 26` (Android 8.0, Oreo). `compileSdk = targetSdk = 36` (Android 16), the current Google-Play-required target as of the August 2026 deadline.
Research behind this (per D022 — chosen from data, not guessed): StatCounter's Bangladesh mobile-OS breakdown (August 2026) shows Android 11 and newer at a combined 85.5% (Android 13: 17.4%, 15: 15.1%, 11: 14.0%, 12: 14.0%, 16: 13.4%, 14: 11.6%). The remaining ~14.5% is Android 10-and-older plus StatCounter's usual "Unknown"/bot-traffic noise — the page's chart is JS-rendered, so the exact tail split (how much is real Android 8–10 devices vs. noise) wasn't extractable from a static fetch. API 26 was chosen over a lower floor (e.g. API 21/24) to capture that Android 8–10 tail — plausible among budget/older phones in active retail use — without carrying legacy-API constraints for versions with negligible real share. Our per-app language mechanism (D014, AndroidX `AppCompatDelegate`) works down to API 21 regardless, so it did not force this floor.
Caveat: this is national aggregate data, not a survey of actual pilot shopkeepers' phones. If real device data from pilot shops later contradicts this (e.g. a meaningful cluster on Android 7 or below), revisit with a new decision entry — don't silently lower minSdk.
Sources: [StatCounter — Android Version Market Share, Bangladesh, Mobile](https://gs.statcounter.com/android-version-market-share/mobile/bangladesh); [Android Developers — Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk).

---

## D026 — Simpler Layering for the MVP (supersedes D023's mapper chain)
Decision: Three folders on Android, two on the server. One shape for a concept, used end to end — a mapper is added only at a boundary where the shapes genuinely differ, not by default.

```text
Android                          Server
  ui/      screens              domain/          the same rules, same words
  domain/  the rules            modules/<name>/  routes + database for one feature
  data/    database + network
```

Rule of thumb: if the Room entity, the JSON sent over the wire, and the Postgres row all hold the same fields, use the same shape and skip the mapper. Write a mapper the day they actually diverge, at that one boundary only.

Rejected: D023's `Room entity ↔ mapper ↔ domain model ↔ mapper ↔ API DTO ↔ backend domain ↔ PostgreSQL row` chain, as the default for everything.

Reason: D023's underlying concern is real — Android-only sync fields shouldn't reach the server schema, and server-only audit fields shouldn't reach the UI. But that is a problem to solve when it appears, with one mapper at the one place it appears. Declaring two mapper layers before a single entity exists is exactly the over-building `CLAUDE.md` warns against ("add structure when a real second use case shows up, not before"), and it makes every feature more code to read and more code to ship — which works against the low-resource goal (RQ2). D023 still applies as the *reason to watch that boundary*; it is no longer a required chain for every entity.
