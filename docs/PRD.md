# Hisab — Product Requirements Document

## 1. Product Definition

**Hisab** is an offline-first, resource-efficient smart retail assistant for small দোকান and micro-retailers in Bangladesh.

The system focuses on four core areas:

1. Daily retail operations.
2. Bangla/Banglish-friendly interaction.
3. Lightweight retail decision support.
4. Reliable operation on low-end Android devices with intermittent internet access.

Hisab is intended to be:

- pilot-ready software for small retailers — usable in real shops, but not claiming the operational maturity (monitoring, disaster recovery, security audit, incident handling) of commercial production software;
- an open-source research-software artifact;
- experimentally evaluated for a SoftwareX submission.

---

# 2. Primary Product Goal

Enable small shopkeepers to reliably manage:

- sales;
- inventory;
- customer baki;
- received payments;
- future stock requirements;

without requiring:

- continuous internet;
- expensive devices;
- large AI models;
- high digital literacy.

---

# 3. Research Goal

Evaluate whether practical smart-retail functionality can be delivered under constrained deployment conditions using:

- offline-first architecture;
- resource-efficient Android implementation;
- Bangla/Banglish interaction;
- lightweight forecasting;
- transparent decision support;
- reproducible software engineering.

Every major research claim must be experimentally validated.

---

# 4. Target Users

## 4.1 Primary User

Small Bangladeshi দোকান owner/operator.

Expected characteristics:

- primarily Android user;
- may use a low-end device;
- may experience intermittent internet;
- commonly communicates in Bangla or Banglish;
- frequently performs transactions while serving customers;
- may currently use notebooks, memory, calculator apps, or simple digital tools.

## 4.2 Secondary User

Authorized shop employee.

## 4.3 Research/Reuse Audience

Hisab is also built to be reusable by:

- researchers studying micro-retail digitization in low-resource settings;
- researchers studying offline-first mobile system design;
- Bangla/Banglish NLP researchers;
- developers evaluating lightweight retail forecasting or recommendation methods.

This does not change the product itself — it means the code, data, and evaluation results are organized so someone outside this project can inspect, reuse, or build on them (see `docs/RESEARCH_PLAN.md` and the `/research` structure in `docs/PHASE_GUIDE.md`).

---

# 5. Core Research Questions

## RQ1 — Offline Reliability

Can Hisab reliably execute and synchronize retail transactions under unavailable or intermittent connectivity without losing or duplicating confirmed transactions?

## RQ2 — Resource Efficiency

Can Hisab perform its essential operations effectively on low-end Android devices with acceptable:

- application size;
- RAM consumption;
- processing latency;
- storage consumption;
- synchronization overhead?

## RQ3 — Bangla/Banglish Interaction

Can lightweight language-processing methods accurately interpret common Bangla, Romanized Bangla, and code-switched retail queries?

## RQ4 — Lightweight Inventory Intelligence

Can lightweight forecasting methods provide useful short-term demand and reorder recommendations without requiring large machine-learning models?

## RQ5 — Complementary Item Suggestion

Can lightweight, on-device co-occurrence methods produce useful and explainable "bought together" suggestions at the point of sale without requiring large machine-learning models or cloud inference?

---

# 6. Core Product Scope

The research release must include:

- product management;
- sales management;
- stock management;
- customer management;
- baki management;
- payment collection;
- full offline operation;
- reliable synchronization;
- bilingual UI (Bangla default, English always available);
- Bangla/Banglish retail querying;
- lightweight forecasting;
- reorder recommendations;
- complementary item suggestions (instant cross-sell);
- security;
- backup/recovery;
- research benchmarking.

---

# 7. Product Management

The system must support:

- adding products;
- editing products;
- searching products;
- deactivating products;
- product aliases;
- selling units;
- purchase price where required;
- selling price;
- current stock (a capability the system supports — see the note below, this is not a Product field).

Current stock is never stored as its own Product field. It is always derived from StockMovement records (see section 9 and `../DECISIONS.md` D020) — as a query, a view, or a cache that is provably kept consistent with the movements.

Minimum product information:

