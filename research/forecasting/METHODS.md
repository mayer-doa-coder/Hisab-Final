# Forecasting methods — the candidate catalogue (RQ4, Steps 85–87)

This is the list of methods the Step 88 walk-forward comparison will run, why each is on it, and what was left off. It is written **before** any comparison has been run. No method below has been scored, and nothing here claims a winner — which method is best on this project's shops is Step 88's measurement, under the metrics frozen in `docs/RESEARCH_PLAN.md`.

Code: `android/app/src/main/java/com/hisab/app/domain/forecast/`. The one list the comparison reads is `ForecastMethods.all()`, so a method added to the code is in the table automatically.

## The common interface

`fit(history)` returns a fitted model; the model's `forecast(horizon)` returns one `Quantity` per day. Fitting and forecasting are separate types so a fitted model cannot reach back into the history it came from (the usual way a walk-forward evaluation leaks the future). Every method also reports:

| Property | Meaning | RQ4 metric it feeds |
| --- | --- | --- |
| `minimumDays` | History the formula needs before its answer means anything | "not enough data" (Step 95) |
| `stateBytes` | Bytes that must be kept from one day to the next | state / model size |
| `basis` on each forecast | `ENOUGH_HISTORY`, `SHORT_HISTORY` or `NO_HISTORY` | Step 95 |

`stateBytes` counts a double as 8 bytes and a counter as 4 and ignores JVM object overhead, so it compares methods with each other and is **not** a measured heap size. Step 88 reports measured latency and measured size separately.

Forecast arithmetic uses `Double` internally and rounds to a scaled-by-1000 `Quantity` at the boundary. This does not break the "no floating point for quantity" rule: that rule protects recorded stock and money, and a forecast is never recorded (D050).

## Catalogue

Settings are starting points chosen from the literature and from what each formula means, **not tuned**. Tuning happens in Step 88, only on days before the final 28-day window (`RESEARCH_PLAN.md`, "Split").

| id | Required by PRD §15? | State | Why it is on the list |
| --- | --- | --- | --- |
| `naive` | yes | 8 B | Baseline. Fails by construction on intermittent demand, which is itself a result. |
| `moving_average(w=7)`, `(w=28)` | yes | 56 B, 224 B | Baseline, at the two window lengths a shopkeeper recognises. Window length is also the state cost. |
| `moving_average(w=all)` | no | 12 B | Running mean. O(1) state however long the history; never forgets. |
| `ewma(a=0.3)` | yes | 8 B | The method to beat in published retail results. Level starts from the first week's mean, not the first day (a zero first day would otherwise depress the level for weeks). |
| `seasonal_naive(p=7)` | yes ("where appropriate") | 56 B | Weekly shape from one observation per weekday. Brittle on purpose. |
| `seasonal_ewma(a=0.3,p=7)` | no | 56 B | One EWMA level per weekday: a weekly shape averaged over every week, instead of one. |
| `croston(a=0.1)` | yes | 20 B | Original Croston (1972). Included as the reference point. Known to be biased upward. |
| `sba(a=0.1)` | no | 20 B | Syntetos–Boylan correction, `× (1 − α/2)`. Same state and cost as Croston. |
| `tsb(a=0.1,b=0.05)` | no | 16 B | Smooths demand *probability* every day, so forecasts decay for a product that has stopped selling. Croston cannot do this. |
| `adida(ewma,m=7)` | no | 20 B | Temporal aggregation: forecast weekly totals, spread over seven days. Removes the zero days that make daily intermittent series hard. |
| `combination(ma+ewma+sba)` | no | 84 B | Equal-weight mean of three members. No weights to fit, so nothing to overfit on a short history. |

Every method fits in at most 224 bytes. All twelve together come to 580 bytes per product, about 116 KB for a 200-product shop; `ForecastMethodTest` asserts the ceiling (under 512 KB), not the exact figure. The comparison is therefore about accuracy per byte, not about whether a method fits at all.

## Research candidates added beyond the PRD's required list

The PRD requires five families (naive, moving average, EWMA, seasonal naive, a Croston-family method). Six more are added, each because it fixes one named, visible failure of a required method:

