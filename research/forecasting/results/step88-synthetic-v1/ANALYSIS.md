# Step 88 — reading the results

Written by hand after the run, against `results.csv` with SHA-256 starting `282b69656cbf`. If `results.csv` is regenerated, check this file against the new numbers before trusting it; the tables in `REPORT.md` are the source, this is commentary.

Everything here is on **synthetic series designed by the developer who wrote the methods** (`PROTOCOL.md`). It describes how the implementations behave. It does not say which method is best in a real shop.

## The headline, and why it is weaker than it looks

By the frozen primary metric (mean MAE rank over the eight shapes), the top of the table is `moving_average(w=28)` (4.75) and `adida(ewma(a=0.3),m=7)` (5.00). The protocol's rule gives the app default as **`adida(ewma(a=0.3),m=7)`**, because it is within 0.25 rank points of the best and needs 20 bytes against 224.

Repeating the whole comparison on ten seeds (`seed-stability.csv`) shows these two are **tied**: mean rank 5.03 and 5.04, spread (SD) 0.29 and 0.42. The rule picked ADIDA on 7 of 10 seeds and a different method on the other 3. A gap in mean rank smaller than the SD is not a difference this data can show. `combination` (5.55), `moving_average(w=7)` (5.59) and the running mean (5.60) are about half a rank point behind the leaders, which is under two SDs. `tsb` (6.09) and `ewma` (6.39) are one to 1.4 points behind, which is more than the spread, so those two are probably worse on this data, though a different generator could change that. What the data does support: the bottom of the table is stable (naive and seasonal naive are the last two by mean rank over the ten seeds, 10.5 and 10.8, SD about 0.5), and the leaders are not separated from one another.

## No method wins everywhere

| Shape | Lowest MAE | Fewest units short | Fewest units left over |
| --- | --- | --- | --- |
| steady | adida 2.22 | moving average 28 (5.7%) | SBA (3.6%) |
| trending | Croston 2.30 | moving average 7 (9.3%) | SBA (3.5%) |
| weekly | seasonal EWMA 2.41 | running mean (5.4%) | SBA (4.9%) |
| intermittent | moving average 7 (0.887) | Croston (25.8%) | adida (37.4%) |
| lumpy | running mean 1.58 | Croston (50.0%) | running mean (55.5%) |
| festival_spike | running mean 5.96 | Croston (41.2%) | running mean (7.4%) |
| ramp_up | combination 2.40 | moving average 7 (7.4%) | running mean (0.1%) |
| stopped_selling | naive 1.43 | moving average 28 (4.6%) | naive (113%) |

(Units short / left over are shares of real demand over the 7-day windows.)

## Findings

**1. MAE and stockout disagree on intermittent demand, and the protocol froze MAE as primary.** On `intermittent`, Croston has the *highest* MAE of the useful methods (0.999 against 0.887 for the 7-day average and 0.895 for EWMA) yet leaves far less demand unmet (25.8% short against 39.7% and 43.9%). This is the known weakness of MAE on series that are mostly zero: it is minimised by forecasting close to the median, which is zero, so it rewards under-forecasting. Croston and SBA estimate the *mean*, which is what a reorder needs and what MAE punishes. The price shows in the other column: Croston leaves 56.5% of demand over as stock, the 7-day average 43.2%. Which of these matters more is a shop's choice between running out and tying up money, not something the data decides. For the paper, the honest statement is that the mean-MAE-rank winner and the stockout winner differ, and both must be reported.

**2. The app default is weak in two places the same table shows.**
- `stopped_selling`: ADIDA's MAE is 4.69 against EWMA's 1.99, and it leaves 451% of real demand as stock. A product that stopped selling two weeks ago keeps being recommended. Every method with a long memory does this (Croston 5.59, SBA 5.33, running mean 4.72).
- `ramp_up`: ADIDA lags a product whose sales are still climbing (MAE 3.11 against 2.46, and 30.6% of demand short against EWMA's 8.0%). A new product is the case where history is shortest and the cost of lagging is a stockout.

**3. TSB helps with stopped products, but only partly at its untuned setting.** On `stopped_selling` TSB (MAE 4.20) beats Croston (5.59) and SBA (5.33), as it should, but not the short-memory methods (EWMA 1.99, 7-day average 2.21). Its decay rate (β = 0.05) is a literature-style default that was not tuned, and the windows here are short, so the probability has only a few weeks to fall.

**4. Croston against SBA behaves as the literature says.** SBA's bias correction reduces over-forecasting on intermittent series (bias +0.14 a day against Croston's +0.18) but costs accuracy on smooth ones, where it under-forecasts (bias −0.42 a day on `steady`). That is the reason a per-product selector is worth trying.