- product ID;
- shop ID;
- primary name;
- optional Bangla/English name;
- aliases;
- unit;
- selling price;
- optional purchase price;
- active status.

---

# 8. Sales Management

The user must be able to:

- create a sale;
- select one or more products;
- specify quantities;
- view calculated totals;
- select cash or baki;
- associate a customer for credit sales;
- see complementary item suggestions after each product is added, and add a suggested item with a single tap (see Section 17);
- confirm the transaction;
- view transaction history;
- reverse/correct mistakes.

A completed sale must generate the required stock movements.

Confirmed financial history must never be silently rewritten.

Reversing a credit sale must, in one atomic operation, reverse the Sale, its StockMovement(s), and its BakiEntry together. A reversal that restores stock without clearing the matching baki (or the reverse) is not a valid state (`../DECISIONS.md` D021).

---

# 9. Inventory Management

Inventory must use a movement-based ledger.

Required stock movement types:

- restock;
- sale;
- return;
- damage/loss;
- manual correction.

Current stock must be derivable from stock movements.

Conceptually:

```text
Current Stock = Σ StockMovement.quantity_delta
```

The system must retain stock history.

---

# 10. Customer and Baki Management

The system must support:

- customer creation;
- new baki entry;
- payment receipt;
- current outstanding balance;
- transaction history;
- optional due date;
- overdue identification;
- transaction correction/reversal.

Baki must use a ledger rather than an independently editable balance.

Conceptually:

```text
Customer Balance = Σ BakiEntry.amount_delta
```

---

# 11. Offline-First Requirements

The following must work without internet:

- product browsing/search;
- product creation/editing;
- sales;
- stock operations;
- customer management;
- baki recording;
- payment recording;
- transaction history;
- supported Ask Hisab queries;
- lightweight forecasting.

A normal transaction must never require the server to respond before it is committed locally.

---

# 12. Synchronization Requirements

When connectivity becomes available, synchronization must operate automatically.

Required properties:

- local-first writes;
- persistent sync queue;
- globally unique event IDs;
- globally unique, client-generated IDs for every syncable entity, not only sync events (`../DECISIONS.md` D018);
- retry support;
- idempotent server processing;
- duplicate prevention;
- resumable synchronization;
- remote-change retrieval;
- synchronization status;
- recovery after interruption.

A confirmed transaction must not disappear because synchronization failed.

Technical synchronization conflicts should not normally be exposed to the shopkeeper — when one must be surfaced (see below), it is shown as a simple message, not technical detail.

**Conflict policy for mutable entities.** Append-only ledger entries (Sale, StockMovement, BakiEntry) are never edited in place, so they cannot conflict with themselves. Mutable entities (Product, Customer, and any future Shop settings) can be edited on two offline devices at once and need an explicit rule: each carries a server-assigned revision and `updated_at`; a client write must include the revision it edited from; a write against a stale revision is rejected rather than silently overwritten. Full policy: `../DECISIONS.md` D017.

---

# 13. Bangla/Banglish and Bilingual UI Requirements

## 13.1 Language Scope

Hisab must support:

- Bangla UI;
- English UI;
- Bangla text;
- Romanized Bangla;
- Bangla-English code switching;
- Bangla digits;
- English digits;
- common Romanized spelling variations;
- product aliases;
- customer-name matching.

## 13.2 Bilingual UI From v1

All user-facing copy — screens, labels, buttons, dialogs, notifications, error messages, onboarding, and Ask Hisab responses — must be implemented in both Bangla and English starting from the first shipped version. Bilingual coverage is not a post-launch localization task.

- Bangla is the default display language.
- English must always be available and switchable at any time, without reinstalling the app or losing data.
- A feature is not considered complete unless every user-facing string it introduces has both a Bangla and an English version.

## 13.3 Safety and Status Outcome Codes

Outcome/status codes used in safety-relevant flows (for example: sync status, transaction validation results, security/authorization failures) must be represented internally as stable, language-neutral identifiers (e.g. `SYNC_CONFLICT`, `AUTH_INVALID`, `DUPLICATE_EVENT`).