- **Running mean** — the cheapest possible state, and the limit EWMA approaches as α → 0.
- **Seasonal EWMA** — seasonal naive's weakness is one odd Friday copied into every future Friday.
- **SBA** — Croston's upward bias becomes an over-ordered shelf.
- **TSB** — Croston never notices a product has died; this is the common case of a product still on the shelf that nobody has bought in three weeks.
- **ADIDA** — daily intermittent demand is mostly zeros; the same history in weekly buckets is not.
- **Equal-weight combination** — the one entry that costs the sum of its members, so it has to earn that in Step 88.

## Deliberately left off

- **Markov-based forecasting** (optional in PRD §15). Needs a state space and a transition matrix per product, which is the one candidate that would not be nearly free on a cheap phone. Can be added later through the same interface if the comparison suggests the Croston family is leaving accuracy on the table.
- **MAPA** (multiple aggregation prediction algorithm, a multi-bucket extension of ADIDA). More state and more code than ADIDA for a gain that is only worth testing if ADIDA wins.
- **Theta, ARIMA, ETS with trend, gradient boosting, neural models.** Either need more history than a new shop has, or need a model file and per-product training. The PRD excludes neural forecasting from scope.
- **Fitted combination weights.** Needs a validation window carved out of an already short history; equal weights are the usual hard-to-beat choice at small sample sizes.

## Demand shape (Step 84)

`profileOf(history)` computes ADI (average days per demand occurrence) and CV² (squared coefficient of variation of non-zero sale sizes) and classifies a series as smooth, erratic, intermittent or lumpy using the Syntetos–Boylan–Croston cut-offs ADI = 1.32 and CV² = 0.49. It changes no forecast. It exists so Step 88 can report results **per shape**, which is what shows whether the Croston family helps exactly where it should and nowhere else, and so the dataset card can state what kind of series the pilot shops actually produce.

## Sources, and how far they were read

Honest limits first: these were found by web search during Step 84–87 development. For each, what was read is the search summary or abstract, **not the full paper**. They are the basis for *why a method is on the list*, not for any number this project reports. Before any of them is cited in the paper, the full text must be read and the claim checked against it.

| Claim used | Source | Read |
| --- | --- | --- |
| Croston splits demand into size and interval; SBA corrects its bias; TSB updates probability every period and handles obsolescence | [A new method to forecast intermittent demand in the presence of inventory obsolescence (Int. J. Production Economics)](https://www.sciencedirect.com/science/article/abs/pii/S0925527318300562) | search summary |
| Syntetos–Boylan–Croston scheme: ADI 1.32, CV² 0.49, four quadrants; SBA for the non-smooth quadrants | [Forecasting-based SKU classification](https://www.sciencedirect.com/science/article/abs/pii/S092552731100483X); [Intermittent demand forecasting: a guideline for method selection](https://www.ias.ac.in/public/Volumes/sadh/045/00/0051.pdf) | search summary |
| Retail series are mostly intermittent (73%) or lumpy (17%) at product-store level in M5; ~92.5% of teams did not beat the best benchmark; winner's edge over exponential smoothing fell from 22.4% to 3.4% at the finest level | [M5 accuracy competition: results, findings and conclusions](https://www.sciencedirect.com/science/article/pii/S0169207021001874) | search summary |
| A reduced set of exponential-smoothing / ARIMA models performs competitively in retail at much lower compute cost | [Wielding Occam's razor: fast and frugal retail forecasting](https://arxiv.org/abs/2102.13209) | abstract |
| ADIDA: aggregate, forecast, disaggregate; aggregation to lead time + review period helps inventory control | [Nikolopoulos et al. (2011), ADIDA](https://orca.cardiff.ac.uk/id/eprint/42077/1/An%20aggregate-disaggregate%20intermittent%20demand%20approach%20%28ADIDA%29%20to%20forecasting%20an%20empirical%20proposition%20and%20analysis.pdf) | search summary |

Two things the sources do **not** settle, and the project must not assume:

1. M5 is large-chain supermarket data. Whether its findings hold for Bangladeshi corner shops with weekly market days and Eid/Ramadan effects is exactly what RQ4 asks.
2. Published recommendations (e.g. "SBA over Croston by default") come from mean-squared-error comparisons on other datasets. This project's primary metrics are MAE and MASE, and stockout/overstock outcomes, so the ranking may differ.
