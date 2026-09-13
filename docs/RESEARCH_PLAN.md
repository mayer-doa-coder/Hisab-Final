# Research Plan

How each research question gets tested. Full detail: `PRD.md`, sections 5 and 27–32.

## RQ1 — Offline Reliability
Test: run the app fully offline, with bad internet, with the app killed mid-sync, and with two devices syncing at once.
Measure: transactions lost, duplicate transactions, whether all devices end up with the same data, how long that takes.
Target: 0 lost confirmed transactions.

## RQ2 — Resource Efficiency
Test: run the app on a low-end phone, a mid-range phone, and a modern phone. The low-end phone is frozen: Tecno Spark Go 2, 3 GB RAM + 64 GB eMMC, Unisoc T7250, Android 15 (`../DECISIONS.md` D028). Separately, confirm the app runs on Android 8.0 (API 26), the frozen minSdk.
Measure: app size, install size, startup time, RAM used, how fast a sale saves, how fast a database query runs, how fast a suggestion or forecast is produced, how much data sync uses.

## RQ3 — Bangla/Banglish Interaction
Test: run a fixed set of Bangla, Romanized Bangla, and mixed-language retail questions that were never used to build the feature.
Measure: whether the intent was understood correctly, whether the right numbers/products/customers were picked out, how long it took.
Report Bangla, Romanized Bangla, and mixed input separately.

**Development set vs. held-out set are different data, written independently.** The development set is what you look at while building the normalizer and intent rules. The held-out set is frozen before final evaluation and must be authored by someone who did not see the rule implementation — not written after the fact by the same person who built the rules, and not reused from the development set. Otherwise the system ends up evaluated on the exact patterns that inspired its own rules, which overstates accuracy. See the Research Data Plan below.

## RQ4 — Lightweight Inventory Intelligence (Forecasting)
Test: use walk-forward evaluation — train on past days, predict the next few days, then move forward and repeat. Never use future data to predict the past.
Compare: naive, moving average, EWMA, seasonal naive, Croston, and (optional) Markov methods.

**Metrics (frozen before experiments run, not chosen after seeing results):**
- MAE (mean absolute error) — primary error metric.
- MASE (mean absolute scaled error) or another scale-normalized metric — needed alongside MAE because product sales volumes differ a lot between products.
- Be careful with percentage-based metrics like MAPE: many products have intermittent, sometimes-zero daily demand, and MAPE is undefined or misleading on zero-demand days. Don't use it as a primary metric here.
- Stockout outcome: a defined measure of how often/how badly under-forecasting would have caused a stockout.
- Overstock outcome: a defined measure of how often/how badly over-forecasting would have caused overstock.
- Latency: time to produce a forecast.
- State/model size: memory or storage the method needs.

The point isn't which metric sounds most sophisticated — it's that the metric list above is fixed before models are compared, so the "winning" method isn't chosen by picking whichever metric happens to favor it.

## RQ5 — Complementary Item Suggestion
Test: hide a slice of recent sales, then check whether the suggestion method would have correctly suggested the items that were actually bought together.
Compare: plain frequency ranking vs. association-rule scoring vs. a time-decayed version.
Measure: precision/recall on the hidden sales, how often a shopkeeper accepts a suggestion, how fast a suggestion is produced.

## Research Data Plan

Settled in M0 (`PHASE_GUIDE.md` Step 19), before M5/M6 need it. Changing any rule below needs a new entry in `../DECISIONS.md` — not a quiet edit.

### Who is allowed to write the held-out test sentences?
Only a person who meets all three:
1. **Not the rule developer** — not the person who builds the normalizer and intent rules (Steps 73–74).
2. **Hasn't seen the system** — not the rule code, not the development set, and not any of the system's answers.
3. **Knows shop talk** — a native Bangla speaker who knows how shop talk sounds, such as a shopkeeper, a shop assistant, or a volunteer.

The rule developer may not write, edit, choose, or read held-out sentences before the final language experiment (Step 108).

### 1. Language dataset (RQ3)

**Where the sentences come from**
- Development set (Step 75) — anything goes:
  - phrases generated from templates (the generator script is saved in `research/language/`);
  - phrases the developer writes;
  - phrases other people write.
- Held-out set (Step 76):
  - only sentences from the held-out writers described above;
  - no template-generated phrases, because templates repeat the same patterns the rules were built from;
  - nothing copied from social media or real chat logs.

**How held-out writers work**
- They get a list of situations in Bangla, not example sentences. For example: "ask how much sugar is left", "ask how much Rahim owes", "ask today's total sales".
- The situation list covers every Ask Hisab intent (Steps 78–82 and 90). The developer may write the situation list, because it describes what to ask, not how to phrase it.
- Writers type the way they would on a phone — Bangla script, Romanized Bangla, or a mix — and each writer uses all three.

**Minimum size of the held-out set**
- At least 300 sentences.
- At least 100 each of Bangla script, Romanized, and mixed.
- At least 20 per intent.
- At least 2 different writers.
- Report how many writers took part.

**What each example records**

| Field | Meaning |
| --- | --- |
| `id` | Unique ID |
| `text` | The sentence exactly as written |
| `language_type` | `bangla`, `romanized`, or `mixed` — set by a fixed rule, not judgment: only Bengali script letters = `bangla`; only Latin letters = `romanized`; both = `mixed`. Digits don't count. |
| `intent` | One value from the fixed intent list |
| `entities` | Each product, customer, quantity, money amount, and date/period mentioned — the exact words from the sentence and the normalized value |
| `writer` | A code such as `W1` — never a name |
| `set` | `dev` or `heldout` |