- The underlying code must never change based on the active display language.
- Each code must map to a Bangla message and an English message that carry the same safety meaning; translation must not soften, omit, or alter safety-critical content.
- Logs, telemetry, and sync payloads must carry the language-neutral code, never the localized string.

Representative inputs:

```text
rahim er baki koto
রহিমের baki কত
coke stock koto
2 ta coke bikri
কার কার টাকা বাকি
আজকে কত বিক্রি
```

---

# 14. Lightweight Retail Query Engine

The system must provide deterministic business queries for at least:

- current product stock;
- customer baki;
- overdue customers;
- today's sales;
- sales over a defined period;
- low-stock products;
- predicted stockout;
- reorder recommendation.

Example:

```text
Input:
coke stock koto

Result:
Coca-Cola 500 ml — 8 bottles remaining.
```

The underlying database must remain the source of truth.

A cloud LLM must not be required for core operation.

---

# 15. Lightweight Forecasting

The forecasting subsystem must support short-term product-demand estimation.

Required candidate/baseline methods:

- naïve forecast;
- moving average;
- EWMA;
- seasonal naïve when appropriate;
- Croston-family method for intermittent demand.

Optional research candidate:

- Markov-based forecasting.

Large neural forecasting models are outside the required scope.

The forecasting subsystem must handle insufficient-history cases explicitly.

---

# 16. Reorder Recommendation

The reorder subsystem must consider:

- current stock;
- expected demand;
- optional lead time;
- configurable safety margin.

The recommendation must be explainable.

Example:

```text
Suggested purchase: 13 units.

Current stock: 4
Expected demand before next restock: 14
Safety margin: 3
```

---

# 17. Complementary Item Suggestions (Instant Cross-Sell)

## 17.1 Concept

When a customer buys one item, Hisab must be able to instantly suggest other items that are commonly bought together with it, so the shopkeeper can offer them before the sale is confirmed.

Example:

```text
Customer buys: Biryani

Suggested: Coca-Cola 500 ml
Also bought together: Borhani, Salad
```

## 17.2 Functional Requirements

The system must:

- track which products are purchased together within the same sale;
- compute a ranked list of complementary items for a given product (or set of products already added to the current sale);
- surface this suggestion during the New Sale flow, immediately after a product is added, before the sale is confirmed;
- allow the shopkeeper to add a suggested item to the sale with a single tap, or dismiss it;
- update suggestions as more items are added to the same sale (the suggestion basis is the current cart, not just the first item);
- work fully offline, using only data already stored on the device;
- degrade gracefully when there is insufficient co-purchase history for a product (show no suggestion rather than a low-confidence guess).

## 17.3 Method

Suggestions must be produced with a lightweight, explainable, deterministic method — no cloud LLM and no large machine-learning model is required.

Acceptable baseline approaches:

- co-occurrence counting between products across historical `SaleItem` records within the same `Sale`;
- simple association-rule scoring (e.g. support/confidence/lift) over that co-occurrence data;
- frequency-ranked "bought together" lists per product, optionally time-decayed so recent buying patterns matter more than old ones.

The underlying `Sale`/`SaleItem` records (see Section 8 and Section 24) are the source of truth; no separate mutable suggestion state may override them. An optional precomputed association cache may be maintained for performance, but it must be derivable from and kept consistent with the sale history.

## 17.4 Explainability

Every suggestion must be traceable to the data that produced it, e.g. "bought together in 62% of Biryani sales in the last 90 days." The shopkeeper-facing copy may be simpler, but the underlying reasoning must be inspectable for debugging and research evaluation.

## 17.5 Non-Goals for This Feature

Does not require:

- personalized suggestions keyed to an individual customer's purchase history;
- cross-shop or global suggestion data;
- deep-learning-based recommendation models;
- real-time server-side computation (must work offline, on-device).

---

# 18. Low-Resource Requirements

The implementation must be explicitly optimized for constrained Android devices.

The following must be measured:

- release application size;
- installed size;
- cold-start latency;
- warm-start latency;
- idle RAM;
- peak RAM;
- transaction-save latency;
- database-query latency;
- language-query latency;
- forecasting latency;
- complementary-suggestion latency;
- synchronization bandwidth.

