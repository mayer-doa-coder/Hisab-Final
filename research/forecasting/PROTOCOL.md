# Step 88 evaluation protocol (RQ4, development comparison)

**Written before the comparison was run.** Everything below — the data, the folds, the metric definitions and the rule that picks the app's provisional default — was fixed first. Results are in `results/step88-synthetic-v1/`; they were produced by code that follows this document and by nothing else. If a result looks wrong, the fix is to the code or to a new dated version of this file, never to quietly redefine a metric afterwards.

Who fixed it, honestly: the same developer who wrote the methods. `docs/RESEARCH_PLAN.md` asks for the metric *list* to be frozen before models are compared, and that list is used unchanged (MAE, MASE, stockout outcome, overstock outcome, latency, state size). It leaves the exact stockout and overstock *definitions* open ("a defined measure"). Those two are defined here and recorded in `DECISIONS.md` D051, so they are a written choice made before seeing a result rather than one made afterwards.

## What this comparison is, and is not

It is a **development** comparison on **synthetic** series, run because no pilot-shop data exists yet. It answers two questions: do the implementations behave the way the literature says each method should, and which candidates deserve to go forward to the real-data comparison.

It is **not** evidence about which method is best in Bangladeshi shops. Each synthetic shape was designed by the same developer who wrote the methods, and some shapes match a method's own assumptions (the intermittent series are a coin flip times a size, which is exactly Croston's model). A method doing well on its own model is expected, not a finding. The question RQ4 actually asks needs the real series of `RESEARCH_PLAN.md` section 2, and the final test window is run once, on data not used here (see "Separation from the final test", below).

## Data

Source label for every series here: `synthetic`. Results are never mixed with another source.

- Generator and seed are saved in code (`SyntheticDemand`, seed `20261002`) and the generated series are saved in `research/forecasting/synthetic/v1/series.csv` with a dataset card and SHA-256 sums. The unit-test suite regenerates the series and fails if they differ from the saved file.
- 8 shapes × 100 series = 800 series, 56 days each (the real-series minimum in the plan).
- Quantities are whole units; the pipeline's scaled-by-1000 `Quantity` is used for everything fed to the methods.

| Shape | Construction |
| --- | --- |
| `steady` | Poisson, mean uniform 2–15 |
| `trending` | Poisson with a mean changing linearly by a daily rate of −1.2% to +2.0% of its starting level |
| `weekly` | Poisson, mean uniform 3–12, times a fixed weekday profile (0.7 0.8 0.9 1.0 1.1 1.6 0.9, mean 1) started at a random weekday |
| `intermittent` | each day a sale with probability 0.08–0.35; size 1 + Poisson(0.5–3) |
| `lumpy` | sale probability 0.08–0.30; size 1 + Poisson(1) with probability 0.7, else 5 + Poisson(10) |
| `festival_spike` | steady, then three times the mean on days 38–44 — inside fold 2's forecast window, so no method can have seen it coming, and inside fold 3's training window, which tests how fast each recovers |
| `ramp_up` | a new product: mean rises linearly from 15% to 100% of its final level over the first 28 days |
| `stopped_selling` | steady until a day chosen 25–40, then nothing, with the product still listed — the case the demand pipeline cannot trim, and the one TSB exists for |

## Walk-forward folds

Exactly the three in `docs/PHASE_GUIDE.md` Step 88, always in date order, each method fitted on the training days alone:

| Fold | Fit on | Predict |
| --- | --- | --- |
| 1 | days 1–30 | days 31–37 |
| 2 | days 1–37 | days 38–44 |
| 3 | days 1–44 | days 45–51 |

Days 52–56 are not used. Each fold forecasts 7 days (the horizon is also the default lead time of the reorder engine, Step 89). **No method setting is tuned** in this comparison: every method runs at the defaults written into `ForecastMethods`, which were chosen before any result existed (`METHODS.md`). `RESEARCH_PLAN.md` allows tuning only on days before the final test window, and that belongs to Step 105.

## Metrics (frozen list; definitions fixed here)

Notation, per series and fold: `y₁…y₇` actual daily demand, `f₁…f₇` forecast, training window `y₁…y_T`.

- **MAE** (primary): mean over the 7 days of `|f − y|`, in units per day. Reported as the mean over all series and folds in a shape.
- **MASE**: `MAE_fold / scale`, where `scale` is the mean of `|y_t − y_{t−1}|` over the training window (the in-sample one-step naive error). It is **undefined when scale is 0** (a perfectly constant or all-zero training window); those folds are left out of the MASE mean and **counted** in a separate column. The MASE of a shape is the mean over its defined folds.
- **Stockout outcome**: suppose the shop holds exactly the forecast quantity for the horizon, `F = Σf`, against real demand `A = Σy`. Units short are `max(0, A − F)`. Reported two ways: *how often* — the share of folds with `A > F`; and *how badly* — total units short as a percentage of total real demand.
- **Overstock outcome**: units left over are `max(0, F − A)`. Reported the same two ways: the share of folds with `F > A`, and total units left as a percentage of total real demand.
- **Latency**: wall-clock time of one `fit` plus a 7-day `forecast`, on the 56-day-history series. Measured twice: on the development laptop's JVM (indicative only), and on the reference phone, the Galaxy A15 (`ForecastLatencyTest`). Median of repeated passes after warm-up.
- **State size**: `stateBytes`, the bytes a method must carry between days, as declared by each method. It is a model of footprint, not a measured heap size, and says so wherever it appears.

Also printed, clearly labelled **supplementary** and never used to rank: signed bias (mean `F − A` per day). It explains whether a method's error is over- or under-forecasting.

Not applicable here: error "with and without stockout days". That needs a stock ledger, and synthetic series have none. `markStockOutDays` is built and tested and will be used on real series.

## How methods are compared

1. Per-shape tables of every metric for every method. No method is dropped.
2. **Mean rank**: within each shape, methods are ranked by MAE (1 = lowest); a method's score is its mean rank over the 8 shapes, each shape counting equally so that high-volume shapes do not dominate. The same is computed for MASE.
3. **Trade-off view** (PRD section 30: trade-offs, not sophistication): a method is *Pareto-efficient* if no other method has both a better-or-equal mean MAE rank and a smaller-or-equal state size, with at least one strictly better.

## The rule that picks the app's provisional default

Steps 89–90 need one method inside the app. The rule, fixed now:

> The provisional default is the method with the lowest mean MAE rank over the 8 shapes. A method within 0.25 rank points of the best is treated as tied, and ties go to the smaller state size.

It is **provisional**: it is the best of the candidates on development data this developer constructed, and it is replaced if the real-data comparison disagrees. A unit test reads the saved results and fails if `ForecastMethods.appDefault()` no longer names the method this rule picks, so the default in the app cannot drift from the evidence without someone deciding to move it.

## Separation from the final test

The final test of `RESEARCH_PLAN.md` ("last 28 days of each series, fixed at Step 105, run once") must not reuse these series. Days 29–56 of every series here overlap that window, so these series are a **development set** and are never the final test: the final run uses real series, plus a fresh synthetic seed that has not been looked at. Selecting among methods on these series and then reporting the winner's score on the same series would be exactly what the plan's general rules forbid.
