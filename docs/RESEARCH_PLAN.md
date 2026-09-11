# Research Plan

How each research question gets tested. Full detail: `PRD.md`, sections 5 and 27–32.

## RQ1 — Offline Reliability
Test: run the app fully offline, with bad internet, with the app killed mid-sync, and with two devices syncing at once.
Measure: transactions lost, duplicate transactions, whether all devices end up with the same data, how long that takes.
Target: 0 lost confirmed transactions.

## RQ2 — Resource Efficiency
Test: run the app on a low-end phone, a mid-range phone, and a modern phone.
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

Settled before M5/M6 start (not figured out along the way — see `PHASE_GUIDE.md` M0):

**Language dataset:**
- Source: synthetic retail phrases plus phrases contributed by people who are not the developer building the normalizer/intent rules.
- Annotation: each example gets text, language_type, intent, entities (see `PHASE_GUIDE.md` M5).
- Split: a development set (used while building) and a held-out set (frozen, used only for the final RQ3 evaluation). The held-out set must be authored independently, not written by the rule author after seeing how the system behaves.
- Who writes examples: at least one contributor other than the developer building the language normalizer, specifically so the held-out set isn't shaped by the same intuitions as the rules.
- PII: no real customer names, phone numbers, or shop-identifying details in anything committed to the repository — fictional or anonymized values only.

**Forecasting dataset:**
- Source: real anonymized sale history once M1–M3 produce it (from pilot shops, with consent — see below), supplemented with synthetic demand series for edge cases real data may not yet cover (e.g. deliberately intermittent demand).
- Every series is labeled real or synthetic — never blended without that label.

**Ethics and consent:**
- Any dataset built from a real shop's data requires that shop owner's consent, collected through a documented process, before the data is used for research (separate from ordinary product use).
- Anything published or shared outside the shop's own device is anonymized or synthetic — see `SECURITY.md` and `PRD.md` section 19.5.

**Retention and versioning:**
- Dataset files live under `/research` (see `PHASE_GUIDE.md` M9) and are versioned like code. A dataset used for a reported result is tagged/frozen at that point — not silently edited afterward.
- A change to a frozen dataset creates a new version; old results are never re-labeled as coming from the new version.

## General Rules
- Never pick a final method by testing it repeatedly against the same held-out data — decide the test set first, then run it once for the final report.
- All test data is synthetic or anonymized unless real users gave consent through a proper ethics process.
- Every result must come from code and data saved in the repository under `/research` (see `PHASE_GUIDE.md`, M9) so someone else can re-run it and get the same numbers.