At least one low-end Android device class must be included in evaluation. "Low-end Android device class" must be a specific, reproducible definition, not a vague label — at minimum: device model, Android version, RAM, SoC, storage, and screen characteristics if relevant. This definition, and the app's minSdk, are chosen from research into real target-user devices and then frozen (`../DECISIONS.md` D022) — not guessed.

No performance claim should be made without measurement.

---

# 19. Security Requirements

Baseline: a mobile security review mapped to OWASP MASVS categories — storage, cryptography, authentication, network, platform interaction, code, resilience, privacy. (Do not label anything "MASVS Level 1/2" — OWASP's current MASVS removed that verification-level model.)

## 19.1 Authentication

The backend always derives which shop a request belongs to from the authenticated session (the logged-in user's shop membership). It never trusts a shop_id supplied by the client in a request body or query string. This applies from the first backend endpoint, not only once full security hardening happens (`../DECISIONS.md` D015).

Required:

- secure authentication;
- secure session lifecycle;
- optional biometric/app PIN lock;
- local application locking.

## 19.2 Device Security

Required:

- Android Keystore for sensitive key/token material;
- protected application storage;
- no reusable private API secrets embedded in the application.

## 19.3 Network Security

Required:

- HTTPS;
- authenticated API requests;
- request validation;
- authorization checks.

## 19.4 Backend Security

Required:

- shop/tenant isolation;
- least-privilege access;
- rate limiting where appropriate;
- secure password hashing;
- protected administrative functionality;
- security-relevant audit logging.

## 19.5 Privacy

Do not require:

- NID;
- facial recognition;
- global customer identity;
- global customer blacklist;
- unnecessary contact-book access.

Research datasets must use synthetic or appropriately anonymized information unless ethical approval and consent permit otherwise.

---

# 20. Backup and Recovery

Backup and synchronization are different guarantees. Sync propagates every change — including an accidental deletion — to every device. A real backup must allow restoring shop data to an earlier point in time, independent of what has already synced (`../DECISIONS.md` D024).

The system must support:

- durable local storage;
- safe database migrations;
- recovery after process termination;
- recovery after interrupted synchronization;
- backup;
- restore testing — confirming a restore actually returns data to a specific prior point in time, not just that a backup file exists;
- encrypted backup where external storage/cloud storage is involved.

---

# 21. UX Requirements

Hisab must prioritize:

- speed;
- simplicity;
- one-handed operation;
- confidence;
- recoverability.

Primary navigation:

- Home
- Sales
- Baki
- Stock
- More

Home should expose:

- today's sales;
- outstanding baki;
- low-stock summary;
- quick actions.

Primary quick actions:

- New Sale
- Add Baki
- Receive Payment
- Add Stock

Required UX behaviors:

- minimal taps for common tasks;
- large touch targets;
- automatic numeric keyboard for amount entry;
- immediate local save acknowledgement;
- visible offline status;
- visible sync status;
- simple language;
- understandable error messages;
- clear transaction correction/reversal;
- non-intrusive complementary item suggestions during New Sale (single-tap add or dismiss, never blocking checkout).

## Typography

Hisab must use exactly two font families for all user-facing text:

- **Noto Sans Bengali** — all Bangla text.
- **Merriweather** — all English text.

Rules:

- No other font family may be introduced without explicit approval.
- These fonts apply globally: every screen, every UI state (loading, empty, error, disabled, success, offline), and any labels rendered on maps or other embedded/third-party views.
- When content mixes Bangla and English (e.g. a Bangla sentence containing an English product name), each script segment must render in its own correct font rather than the whole string falling back to one font.

---

# 22. Android Technical Requirements

Recommended production stack:

- Kotlin;
- Jetpack Compose;
- Room;
- WorkManager;
- DataStore;
- Android Keystore;
- OkHttp/Retrofit or equivalent;
- kotlinx.serialization;
- AndroidX per-app language support (`AppCompatDelegate`, backed by `LocaleManager` on supported Android versions) for Bangla/English switching — not a custom language-state mechanism (`../DECISIONS.md` D014);
- bundled font assets: Noto Sans Bengali (Bangla) and Merriweather (English) — no other font family without approval.

Dependencies must be kept deliberately minimal.

minSdk = 26 (Android 8.0), compileSdk = targetSdk = 36 (Android 16). Chosen from research into real target-user devices, then frozen (`../DECISIONS.md` D022, D025) — not picked by guesswork.

Room database schemas are exported, versioned, and committed to the repository from the first schema onward; every schema change has a migration and a migration test in CI. Destructive migrations are never used against production user data.

---

# 23. Backend Technical Requirements

Recommended stack:

- TypeScript;
- Fastify;
- PostgreSQL.

Architecture:

**Modular monolith**

Core modules:

- auth;
- shops;
- products;
- customers;
- sales;
- inventory;
- baki;
- sync.

Microservices are not required.

---

# 24. Minimum Domain Model

Value-type conventions used throughout this model (full reasoning: `../DECISIONS.md` D019):

- **Money** fields are integers in the smallest currency unit (poisha for BDT). Never floating-point.
- **Quantity** fields are integers scaled by 1000 (3 decimal places): 0.5 kg = 500, 3 pieces = 3000. Never floating-point.
- **id** fields on every entity below (except SyncOutbox/SyncMetadata, which have their own identity fields) are globally unique and generated on the device at creation time — the same ID is used locally and on the server (`../DECISIONS.md` D018).
- Mutable entities (Product, Customer) carry `revision` and `updated_at` for conflict detection, and `deleted_at` for tombstoned deletes (`../DECISIONS.md` D017). Append-only ledger entities (Sale, SaleItem, StockMovement, BakiEntry) do not need these — they are never edited in place.
- Transaction-producing entities (Sale, StockMovement, BakiEntry) record `occurred_at` (when it actually happened, set by the device) separately from `server_received_at` (when the backend processed the sync event) (`../DECISIONS.md` D019).

## Shop

- id
- name
- currency (default `"BDT"`)
- timezone
- created_at

## User

- id
- shop_id
- role
- email
- password_hash

## Product

- id
- shop_id
- name
- aliases
- unit
- purchase_price (money)
- selling_price (money)
- active
- revision
- updated_at
- deleted_at (nullable)

## Customer

- id
- shop_id
- name
- phone_optional
- revision
- updated_at
- deleted_at (nullable)

## Sale

- id
- shop_id
- occurred_at
- server_received_at
- total (money)

## SaleItem

- sale_id
- product_id
- quantity (quantity)
- unit_price (money)

## StockMovement

- id
- product_id
- movement_type
- quantity_delta (quantity)
- source_reference
- occurred_at
- server_received_at

## BakiEntry

- id
- customer_id
- amount_delta (money)
- type
- reference
- due_date_optional
- occurred_at
- server_received_at

## SyncOutbox

- event_id
- entity_type
- entity_id
- operation
- payload
- base_revision (for updates/deletes of mutable entities; see D017)
- client_timestamp
- status
- retry_count

## SyncMetadata

- device_id
- last_server_cursor

---

# 25. Synchronization Architecture

Hisab must use an outbox-based synchronization approach.

Conceptual workflow:

```text
Business transaction
        ↓
Local database transaction
        ├── domain changes
        └── SyncOutbox event
        ↓
UI immediately updated
        ↓
WorkManager
        ↓
Backend
        ↓
Idempotent event processing
        ↓
Acknowledgement
        ↓
Local event marked synchronized
```

Remote changes should be retrieved using a server revision/cursor mechanism or an equivalently deterministic approach.

---

# 26. Testing Requirements

## Unit Testing

Required for:

- stock calculations;
- baki calculations;
- sale calculations;
- reversals;
- language normalization;
- intent parsing;
- forecasting algorithms;
- co-occurrence/association scoring for complementary item suggestions.

## Integration Testing

Required for:

- sale → stock movement;
- credit sale → baki entry;
- payment → baki update;
- local transaction → outbox;
- sync;
- server idempotency;
- sale item added → complementary suggestion updated.

## Failure Testing

Required for:

- unavailable network;
- network timeout;
- repeated request;
- application termination;
- phone restart;
- interrupted synchronization;
- database migration.

---

# 27. Research Evaluation — Offline/Sync

Test at minimum:

- full offline operation;
- unstable connectivity;
- timeout;
- duplicate upload;
- app termination;
- device restart;
- server downtime;
- interrupted synchronization;
- large queued event batches;
- two-device scenarios.

Measure:

- confirmed transactions lost;
- duplicate transactions;
- convergence success;
- convergence time;
- synchronization traffic.

Primary correctness target:

**0 lost confirmed transactions.**

---

# 28. Research Evaluation — Resource Efficiency

Benchmark at minimum:

- low-end Android (exact model, Android version, RAM, SoC, storage — see `../DECISIONS.md` D022, not a vague label);
- mid-range Android;
- reference/modern Android.

Measure:

- application size;
- installed size;
- startup latency;
- RAM;
- local-write latency;
- database-query latency;
- language-processing latency;
- forecast latency;
- complementary-suggestion latency;
- synchronization bandwidth.

---

# 29. Research Evaluation — Bangla/Banglish

Create a dedicated retail-language benchmark.

Include:

- Bangla;
- Romanized Bangla;
- mixed Bangla-English;
- spelling variants;
- Bangla digits;
- English digits;
- product names;
- customer names.

Measure:

- intent accuracy;
- entity extraction;
- amount extraction;
- product matching;
- end-to-end task success;
- latency;
- language-component storage footprint.

---

# 30. Research Evaluation — Forecasting

Evaluation must be chronological.

Use walk-forward validation.

Compare at minimum:

- naïve;
- moving average;
- EWMA;
- seasonal naïve where appropriate;
- Croston-family approach;
- optional Markov method.

Evaluate (exact metric definitions frozen in `RESEARCH_PLAN.md` before experiments run — "forecast error" alone is not specific enough):

- forecast error;
- stockout consequences;
- overstock consequences;
- computation latency;
- algorithm state/model size.

The final algorithm must be selected based on useful trade-offs rather than sophistication.

---

# 31. Research Evaluation — Complementary Item Suggestions

Use held-out evaluation: withhold a chronological slice of sale history and measure whether the co-occurrence method suggests items that were actually added in that held-out data.

Compare at minimum:

- frequency-ranked "bought together" baseline;
- association-rule scoring (support/confidence/lift);
- optional time-decayed variant.

Measure:

- suggestion precision/recall against held-out sales;
- hit rate (suggested item was accepted by the shopkeeper);
- acceptance rate in real/simulated usage;
- computation latency;
- storage footprint of the association data/cache.

The final method must be selected based on usefulness and explainability, not sophistication.

---

# 32. Research Reproducibility

The public research release must include:

- source code;
- open-source license;
- README;
- architecture documentation;
- database documentation;
- API documentation;
- installation instructions;
- build instructions;
- dependency versions;
- automated tests;
- synthetic/sample data;
- benchmark scripts;
- experiment configuration;
- experimental results;
- SECURITY.md;
- CONTRIBUTING.md;
- CHANGELOG.md;
- CITATION.cff;
- tagged release;
- permanent archived release.

---

# 33. Explicit Non-Goals

The initial SoftwareX-oriented research release does not require:

- OCR;
- handwritten OCR;
- SMS;
- USSD;
- cloud LLM chatbot;
- facial recognition;
- global customer blacklist;
- cross-shop customer reputation;
- deep-learning forecasting;
- deep-learning-based product recommendation;
- personalized/customer-specific cross-sell suggestions;
- advanced ERP;
- full accounting system;
- multi-branch enterprise management;
- payment-gateway integration;
- blockchain.

---

# 34. Research Release Definition of Done

Hisab reaches research-release status only when:

- core retail workflows work fully offline;
- synchronization survives tested failures;
- no confirmed transaction is lost in defined reliability tests;
- Bangla/Banglish processing is quantitatively evaluated;
- forecasting is compared against baselines;
- complementary item suggestions are evaluated against a co-occurrence baseline;
- resource consumption is benchmarked;
- security requirements are validated;
- automated tests pass;
- clean-machine build instructions work;
- benchmark scripts reproduce reported results;
- source code is publicly versioned;
- the release is permanently archived.