**Labeling**
- Held-out intents and entities are labeled by someone other than the rule developer.
- A second person independently labels a random 20% of the held-out set. Report agreement as percent agreement, plus Cohen's kappa for intent.
- Disagreements are settled by discussion, and the decision is written down.

**Keeping the two sets apart**
- The development and held-out sets are separate files.
- Before freezing, remove any held-out sentence that matches a development sentence after simple cleanup (lowercase, extra spaces removed, Bangla digits turned into 0–9).
- There is no training split, because the rules aren't trained. If a learned method is ever added, it may train only on the development set.

**Freezing (Step 77)**
1. The held-out file stays with a writer or labeler — not the rule developer.
2. Only its SHA-256 hash is committed at Step 77.
3. At Step 108 the file is added to `research/language/heldout/v1/`, and its hash must match the committed one before any result counts.

**No personal data**
- Customer names come from a fixed list of made-up names.
- Phone numbers are placeholders, never real ones.
- No real shop names, and no addresses.
- Before any language file is committed, a search for Bangladeshi mobile numbers (`01` followed by 9 digits) must find nothing.

### 2. Forecasting dataset (RQ4)

**Sources, in order of priority**
1. **Real** — daily sales from pilot shops that gave consent (section 5), exported from the app once M1–M3 exist. Step 84's pipeline turns them into `date, product_id, quantity_sold`.
2. **Synthetic** — series generated with known shapes that real data may not cover yet: steady, trending, weekly pattern, intermittent (many zero days), an Eid/Ramadan spike, a new product, a discontinued product. The generator script and its random seed are saved, so the exact same series can be rebuilt.
3. **Public** — optional, and only if its license allows research use and sharing. The dataset card records name, link, version, and license.

**Labels and reporting**
- Every series is labeled `real`, `synthetic`, or `public`.
- Results are always shown separately for each source. A combined number is never shown on its own.

**Anonymizing real data**
- Shop → a random code (`S01`).
- Product name → a product code plus a general category (for example, "soft drink").
- No customer data, no baki data, and no prices — quantities only.
- Dates are kept, because weekly and Eid patterns need them. Shop location is not recorded.

**Stockout days**
- A day when the product's stock reached zero is marked `stock_out = true`, because zero sales that day doesn't mean zero demand.
- Forecast errors are reported both with and without these days.
- Stock is known from stock movements, so this mark is calculated, not typed by hand.

**Which series count**
- A real series needs at least 56 days (8 weeks) of history.
- Shorter series are reported as "not enough data" (Step 95), not dropped silently.

**Split**
- Walk-forward only, always in date order.
- The final test window is the last 28 days of each series, fixed at Step 105.
- Method settings (moving-average window, EWMA smoothing) are tuned only on days before that window.

### 3. Suggestion dataset (RQ5)
- Real sale baskets come from the same consenting shops, anonymized the same way.
- Split by time: the most recent 20% of sales is the hidden test slice, fixed at Step 105.
- Synthetic baskets may be used for testing, labeled `synthetic`.

### 4. Pilot device record (RQ2)
- For every phone used in the pilot, record: model, Android version, RAM, and storage. Nothing that identifies the owner — no name, phone number, or IMEI.
- Saved in `research/performance/device-research/`.
- Reported as a table next to the reference device (`../DECISIONS.md` D028), to show how real pilot phones compare. It is not used to change minSdk.

### 5. Consent and ethics
- **Ethics review:** before collecting any real shop data, check whether the university's ethics review process applies, and get approval if it does. Record the approval reference in the dataset card.
- **Consent form:** written in plain Bangla, with an English copy. It says:
  - what is collected — sale quantities, dates, products as codes, phone model;
  - what is not — customer names, phone numbers, baki;
  - why — research, published only in anonymized form;
  - that the shop can withdraw at any time.
- **Separate choice:** consent to research is separate from using Hisab. A shop can use the app without joining the research.
- **Withdrawal:** the shop's data is removed from every dataset that isn't frozen yet. A version already frozen and published can't be recalled, and the form says this before signing.
- **Consent records:** the signed form or recorded verbal consent is kept privately, outside the repository. The repository holds only the shop code and consent date.
- **Language writers:** they agree to their sentences being published, and are credited by writer code — or by name, only if they ask.

### 6. Storage, retention, and versioning

**Folder layout**

```text
research/
  language/dev/v1/  language/heldout/v1/
  forecasting/real/v1/  forecasting/synthetic/v1/
  suggestions/real/v1/
  performance/device-research/
```

**What every dataset version folder contains**
- The data, as UTF-8 CSV or JSONL.
- `DATASET_CARD.md`: source, collection dates, writers or shops by code, counts (per language type and intent, or per series), ethics/consent reference, license, known gaps.
- `SHA256SUMS`.

**Rules**
- A frozen version is never edited. A fix creates `v2`, with a note on what changed. Old results keep pointing at the version they used.
- Every reported result names the dataset, its version, and its hash.
- Raw real exports (before anonymization) never enter the repository. They stay encrypted on the researcher's own computer and are deleted 12 months after the v1.0.0 research release (Step 115). Only anonymized versions are kept long term.
- The license for published datasets is chosen at Step 114 and written in each dataset card.

## General Rules
- Never pick a final method by testing it repeatedly against the same held-out data — decide the test set first, then run it once for the final report.
- All test data is synthetic or anonymized unless real users gave consent through a proper ethics process.
- Every result must come from code and data saved in the repository under `/research` (see `PHASE_GUIDE.md`, M9) so someone else can re-run it and get the same numbers.
