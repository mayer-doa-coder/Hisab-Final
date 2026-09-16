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

---

## D027 — Auth Storage and Password Hashing for Steps 11–12
Decision: Users, shops, and sessions are held in an in-memory store for now, seeded with a couple of demo accounts — not PostgreSQL. Passwords are hashed with Node's built-in `crypto.scrypt` (salted), not a third-party library. Session tokens are opaque random bytes, checked against an in-memory map — not JWTs.
Reason: PostgreSQL wiring belongs to M1 (`docs/PHASE_GUIDE.md` Step 23, "Postgres arrives with the real endpoints") — there is no entity yet that needs it. Building migrations and a connection pool now, before Product exists, is exactly the ahead-of-need structure `CLAUDE.md` warns against. `crypto.scrypt` and opaque tokens use only what Node already ships, matching D006's "keep dependencies minimal" — a real password-hashing library (argon2/bcrypt) or JWT library can replace these later if a real need shows up, but scrypt already satisfies "secure password hashing" (`PRD.md` §19.4) without adding one.
Caveat: this is scoped to Steps 11–12 only. Full hardening — token rotation, expiry, rate limiting, a real user store — is still M7, per D015.

---

## D028 — minSdk 26 Frozen Permanently, and the Low-End Reference Device (closes D022)
Decision, part 1 — minSdk: `minSdk = 26` (Android 8.0) is frozen permanently. No later step revisits it. This replaces D025's "revisit if pilot data disagrees" caveat: pilot phones are recorded and reported (`docs/RESEARCH_PLAN.md`, "Pilot device record"), not used to move the floor. A phone below Android 8.0 is unsupported, and that is stated as a limitation. Only `minSdk` is frozen — `compileSdk`/`targetSdk` still rise when Google Play requires it.

Decision, part 2 — low-end reference device (for Steps 101 and 107):

