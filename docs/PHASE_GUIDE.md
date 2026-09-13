# Hisab — Build Plan

This is a step-by-step checklist. Each step is small on purpose — build it, check it, then move to the next one. Don't move on until a step actually works; that's easier than debugging five steps at once later.

You don't have to follow the order exactly. If it makes sense to jump ahead or come back, that's fine — just don't skip the checks.

For each feature (Product, Sale, Baki, ...), Android and backend steps sit next to each other, so you build and check both sides together instead of one going first and the other catching up later.

Full requirements: `PRD.md`. Why things are done a certain way: `../DECISIONS.md`.

Step numbers are continuous across the whole plan (Step 1, Step 2, ...), grouped under M0–M9. A milestone (M0, M1, ...) is done when every step in it is checked off.

---

# M0 — Setup

The goal of M0 is a working skeleton on both sides, including a few things that are much cheaper to build now than to add later: Bangla-first language support, basic login, and the real sync system.

## Step 1 — Create the repo folders

```text
hisab/
├── android/
├── server/
├── research/
├── docs/            (already exists)
├── scripts/
├── .github/
├── README.md        (already exists)
├── LICENSE          (already exists)
├── CONTRIBUTING.md
└── CHANGELOG.md
```

Check: every folder exists, nothing else changed.

## Step 2 — Create empty backend module folders

```text
server/src/modules/
  auth/
  shops/
  products/
  customers/
  sales/
  inventory/
  baki/
  sync/
```

No code inside yet — just the folders. Check: the server project still builds with these empty folders in place.

## Step 3 — Set up Git, formatting, and linting

Standard config for both Android and the server. Check: running the formatter/linter locally doesn't error out.

## Step 4 — Set up CI

```text
Android compile
Android unit tests
Server compile
Server unit tests
Lint
```

Check: a CI run passes on this near-empty codebase.

## Step 5 — Write down the money/quantity/currency/time rules

Don't decide this while building a feature later — decide it now, once, and write it in `PRD.md` section 24 and `../DECISIONS.md` D019 (already done — this step is about making sure everyone building against these docs has actually read it):

- Money: integer in the smallest unit (poisha), never a decimal/float.
- Quantity: integer × 1000 (3 decimal places), never a decimal/float.
- Currency: a `currency` field, `"BDT"` for now.
- Time: `occurred_at` (when it really happened) separate from `server_received_at` (when the backend saw it); a `timezone` field on Shop.

Check: you can explain these four rules from memory before Step 6.

## Step 6 — Write down the ID and conflict rules

- Every entity that can be created offline gets its own globally unique ID, made on the device, used forever (locally and on the server) — not just sync events.
- Product and Customer (the only entities anyone edits in place) get a `revision`, `updated_at`, and `deleted_at`.

Full reasoning: `../DECISIONS.md` D017, D018. Check: you can explain why Sale/StockMovement/BakiEntry don't need a revision but Product/Customer do.

## Step 7 — Write down the layering rule

Keep it small. Three folders on Android, two on the server:

```text
Android                          Server
  ui/      screens              domain/          the same rules, same words
  domain/  the rules            modules/<name>/  routes + database for one feature
  data/    database + network
```

One shape for a concept, used end to end. If the Room entity, the JSON on the wire, and the Postgres row hold the same fields, use the same shape — no mapper. Write a mapper the day two of them actually differ, at that one boundary only.