**5. The selector does not earn its place at its fixed mapping.** `profile_select(ewma/sba)` ranks 8.38 (8.21 over ten seeds), worse than both of its parts. It is not a bug: on `steady`, `trending`, `weekly` and `festival_spike` its numbers equal EWMA's to four decimals, and on `intermittent` and `lumpy` they equal SBA's, so it chooses as designed. On `ramp_up` it is almost EWMA's (MAE 2.465 against 2.461): a few young products look intermittent in their first weeks and are handed to SBA. On `stopped_selling` it is a mixture (3.57, between EWMA's 1.99 and SBA's 5.33). It loses for two reasons visible above: MAE rates SBA below EWMA on intermittent series (finding 1), and a product that has stopped selling looks "intermittent" to the average-interval test, so it is handed to SBA, the method that handles it worst. Changing the mapping now (for example to TSB in the intermittent branch) would be choosing on the data that exposed the problem; it is recorded as a candidate for the next round on a fresh seed.

**6. The simple seasonal method is poor.** Seasonal naive ties naive as the worst on `steady` (MAE 3.08 each) and is last overall by mean rank: one observation per weekday is mostly noise, as expected. Seasonal EWMA has the lowest MAE on `weekly` (2.41) and ranks 7.6 overall, so a weekly shape helps when there is one and costs when there is not.

**7. An unforeseen festival week hurts every method equally in the window it falls in.** On `festival_spike` the spike week falls in fold 2, and every method is about 66% short of real demand in that fold (66.3% EWMA, 66.8% ADIDA, 66.4% Croston, 66.7% running mean), because no method can see the spike coming from history. In folds 1 and 3 they are not short at all, so the pooled 41% shortfall is entirely the spike week. The methods differ in the fold after it, which trains on the spike: EWMA, with its fast memory, forecasts 2.8 times what sold (181% over), Croston 2.0 times, and ADIDA and the running mean 1.3 times (33% and 32% over). That is why EWMA's pooled MAE is 9.04 against 5.98 and 5.96. Per-fold figures were computed with the independent check (`independent_check.py`'s methods), not read from `results.csv`, which pools folds. A real Eid effect is recurring and could be modelled from last year's data, which a 56-day series cannot hold.

**8. Cost is not what separates the methods.** Every method needs at most 224 bytes of state. On the laptop JVM each takes about 0.4 to 3.2 microseconds per product (`latency-jvm.csv`, which a stopwatch rewrites on every regeneration, so it varies); on the Galaxy A15 each takes about 1.6 to 50 microseconds (`latency-phone.md`, two runs), and a 200-product shop is forecast with the default method in about 4 to 5 ms. The default needs 20 bytes. Accuracy per byte, not speed, is what separates the methods at this scale. These are not the frozen low-end reference device (Tecno Spark Go 2), which has not been used.

## What these results cannot show

- **Anything about real shops.** The shapes were chosen by the person who built the methods, and some are a method's own model (the intermittent series are a coin flip times a size, which is Croston's assumption). A method doing well on its own model is not a finding.
- **A real ranking.** The leaders are tied within the seed spread, and the spread itself comes from one generator.
- **The effect of tuning.** No setting was tuned. Smoothing constants, window lengths and TSB's decay all matter and are Step 105's job, on days before the final test window.
- **Error without stockout days.** Synthetic series have no stock ledger.
- **Horizons other than 7 days**, seasons other than weekly, or a Ramadan or Eid pattern.
- **Anything on the final test.** These series are a development set; the final run needs real data or a fresh seed.

## What to do with this

1. Keep `adida(ewma(a=0.3),m=7)` as the provisional app default, as the rule says, and keep its two weaknesses visible (README of this folder, D051).
2. Do not read the default as the answer to RQ4. It is the answer to "which candidate does the pre-written rule pick on development data".
3. Before the real-data comparison, decide whether MAE or the stockout outcome is the primary metric for the paper — the data shows they pick different methods on the shapes that matter most to a shop — and write that decision down before running.
4. Add candidates that target the two weaknesses (a selector that uses TSB for the intermittent branch; a faster TSB decay) and evaluate them on a fresh seed, not this one.