| Field | Value |
| --- | --- |
| Model | Tecno Spark Go 2 |
| Variant | 3 GB RAM + 64 GB storage (the cheapest variant — ৳9,999 official price in Bangladesh) |
| Android version | Android 15 (HiOS 15), as shipped |
| SoC | Unisoc T7250, 12 nm — CPU 2× Cortex-A75 @ 1.8 GHz + 6× Cortex-A55 @ 1.6 GHz; GPU Mali-G57 MP1 |
| RAM | 3 GB (the phone's "memory extension" swap is left at factory default; its state is recorded) |
| Storage | 64 GB eMMC 5.1 |
| Screen | 6.67" IPS LCD, 720 × 1600, 120 Hz |
| Battery | 5000 mAh, 15 W |
| On sale in Bangladesh | Since June 2025 |

When the phone is bought, record in `research/performance/device-research/`: the exact build number, security patch date, `adb shell getprop ro.config.low_ram`, and total RAM from `adb shell cat /proc/meminfo`. Do not update its OS during M7–M8; if an update is forced, record the new build.
If this exact model can't be bought, use Infinix Smart 10 (3 GB + 64 GB), then Redmi A5 (3 GB + 64 GB) — same SoC, same RAM, same storage type — and record the swap as a new decision entry.

Research behind part 1 (full CSVs saved in `research/performance/device-research/`):
- StatCounter, Bangladesh, mobile, Android version — August 2026 / average of Feb–Aug 2026:
  - Android 8.0 and newer (API 26+): **98.04%** / 96.15%. Of that, Android 8.0–10 is 11.65% in August — a real group, mostly older cheap phones, which is why the floor isn't higher.
  - Below Android 8.0 (4.4–7.1): **1.93%** / 3.84%. The average is pushed up by a May–June 2026 spike in Android 5.0/6.0/8.0 that fell back in July, which looks like bot traffic, not real phones.
- What other floors would change (August 2026):
  - API 24 (Android 7.0): +0.52% more phones.
  - API 23 (Android 6.0, the lowest our libraries allow): +1.43%.
  - API 28 (Android 9): −2.37%.
  - API 29 (Android 10): −5.46%.
- Libraries don't force the floor. Read from the AAR manifests actually used in this build: Room 2.8.5, Compose UI 1.10, and AppCompat 1.8.0 need API 23; core-ktx, activity-compose, and lifecycle need API 21.
- Why not go lower than 26, for at most +1.43%:
  - `java.time.Instant` is already used (`SyncOutboxEntity`, `InstantConverters`) and is built in only from API 26. Below that it needs core-library desugaring, an extra build step and dependency.
  - The launcher icon is an adaptive icon (`mipmap-anydpi-v26`). Below API 26 it would need extra PNG icons.
  - Android below 7.1.1 does not trust the Let's Encrypt root certificate, so sync could fail depending on the server's certificate.
  - Those phones are 8+ years old and get no security updates (`docs/SECURITY.md`).
- Why not go higher: raising to API 28/29 drops 2.4–5.5% of phones. Those are the oldest, cheapest phones, the ones most likely in a small shop, and nothing in the stack needs a higher floor.
- Limit of this data: StatCounter counts web page views, not phones owned. It leans toward phones that browse more, which are usually newer. So old phones are probably somewhat under-counted — one more reason not to raise the floor.

Research behind part 2:
- Brand: IDC, Bangladesh, Q3 2025 — Tecno 20.4%, Infinix 18.2%, Xiaomi 14.9%, Vivo 13.6%, OPPO 8.1% of smartphone shipments. IDC also reports Infinix as No. 1 through 2025 and in Q1–Q2 2026. Tecno and Infinix are both Transsion brands, so an entry-level Transsion phone is the most representative choice.
- Price: IDC Q3 2025 — 41% of smartphones shipped cost under $100, the largest price band. At ৳9,999, the Spark Go 2 is in that band.
- One chip, three brands: the Unisoc T7250 is also in the Infinix Smart 10 and the Redmi A5, the entry phones of the other two top brands. Results therefore describe the most common current entry-level platform, not one unusual phone.
- Low end on purpose:
  - 3 GB RAM is the lowest tier the top brands sell now. Google requires Android Go edition for 2–3 GB phones on Android 15.
  - eMMC 5.1 is slow storage, which is the honest worst case for local database writes.
- Repeatable: it's a current model on sale in Bangladesh at an official price, so someone else can buy the same phone.
- Also relevant: 53.5% of all phones shipped in Bangladesh in Q3 2025 were still feature phones (IDC). Shop owners who own a smartphone at all mostly own cheap ones.

What the reference device does not test: whether the app runs on Android 8.0, because the phone ships Android 15. So Step 107 also installs the release build on an Android 8.0 (API 26) system image (emulator or cloud test device) and runs the core flow: open, switch language, record a sale offline, sync. That is a compatibility check, not a performance measurement.
The development phone (Samsung Galaxy A15, Android 16) is not the low-end device. The mid-range and modern devices are chosen at Step 107.

Sources:
- [StatCounter — Android version share, Bangladesh, mobile](https://gs.statcounter.com/android-version-market-share/mobile/bangladesh)
- [StatCounter — vendor share, Bangladesh, mobile](https://gs.statcounter.com/vendor-market-share/mobile/bangladesh)
- [IDC Q3 2025 Bangladesh figures, reported by TOB News (7 Nov 2025)](https://tob.news/smartphone-market-share-jumps-to-46-5-in-july-sep/)
- [IDC: Infinix No. 1 through Q2 2026, reported by TOB News](https://tob.news/infinix-retains-smartphone-market-lead-through-2026/)
- [TECNO — Spark Go 2 official specs](https://www.tecno-mobile.com/phones/tech-specs/techspecs/spark-go-2/)
- [MobileDokan — Tecno Spark Go 2 Bangladesh price and release](https://www.mobiledokan.com/mobile/tecno-spark-go-2)
- [GSMArena — Tecno Spark Go 2](https://m.gsmarena.com/tecno_spark_go_2-13975.php)
- [GSMArena — Redmi A5](https://www.gsmarena.com/xiaomi_redmi_a5_4g-13737.php)
- [GSMArena — Google's minimum RAM/storage rules for Android 15 (15 Apr 2025)](https://www.gsmarena.com/here_are_googles_new_minimum_ram_and_storage_requirements_for_android_phones-news-67387.php)

---

## D029 — Claymorphism as the Look of Every Screen
Decision: Hisab's screens use one claymorphism design language, written once in `android/app/src/main/java/com/hisab/app/ui/theme/` and used everywhere: a soft lilac ground, thick rounded surfaces (26 dp cards, 22 dp buttons, 20 dp fields), a coloured shadow below with a pale edge above, inputs that look pressed into the surface rather than raised, and buttons that shrink slightly and lose shadow depth when touched. A screen does not style itself; it uses `ClayCard`, `ClayButton`, `ClayChip`, `ClayTextField` and `ClayText`.

Reason: a single small set of components keeps every screen consistent without a UI library, and makes a shop-facing app look finished rather than like a form. It is built from Compose primitives only — no new dependency, matching D006.

Rules that override the look wherever they clash:
- Text is dark ink on light surfaces, never pale-on-pale. Cheap phone screens in daylight are the target (D028), so decoration stays on surfaces, never on words or numbers.
- Tap targets are at least 54 dp high.
- Fonts still follow D010: app labels take the current language's font, and anything the shopkeeper typed is split per script by `scriptAwareText`, so "চিনি Sugar" renders each half in its own font — including inside text fields, through `ScriptAwareVisualTransformation`.
- Money is shown in the digits of the language on screen (১২.৫০ in Bangla, 12.50 in English), always built from integer poisha.

Inspiration came from tactile/soft-button component examples on 21st.dev. No code was copied: those are React and Tailwind, and this app is Jetpack Compose — only the visual idea carried over.

Not decided here: dark mode. The app is light-only for now; a dark palette would need its own entry.

---

## D030 — How Postgres Is Run, and How the Schema Changes
Decision, in three parts.

**Where the database runs.**
- Development and tests: a local Postgres 18, unpacked from the official binaries-only zip (no installer, no admin rights), listening on port **5433** and trusting connections from this machine. Databases `hisab_dev` and `hisab_test`. Because local connections are trusted, **there is no password anywhere in the repository** (CLAUDE.md).
- CI: a throwaway `postgres:18` service container, also with trust auth, created empty for every run — which is what proves the migrations still run on an empty database (Step 29).
- Later, for the pilot deployment: a hosted Postgres such as Supabase. Nothing in the code changes; `DATABASE_URL` points somewhere else.

Rejected for development: Supabase or any hosted database. Every test would need internet and a network round trip per query, the connection string is a secret to manage, free projects pause when idle, and anyone cloning the repo — including a reviewer of the research artifact — would need an account before the tests could run. Rejected for now: Docker, because it means starting Docker Desktop before any work; the portable install has no such step. PGlite was rejected as a dependency that is not quite a real server.

**How the schema changes.** Numbered plain `.sql` files in `server/migrations/`, applied by a small runner (`server/src/db/migrate.ts`) that records each applied filename in a `schema_migrations` table and runs each migration in its own transaction. Re-running does nothing. `npm run migrate` applies them; CI runs it on every push.

Rejected: node-pg-migrate, Drizzle, Prisma. They are bigger tools that would own the schema and add a generation step, where plain SQL is readable by anyone who knows SQL and is the whole mechanism (D006).

**Schema conventions.**
- Column names in `snake_case`, matching `docs/DATA_MODEL.md`; the domain shape stays camelCase, and the row is mapped to it (Step 7) rather than assumed identical to the phone's Room entity.
- Money is `BIGINT` poisha; quantity will be `BIGINT` scaled by 1000. Never floating point.
- Ids are `TEXT`, because they are generated on the phone (D018) and must be storable exactly as sent.
- Timestamps are `TIMESTAMPTZ`, returned as ISO-8601 strings.
- Every query is written with `shop_id` in the WHERE clause, taken from the session token and never from the request (D015).
- Queries always use parameters, never strings built by hand.
---

## D031 — A Sale Is Never Blocked by Stock; Stock May Go Negative
Decision: `sell()` records what happened. It does not check current stock, and nothing refuses a sale because the ledger says there is not enough. If more is sold than was ever recorded, current stock goes negative and stays negative until a restock or a shelf count (`correctStock`) explains it. The New Sale screen warns before confirming, using `stockShortfall()`, but the warning never becomes a refusal.

Rejected: refusing to record a sale that would take stock below zero.

Reason: the shopkeeper is at the counter with the goods in their hand. The goods are leaving whether or not the ledger agrees, so refusing to record the sale does not prevent anything — it only makes the ledger wrong about the real world, and loses the money record too. Negative stock is information: it says the ledger is missing a restock, which is exactly the thing a shelf count fixes. This is also the honest starting state for a real shop, where products exist long before anyone enters an opening quantity.

This is a configurable switch in commercial POS systems ("allow negative inventory"), usually turned off for warehouses that must not oversell online, and on for over-the-counter retail. Hisab is over-the-counter retail, offline, where the counter is the source of truth — so it is on, and not configurable, because there is no case here where blocking the shopkeeper is the right answer.

Sources: [Deal POS — Allow or disallow negative inventory](https://support.dealpos.com/en/articles/4774060-allow-or-disallow-negative-inventory); [Shopify POS — negative inventory behaviour when offline](https://community.shopify.dev/t/pos-ship-to-customer-ignores-inventory-constraints-and-allows-negative-stock/20739).

---

## D032 — A Line Total Rounds Half Away From Zero, Per Line
Decision: a sale line costs `quantityScaled x unitPricePoisha / 1000`, in integer arithmetic. When that division lands exactly halfway between two poisha, it rounds **away from zero** (+0.5 → +1, −0.5 → −1). Each line is rounded on its own and the rounded lines are then added; the sale total is never computed by rounding once at the end.

Rejected, rounding: half upward (toward +∞), and truncation toward zero.
Rejected, timing: totalling in exact arithmetic and rounding once at the end.

Reason:
- **Why away from zero.** A reversal is a line with a negative quantity (D033). 1.25 kg at 90.50 taka is 113.125 taka. Rounding half upward charges 113.13 and refunds 113.12, leaving one poisha behind on every reversed half-poisha line — money that belongs to nobody and that no screen can explain. Away from zero refunds exactly what was charged, so a reversal nets to zero. `reverseSale` is only correct because of this choice, and there is a test that pairs the two directions.
- **Why per line.** A shopkeeper checks a bill by adding the printed lines. If the total were rounded separately, the printed lines could add up to one poisha less than the printed total, and the shop would be right and the app wrong. Two lines of 1.25 kg at 90.50 come to 226.26 by this rule and 226.25 by rounding once at the end; 226.26 is what the receipt shows.

Everything stays integer, so D019 holds: no float touches money or quantity, and no rounding error accumulates over a day's sales.

---

## D033 — A Reversal Is a Second, Opposite Sale
Decision: undoing a sale never edits or deletes the original. It writes a second Sale with a negative total, negative-quantity SaleItems, positive `return` StockMovements referencing the original sale, and — for a credit sale — a BakiEntry that subtracts exactly what the sale added. `reverseSale()` returns all of these in one `SaleTransaction` value, and the phone writes them in one database transaction.

This requires three fields on Sale that `docs/DATA_MODEL.md` had not named, added there in the same change:
- `payment` (cash or credit) — PRD section 8 requires choosing one, and a reversal cannot know whether to clear baki without it;
- `customer_id` (nullable) — PRD section 8 requires associating a customer with a credit sale;
- `reverses_sale_id` (nullable) — the id of the sale being undone.

Rejected: a `reversed` flag on the original Sale (it would edit confirmed history, which `CLAUDE.md` forbids). Rejected: recording only the compensating stock and baki entries and leaving the Sale table alone — then a day's takings would still include the reversed sale, and the only fix would be a special case in every sales query.

Reason: append-only is the same rule the stock and baki ledgers already follow (D001, D002), and it makes the sales ledger self-correcting: summing `total` over a day already excludes anything reversed, because the reversal's negative total is sitting in the same column. `reverses_sale_id` is what answers "has this already been undone?" without a flag, and what lets a reversal be found from the sale it undoes.

D021's atomicity is enforced by the shape rather than by remembering: `SaleTransaction` refuses to exist unless a credit sale has both a customer and a baki entry for exactly the sale total, and unless every line has its stock movement. There is no way to construct a reversal that restores stock and leaves the baki standing — a test asserts each of those constructions throws.

A reversal cannot itself be reversed; undoing an undo is a new sale. Whether a sale has *already* been reversed is a question about stored rows, so `SaleDao.reversalOf()` answers it, not the pure function.

This matches how inventory and accounting systems handle corrections generally: posted entries stay immutable and a compensating entry is appended, rather than the original being rewritten.

Sources: [User Solutions — what an append-only inventory ledger is](https://usersolutions.com/blog/what-is-an-inventory-ledger); [Gravity Payments — void vs. refund](https://gravitypayments.com/blog/void-vs-refund/).

---

## D034 — One Fixture File, Read by Both Kotlin and TypeScript
Decision: the numbers that Android and the backend must agree on live in one plain tab-separated file, `fixtures/m2_sale_stock.tsv`. Both test suites read that file at run time — `android/.../domain/SharedFixtureTest.kt` and `server/src/domain/sharedFixture.test.ts` — and each walks up from its working directory to find it, so it does not matter where the tests are launched from.

Rejected: writing the same cases twice, once in each language. Rejected: JSON, which would need a parser dependency on the Kotlin side (`CLAUDE.md`: add a library only if clearly necessary) — a tab-separated line splits in three lines of code in both languages.

Reason: `docs/PHASE_GUIDE.md` Step 37 asks for the same fixture to pass on both implementations. Two hand-kept copies satisfy that on the day they are written and quietly stop meaning anything the first time one is edited. Reading the real file means a case added to the fixture runs on both sides immediately, and neither side can pass by keeping its own numbers. Proved by changing a number in the file and watching both suites fail on it.

---

## D035 — Customer and BakiEntry Tables Arrive With the Credit Sale, Not With Their Screens
Decision: the `customer` and `baki_entry` tables, and a `findOrCreate(name)` lookup, are built in M2 alongside the credit-sale screen (Step 40), rather than in M3 where the customer and baki *screens* live (Steps 50–51). Nothing else of M3 comes forward: there is no customer list, no customer details screen, no editing, and none of the baki ledger functions (`addCredit`, `receivePayment`, `calculateBalance`, `isOverdue`).

Reason: Step 40's check is that a credit sale "saves the sale, reduces stock, and creates a baki entry". An entry that is not stored has not been created, and an entry cannot say who owes the money without a customer to point at. Building the sale first and the entry a milestone later would also mean either backfilling entries for sales already recorded, or accepting that early credit sales have no baki — both worse than moving one table forward.

Known limit, recorded rather than hidden: a credit sale identifies the customer by name, matched case-insensitively, so two real customers who share a name become one record until M3 adds phone numbers and a customer picker. That is the deliberate choice between two imperfect options — one merged record that a shopkeeper can see and correct, or one person's baki silently split across three rows, which is the failure that actually loses money.

---

## D036 — Sale and Stock Changes Are Not Queued for Sync Until the Server Can Accept Them
Decision: writing a sale, its lines, its stock movements and its baki entry does **not** write a SyncOutbox event yet. The queueing goes in at Steps 46–47, in `SaleRepository.record`, together with the backend endpoints that can accept those events.

Rejected: queueing now "so it is not retrofitted later" (the instinct D016 is built on).

Reason: D016 is about not building a *throwaway* sync mechanism and replacing it — and nothing here is throwaway; the outbox, the envelope, the push endpoint and the idempotency log all already exist and Product already flows through them. This is only about when Sale starts using them. The server has no sale endpoints until Step 46, so a pushed sale event comes back rejected as `UNSUPPORTED_ENTITY`, and `SyncEngine` marks a rejected event `REJECTED` and never retries it. Queueing now would therefore turn every sale into a permanently stuck outbox row and make the "changes waiting to send" count on the Sync screen wrong — telling a shopkeeper that work is pending when nothing will ever send it.

The cost of waiting is one method body, in one place, already marked with a comment naming the step. The cost of not waiting is a misleading sync status and a table full of rubbish to clean up.

---

## D037 — How the New Sale Screen Is Laid Out
Decision: New Sale is one screen. The product list is its body rather than sitting behind a "choose a product" button; tapping a product adds one of it, and tapping the same product again adds one more instead of making a second line. The total, the cash/baki choice and the confirm button are pinned to the bottom, and the cart is reached by tapping the summary bar. Quantity is changed either with 48 dp −/+ buttons or by typing into the field between them.

Reason, from how the screen is actually used and from published cart/POS guidance:
- **One tap per item.** A shopkeeper adds items while a customer waits, so the common path cannot start with a tap that only opens a picker.
- **The total never scrolls away.** A sticky footer carrying the running total and the primary action is the standard cart pattern, and it puts both in the thumb's reach on a tall phone — the bottom of the screen is where one-handed reach is best.
- **Both ways to set a quantity.** Large +/− targets are faster and more accurate than a small numeric field for one or two of something, and touch accuracy rises with target size. But twelve taps for twelve pieces is absurd and half a kilo cannot be tapped at all, so the number stays typeable. The stepper is worth one tap of a *whole* unit for pieces and half a unit for kg and litre, which is how those are actually sold.
- **Confirmation is state, not a timer.** After a sale the screen stays put, the cart empties and a confirmation shows until the next item is added. Navigating away would cost two taps per customer, and a message that fades on a timer would disappear across the language switch that recreates the Activity (D014).

The look is unchanged: every element is an existing `ClayCard`, `ClayButton`, `ClayChip` or `ClayTextField` (D029).

Sources: [Justinmind — shopping cart UI best practices](https://www.justinmind.com/ui-design/shopping-cart); [UXPin — mobile navigation and the thumb zone](https://www.uxpin.com/studio/blog/mobile-navigation-examples/); [Scott & Conzola, *Designing Touch Screen Numeric Keypads: Effects of Finger Size, Key Size, and Key Spacing*](https://journals.sagepub.com/doi/10.1177/107118139704100180).
