# Dataset card — synthetic forecasting series, development set, v1

Format follows `docs/RESEARCH_PLAN.md` section 6. **Frozen: never edited.** A fix creates `v2` with a note on what changed.

| | |
| --- | --- |
| Source label | `synthetic` (no real shop, no real customer, no person) |
| Files | `series.csv`, `SHA256SUMS` |
| Generated | 2026-10-02 |
| Generator | `SyntheticDemand` in `android/app/src/test/java/com/hisab/app/domain/forecast/research/SyntheticDemand.kt`; seed `20261002` |
| Rebuild | Exact. The unit-test suite (`Step88EvaluationTest`) regenerates the series from the seed and fails if they differ from `series.csv` |
| Series | 800 = 8 shapes × 100 |
| Length | 56 days each, the real-series minimum in the plan |
| Values | Whole units sold per day; no prices, no customers, no baki, no dates (day 1 to day 56) |
| Ethics / consent | Not applicable: no human subjects and no personal data |
| License | To be chosen at Step 114 and written here in the version that is published |

## Columns

`id` (`shape-NNN`), `shape`, then `d1` … `d56`.

## Shapes (100 series each)

`steady`, `trending`, `weekly`, `intermittent`, `lumpy`, `festival_spike`, `ramp_up`, `stopped_selling`. Exact construction is in `research/forecasting/PROTOCOL.md`.

## Intended use

The Step 88 walk-forward comparison, and nothing else. It is a **development** set: it was used to compare candidate methods and to apply the rule that picks the app's provisional default. It must **not** be the final test.

## Known gaps — read before using it

- **Written by the people who wrote the methods.** The shapes encode the developer's idea of what retail demand looks like. Some are a method's own model (an `intermittent` day is a coin flip times a size, which is Croston's assumption), so that method doing well on them is expected and shows nothing about real shops.
- **No stock ledger**, so no `stock_out` mark. `RESEARCH_PLAN.md` asks for error with and without stockout days; that cannot be done here.
- **One festival.** `festival_spike` has a single three-times week on days 38–44. Real Eid and Ramadan effects recur, vary by product and can be learned from earlier years; none of that is here.
- **One weekly profile**, rotated to a random start day. Real weekday shapes differ by shop and product.
- **No missing days, no data errors, no product launches mid-window** beyond `ramp_up`.
- **Days 52–56 are not used** by the three folds of the protocol.
- **Overlaps the plan's final test window.** Days 29–56 of every series fall in the "last 28 days" window the plan reserves for the single final run (Step 105). So this set can never be that run's data, and the final comparison must use real series and a fresh seed.