Full reasoning: `../DECISIONS.md` D026 (which replaces D023's longer mapper chain — that was more structure than an MVP needs).

Check: you can name the three Android folders and say what goes in each, without looking.

## Step 8 — Add Bangla and English string resources

Bangla resource file is the default; English is the alternate. Full detail: `LOCALIZATION.md`. Check: a string added in Bangla only, with no English version, is visibly a problem (even if nothing enforces it yet — that's Step 10).

## Step 9 — Add language switching

Use AndroidX per-app language APIs (`AppCompatDelegate` / `LocaleManager`) — not a custom mechanism (`../DECISIONS.md` D014). Check: you can switch the app from Bangla to English and back, and it remembers the choice after restarting the app.

## Step 10 — Add the localization-completeness CI check

Fail the build if a string key exists in one language file but not the other. Check: add a string in Bangla only, on purpose, and confirm CI fails; then add the English version and confirm CI passes again.

## Step 11 — Build the login endpoint

Minimal auth: a login that issues a session token. A User is linked to a Shop. Check: logging in with valid credentials returns a token; logging in with bad credentials doesn't.

## Step 12 — Require a valid token on every endpoint, and derive shop_id from it

No endpoint should read shop_id from the request body or query string — it always comes from the token (`../DECISIONS.md` D015). Check: a request with no token is rejected; a request with a token for Shop A can't be made to act on Shop B's data by changing a parameter.

## Step 13 — Create the SyncOutbox and SyncMetadata tables

Fields: `DATA_MODEL.md`. Check: the tables exist locally on Android; nothing writes to them yet.

## Step 14 — Define the sync event shape

One envelope used for every push: event_id, entity_type, entity_id, operation, payload, base_revision (for edits/deletes), client timestamp. Check: you can describe this shape without looking it up.

## Step 15 — Build the push endpoint

Accepts a batch of events. Checks each event_id against a processed-event log so the same event is never applied twice. Check: pushing the same event twice has the same effect as pushing it once.

## Step 16 — Build the pull endpoint

```text
GET /sync/changes?after=<cursor>
```

Returns changes since the given cursor, plus a new cursor. Check: calling it with no cursor returns everything; calling it again with the returned cursor returns nothing new (until something changes).

## Step 17 — Add the conflict check

A push that edits Product or Customer with a stale `base_revision` is rejected with `REVISION_CONFLICT`, not silently applied. Check: edit the same (test) record twice with the same base revision — the second one is rejected.

## Step 18 — Research target devices and freeze minSdk

Look into what Android versions, RAM, and chipsets are common among small-shop owners in Bangladesh. Pick and write down: minSdk, and one specific low-end reference device (model, Android version, RAM, SoC, storage). Full reasoning: `../DECISIONS.md` D022. Result: `../DECISIONS.md` D028 — minSdk 26 frozen permanently; reference device Tecno Spark Go 2 (3 GB + 64 GB). Check: the choice is written down somewhere in the repo, not just remembered.

## Step 19 — Write the research data plan

Where the language dataset and forecasting dataset will come from, who writes the held-out language examples, consent, and versioning. Full detail: `RESEARCH_PLAN.md`, "Research Data Plan" section (already written — this step is confirming it before M5/M6 need it). Check: you know the answer to "who is allowed to write the held-out test sentences" without re-reading the doc.

## Step 20 — Build one Android screen that runs

Using the language system from Steps 8–10. Doesn't need to do anything yet. Check: the app installs and opens to this screen, in Bangla by default.

## Step 21 — Build one backend health-check endpoint

Behind the login from Steps 11–12. Check: calling it without a token fails; calling it with a token succeeds.

## Step 22 — Check the whole skeleton on a clean machine

Follow `README.md` on a fresh checkout. Check: both sides build and run using only the README instructions, and CI is green.

**M0 is done when Steps 1–22 all check out.**

---

# M1 — Product

The first real feature, and the first thing to actually flow through the sync system from M0.

## Step 23 — Create the Product table in Room

Fields: id, shop_id, name, aliases, unit, purchase_price, selling_price, active, revision, updated_at, deleted_at (`DATA_MODEL.md`). Check: you can insert and read back a Product in a unit test.

## Step 24 — Write local Product tests

Create, edit, search, deactivate. Check: all pass.

## Step 25 — Build the Product List screen

Check: shows products from the local database, in Bangla by default.

## Step 26 — Build the Product Add/Edit screen

Check: adding a product offline shows it in the list immediately, no waiting on the network.

## Step 27 — Build product search

Check: searching by name and by alias both work.

## Step 28 — Check the whole local Product flow in airplane mode

Add, edit, search, all with the network off. Check: nothing breaks, nothing waits.

## Step 29 — Create the Product table in Postgres

Via a migration, mapped from the domain model (not assumed identical to the Room entity — Step 7). Check: the migration runs cleanly on an empty database.

## Step 30 — Build the backend product endpoints

Create, edit, list, search. shop_id always comes from the token (Step 12), never the request. Check: a request with someone else's shop's token can't see or edit this shop's products.

## Step 31 — Set up Room schema export and a migration test in CI

Starting now, and for every schema change from here on (`PRD.md` section 22). Check: CI fails if a schema changes without a matching migration.

## Step 32 — Connect Product saves to the push endpoint from M0

Check: a product added offline appears in the SyncOutbox, and gets pushed once the network is back.

## Step 33 — Connect Product loading to the pull endpoint from M0

Check: a product created directly through the backend API shows up on the phone after a pull sync.

## Step 34 — Check a conflict on purpose

Edit the same product from two (simulated) devices offline, then let both sync. Check: the second one to arrive gets rejected with `REVISION_CONFLICT`, not silently overwritten.

**M1 is done when Steps 23–34 all check out.**

---

# M2 — Sale and Stock

## Step 35 — Write the stock functions

Plain functions, no UI or network: `restock()`, `sell()`, `returnStock()`, `damage()`, `correctStock()`, `calculateCurrentStock()`. Check: unit tests pass for each.

## Step 36 — Write the sale functions

`calculateLineTotal()`, `calculateSaleTotal()`, `completeCashSale()`, `completeCreditSale()`, `reverseSale()`. Check: unit tests pass for each.

## Step 37 — Write the same test cases for the backend

Android is Kotlin and the backend is TypeScript, so the code isn't literally shared — but the same fixture (e.g. "sale of 3 items at unit_price 50 → line total 150") should pass on both. Check: run that fixture against both implementations, same expected result.

## Step 38 — Create the Sale, SaleItem, and StockMovement tables in Room

Fields: `DATA_MODEL.md` — note `occurred_at`/`server_received_at`, not one timestamp. Check: insert/read tests pass.

## Step 39 — Build the New Sale screen — cash

Check: a cash sale, offline, saves the sale and reduces stock immediately.

## Step 40 — Build the New Sale screen — credit

Check: a credit sale, offline, saves the sale, reduces stock, and creates a baki entry (baki UI itself comes in M3, but the entry must be created correctly here).

## Step 41 — Build the Stock screen

View current stock, add stock (restock). Check: restocking updates the displayed stock immediately, offline.

## Step 42 — Build the Transaction History screen

Check: shows sales and stock movements in order.

## Step 43 — Build transaction reversal/correction

Check: reversing a cash sale restores stock correctly.

## Step 44 — Check the credit-sale reversal rule specifically

Reversing a credit sale must undo the sale, restore stock, AND clear the matching baki — all together, or not at all (`../DECISIONS.md` D021). Check: write a test where you reverse a credit sale and confirm all three changed together; there is no way to end up with stock restored but baki still owed.

## Step 45 — Create the Sale/SaleItem/StockMovement tables in Postgres

Check: migration runs cleanly.

## Step 46 — Build the backend sale and stock endpoints

Check: same shop-scoping rule as Product (Step 30).

## Step 47 — Check sale data syncs correctly

A sale made on the phone should reach the backend, and stock/history should match on both sides after sync. Check: run all four workflows below end to end, phone + backend, and check the stock ledger after each:

```text
Cash Sale:    Home → New Sale → Product → Quantity → Cash → Confirm
Credit Sale:  Home → New Sale → Product → Quantity → Customer → Baki → Confirm
Restock:      Stock → Product → Add Stock → Quantity → Confirm
Correction:   History → Transaction → Reverse/Correct → Confirm
```

**M2 is done when Steps 35–47 all check out.**

---

# M3 — Customer, Baki, Payment

## Step 48 — Write the baki functions

`addCredit()`, `receivePayment()`, `reverseEntry()`, `calculateBalance()`, `isOverdue()`. Check: unit tests pass.

## Step 49 — Write the same test cases for the backend

Check: the fixture `credit 500, payment 200, credit 100 → balance 400` passes on both Android and backend implementations.

## Step 50 — Create the Customer table in Room

With revision/updated_at/deleted_at, same as Product. Check: insert/read tests pass.

## Step 51 — Create the BakiEntry table in Room

Ledger entity, no revision needed (it's never edited in place). Check: insert/read tests pass.

## Step 52 — Build the Customer List screen

Check: shows customers, offline.

## Step 53 — Build the Customer Details screen

Check: shows a customer's current balance, calculated from BakiEntry records (never a stored number).

## Step 54 — Build the Add Baki screen

Check: adding credit offline updates the balance immediately.

## Step 55 — Build the Receive Payment screen

Check: recording a payment offline updates the balance immediately.

## Step 56 — Run the exact balance fixture on the real screens

Give a customer 500 in baki, receive 200, add 100 more credit. Check: the balance shown is exactly 400.

## Step 57 — Check a reversal

Reverse one of those entries. Check: the balance updates correctly again.

## Step 58 — Create the Customer and BakiEntry tables in Postgres, and the backend endpoints

Check: same shop-scoping rule as before; a baki entry made on the phone reaches the backend correctly.

## Step 59 — Do a UX pass

Taps needed for common tasks, touch target size, automatic numeric keyboard for amounts, confirm/reversal wording, search speed, visible offline indicator. Check: walk through Add Baki and Receive Payment start to finish and count the taps — does it feel fast?

## Step 60 — Check bilingual completeness

No screen, state (loading/empty/error/disabled/success/offline), or message is missing its Bangla or English version. Check: switch the whole app to English and walk through every M1–M3 screen; switch back to Bangla and do it again.

## Step 61 — Check fonts

Bangla text in Noto Sans Bengali, English in Merriweather, mixed-language strings render each part correctly. Check: look at a screen with a mixed string (e.g. a Bangla sentence containing an English product name) and confirm both fonts show correctly in the same line.

**M3 is done when Steps 48–61 all check out.**

---

# M4 — Harden Sync

Product, Sale, Stock, Customer, and Baki all exist now and run on the real sync system from M0. This milestone stress-tests that system — it doesn't rebuild it.

## Step 62 — Add background sync with WorkManager

Connectivity-aware, so it only tries when there's a network. Check: turning wifi on triggers a sync attempt without opening the app.

## Step 63 — Add retry with backoff

Check: a failed sync attempt retries later instead of giving up or spamming retries.

## Step 64 — Add recovery after the app is killed

Check: force-close the app mid-sync, reopen it — sync resumes correctly, nothing is lost or duplicated.

## Step 65 — Add recovery after the phone restarts

Check: same test as Step 64, but restart the phone instead.

## Step 66 — Show a simple sync status to the user

E.g. "Saved on this device — 3 changes waiting to sync." No technical detail. Status codes like `SYNC_CONFLICT`/`SYNC_PENDING` stay the same internally in both languages — only the shown message is translated, and it must mean the same thing in both (D011). Check: the message appears in Bangla and English correctly.

## Step 67 — Make sure a server crash mid-apply can't leave things half-done

The processed-event log from M0 should let a retried push resume safely. Check: simulate a crash between two events in a batch and confirm a retry doesn't double-apply the first one.

## Step 68 — Failure-test: offline and network problems

```text
offline operation, connection loss during upload, timeout after server processing, invalid response
```

Check each one individually — nothing is lost, nothing is duplicated.

## Step 69 — Failure-test: repeats and restarts

```text
duplicate request, app killed before ACK, app killed after ACK, phone restart, server outage
```

Check each one individually.

## Step 70 — Failure-test: scale

Queue 100 events, then 1,000. Check: sync still completes correctly, just slower.

## Step 71 — Failure-test: two devices

Same shop, two phones, syncing around the same time. Check: both end up with the same data.

## Step 72 — Failure-test: conflicting edits under real conditions

Two devices edit the same Product or Customer at the same time, for real (not the single scripted test from Step 34). Check: the conflict policy still holds up.

**M4 is done when Steps 62–72 all check out. This is the one milestone worth being strict about — don't move on with any of these unresolved.**

---

# M5 — Ask Hisab (Bangla/Banglish Understanding)

## Step 73 — Build the normalization pipeline

Unicode normalization → case normalization → digit normalization → tokenization → Romanized Bangla normalization → alias resolution. Check: run a few example inputs through it and inspect the output at each stage.

## Step 74 — Build product/customer alias matching

Check: "coke" matches "Coca-Cola 500ml" if that alias was set in M1.

## Step 75 — Collect the development language dataset

Bangla, Romanized Bangla, mixed, following the Research Data Plan (Step 19). Example:

```text
rahim er baki koto
রহিমের baki কত
coke koyta ase
চিনি stock কত
ajke koto sell hoise
```

Check: each example is annotated with text, language_type, intent, entities.

## Step 76 — Collect the held-out test set

Written independently — not by whoever built the rules, and not written after seeing how the system behaves. Check: confirm who wrote it, and that it's a different person from whoever wrote Steps 73–74.

## Step 77 — Freeze the held-out set

Check: it's saved somewhere it won't be edited or peeked at again until the M8 language experiment.

## Step 78 — Build GET_STOCK

Check: "coke stock koto" returns the right number, in the typed language.

## Step 79 — Build GET_CUSTOMER_BAKI

Check: "rahim er baki koto" returns the right balance.

## Step 80 — Build GET_OVERDUE

Check: returns customers past their due date.

## Step 81 — Build GET_TODAY_SALES

Check: matches the actual day's sales total.

## Step 82 — Build GET_PERIOD_SALES

Check: matches a manually-computed total for the same period.

`GET_LOW_STOCK`, `GET_PREDICTED_STOCKOUT`, and `GET_REORDER` are **not** part of this milestone — they need forecasting and the reorder engine, which don't exist until M6. They're Step 90.

## Step 83 — Measure accuracy and latency on the development set

Intent accuracy, entity extraction, amount extraction, product matching, latency, storage footprint. Check: numbers are written down somewhere, not just "it seemed to work."

**M5 is done when Steps 73–83 all check out.**

---

# M6 — Smart Extras

## Step 84 — Build the forecasting data pipeline

Completed sales → daily demand per product (date, product_id, quantity_sold). Handle missing days, zero demand, discontinued products, short history. Never shuffle — keep chronological order. Check: feed it a known sale history, confirm the daily demand output is correct.

## Step 85 — Build the naive and moving-average forecasting methods

Common interface: `fit(history)`, `forecast(horizon)`. Check: tests pass for constant demand, zero demand, intermittent demand, short history.

## Step 86 — Build EWMA and seasonal naive

Check: same test cases as Step 85.

## Step 87 — Build the Croston method

Check: same test cases, with particular attention to intermittent demand (this is what Croston is for).

## Step 88 — Run walk-forward evaluation and compare methods

```text
Days 1–30 → predict 31–37
Days 1–37 → predict 38–44
Days 1–44 → predict 45–51
```

Use the metrics frozen in `RESEARCH_PLAN.md` (MAE, MASE, stockout/overstock outcomes, latency, state size). Check: a comparison table exists with real numbers for every method.

## Step 89 — Build the reorder engine

Inputs: current stock, predicted demand, lead time, safety margin. Output: a quantity plus a plain-language explanation, e.g. "Buy 13 units. Current stock: 4. Expected demand: 14. Safety margin: 3." Check: the explanation numbers actually add up.

## Step 90 — Add GET_LOW_STOCK, GET_PREDICTED_STOCKOUT, GET_REORDER to Ask Hisab

Now that forecasting and reorder exist. Check: each returns a sensible answer using real data.

## Step 91 — Build the co-occurrence counter

Convert sales to (sale_id, product_id) pairs; `computeCooccurrence(saleHistory)`. Check: a known pair of frequently-co-purchased products shows a high count.

## Step 92 — Build the suggestion ranking

`rankComplements(productIds, topN)` — start with co-occurrence counting and association-rule scoring. Check: ranks the known pair from Step 91 near the top.

## Step 93 — Wire suggestions into the New Sale screen

```text
Product added to cart → rankComplements(current cart) → show top suggestions → tap to add, or dismiss
```

Check: adding a product to a sale shows a relevant suggestion, and tapping it adds the item.

## Step 94 — Check the suggestion edge cases

Two products always bought together (should suggest); products never bought together (should not suggest); a brand-new product with no history (should show nothing, not a guess). Check: all three behave correctly.

## Step 95 — Add "not enough data yet" handling everywhere it's needed

Forecasting, reorder, and suggestions should all say so plainly instead of guessing when history is short. Check: test each with a product that has almost no sales history.

**M6 is done when Steps 84–95 all check out.**

---

# M7 — Harden and Measure

## Step 96 — Go through the security checklist

`SECURITY.md`, mapped to OWASP MASVS categories (storage, cryptography, authentication, network, platform interaction, code, resilience, privacy). Check: every item has a yes/no answer, not a guess.

## Step 97 — Run the threat tests

```text
cross-shop access, stolen token, invalid ownership, replayed sync event,
injected input, exposed secret, backup leakage
```

Check: each one is tried on purpose and confirmed blocked.

## Step 98 — Test backup and restore

Confirm a restore actually returns data to a specific earlier point in time — not just that a backup file exists (`../DECISIONS.md` D024). Check: back up, make a change, restore, confirm the change is gone.

## Step 99 — Test recovering from an accidental bulk delete

One that has already synced to every device. Check: the backup from Step 98 can undo it.

## Step 100 — Expand tests across everything already built

```text
Domain:        sales, stock, baki, reversals
Database:      atomic transactions, migrations, large record volumes
Sync:          retries, duplicates, convergence, multi-device, conflict policy
Language:      normalization, intent, entities
Forecasting:   edge cases, regression tests
Suggestions:   co-occurrence edge cases, insufficient-history behavior
Backend:       authorization, tenant isolation, malformed requests
```

Check: test coverage for each area, not just the happy path.

## Step 101 — Measure resource use on the low-end reference device

The device frozen in `../DECISIONS.md` D028 (Tecno Spark Go 2, 3 GB + 64 GB). First record its build number, security patch, `ro.config.low_ram`, and total RAM, as D028 lists. App size, installed size, cold/warm start, RAM, local-save latency, database-query latency, Ask Hisab latency, forecasting latency, suggestion latency, sync bandwidth. Check: every number is measured, not estimated.

## Step 102 — Find the actual bottlenecks

Look at the Step 101 numbers before changing anything. Check: you can point to the slowest 2–3 things with evidence.

## Step 103 — Optimize

App Bundle + resource shrinking, remove unnecessary dependencies, duplicate assets, unused fonts, unused resources, excessive logging, oversized ML dependencies. Check: each change is aimed at something identified in Step 102.

## Step 104 — Re-measure and document before/after

Check: a written comparison exists for every metric from Step 101.

**M7 is done when Steps 96–104 all check out.**

---

# M8 — Reliability and Research Experiments

## Step 105 — Freeze the release candidate

Algorithms, test data, evaluation scripts, benchmark configuration. Check: from this point, any change affecting an experiment is versioned and documented, not made quietly.

## Step 106 — Run the offline reliability experiment

Fully offline operation, queued batches, failed/repeated uploads, interrupted sync, restarts, multi-device convergence, concurrent conflicting edits. Measure: transaction loss, duplicates, convergence success/time, network use. Check: **0 lost confirmed transactions** — this is the target, not a nice-to-have.

## Step 107 — Run the low-end device experiment

On the M0 reference device (`../DECISIONS.md` D028), plus a mid-range and a modern device. Also install the release build on an Android 8.0 (API 26) system image and run the core flow — open, switch language, record a sale offline, sync — to prove minSdk 26 actually works. Check: results recorded for all three devices, and the API 26 run passes.

## Step 108 — Run the language experiment

Only on the frozen held-out set from Step 77. Report Bangla, Romanized Bangla, and code-switched input separately. Check: the held-out set genuinely wasn't touched since Step 77.

## Step 109 — Run the forecasting experiment

Chronological, all selected methods, using the metrics frozen earlier. Check: final method chosen by overall trade-off, documented, not just "the best MAE."

## Step 110 — Run the suggestion experiment

Held-out chronological sale data, compare the baseline vs. association-rule scoring vs. any time-decayed variant. Check: precision/recall, hit rate, acceptance rate, latency, and storage size are all recorded.

## Step 111 — Run user validation

After required ethics/consent. Sale entry, baki entry, payment, stock lookup, balance query, reorder lookup, accepting/dismissing a suggestion, offline operation, correcting an error. Check: completion rate, time, errors, assistance needed, and qualitative feedback are all recorded.

**M8 is done when Steps 105–111 all check out.**

---

# M9 — Release and Publication

## Step 112 — Run production release hardening checks

Database migrations, backup/restore, security settings, error recovery, crash handling, permission handling, offline behavior, server deployment, API logging, release signing. Check: clean-device installation test passes.

## Step 113 — Verify backup/restore end to end on a release build

Distinct final check, not the same as Steps 98/99. Check: restore actually works on the real release build, not just a dev build.

## Step 114 — Build the research reproducibility package

```text
/research
    /language
    /forecasting
    /suggestions
    /sync
    /performance
```

Benchmark scripts, dataset-generation instructions, evaluation scripts, configuration, raw results, aggregation scripts. Check: someone else could reproduce the reported tables without touching production code.

## Step 115 — Tag and archive the release

`v1.0.0` — source, license, tagged version, installation guide, architecture docs, API docs, sample/synthetic data, benchmark scripts, tests, research protocols and results, `CITATION.cff`, `SECURITY.md`, `CHANGELOG.md`. Check: archived permanently, not just tagged in a branch that could be deleted.

## Step 116 — Write the SoftwareX submission

Only after Step 115. Motivation, architecture, functionality, workflows, empirical evaluation, comparison with related work, impact, limitations, reuse potential (including the research/reuse audience in `PRD.md` section 4.3), citation of the exact archived version. Describe the software as pilot-ready, not commercial-production-ready (`PRD.md` section 1). Check: the manuscript describes the software that was actually released, not a later unreleased build.

**M9 is done when Steps 112–116 all check out.**

---

## Notes

- Don't start a step until the one before it checks out. If a step doesn't check out, that's useful information now, not five steps from now.
- M1–M3 can overlap (e.g. start Sale while Product is still being polished), as long as each feature's own rules — local-save-first, ledgers, bilingual, the conflict policy — hold.
- M0 has more steps than it looks like it should, on purpose: Bangla infrastructure, basic login, and the real sync system are all much cheaper to build now than to retrofit after M1–M3 exist.
- M4 is the other place worth being strict: harden sync once real data exists, and don't skip any of its failure-test steps.
