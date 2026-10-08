# Step 88 — latency on the reference phone

Measured 2026-10-08 on the Samsung Galaxy A15 (SM-A155M, Android 16) by `ForecastLatencyTest`. This is the phone used for development testing; it is **not** the frozen low-end reference device of `DECISIONS.md` D028 (Tecno Spark Go 2), which has not been used. Whether the low-end device is slower, and by how much, is not known from these figures.

Time for one `fit` of a 56-day history plus a 7-day `forecast`, median of 9 timed passes over 600 histories (a fixed mix: one third intermittent, two thirds steady), after every method had been run once untimed. Two complete runs are shown so the repeatability is visible.

| Method | Run 1 (µs) | Run 2 (µs) | State (bytes) |
| --- | ---: | ---: | ---: |
| `naive` | 1.86 | 1.56 | 8 |
| `moving_average(w=7)` | 5.09 | 4.39 | 56 |
| `moving_average(w=28)` | 8.20 | 8.81 | 224 |
| `ewma(a=0.3)` | 21.46 | 21.02 | 8 |
| `seasonal_naive(p=7)` | 10.79 | 10.28 | 56 |
| `croston(a=0.1)` | 19.63 | 18.26 | 20 |
| `moving_average(w=all)` | 14.48 | 9.45 | 12 |
| `seasonal_ewma(a=0.3,p=7)` | 16.59 | 11.58 | 56 |
| `sba(a=0.1)` | 22.86 | 21.75 | 20 |
| `tsb(a=0.1,b=0.05)` | 8.86 | 7.83 | 16 |
| `adida(ewma(a=0.3),m=7)` (app default) | 20.46 | 21.88 | 20 |
| `combination(moving_average+ewma+sba)` | 49.44 | 48.81 | 84 |
| `profile_select(ewma/sba)` | 33.79 | 49.43 | 52 |

A whole shop of 200 products with the app default: **4.05 ms** and **5.09 ms** in the two runs.

## How far to trust these

- Most methods repeat within about 10% between runs. Three do not: the running mean (14.5 → 9.5), seasonal EWMA (16.6 → 11.6) and the selector (33.8 → 49.4). A phone's cores change speed with load and heat, so differences of this size between methods are not meaningful; differences of several times (naive against the combination) are.
- Do not read the ordering of methods within a factor of two as a result.
- All are tens of microseconds per product. The question RQ4 asks of latency is whether a forecast is fast enough to not matter, and on this phone it is, by a wide margin, for every method.
- **A first version of this measurement was wrong and is not recorded.** It timed each method straight after a short warm-up, and the device's JIT compiler was still compiling the code every method shares while the first methods ran: a 7-day average measured five times slower than a 28-day one. The test now runs every method once, untimed, before timing any.
- The laptop figures in `latency-jvm.csv` are a different machine and a different JVM; do not compare the two columns method by method.
