# Step 83 — accuracy and latency on the development set

Measured on 2026-09-28 on the reference phone (Samsung Galaxy A15, Android
16), not on a laptop: latency on the hardware a shopkeeper actually owns is
the number that matters. Produced by
`DevSetMeasurementTest.measureAccuracyAndLatencyOnTheDevelopmentSet`, which
reads the real `dev-set.jsonl` shipped as a test asset, so these numbers
cannot drift away from the data set they claim to describe.

| Measure | Result |
| --- | --- |
| Examples | 110 |
| Intent accuracy | **100.0%** (110/110) |
| Product matching | 100.0% (40/40) |
| Customer matching | 100.0% (32/32) |
| Period extraction | 100.0% (17/17) |
| Amount extraction | no examples in the set (0 annotated) |
| Latency, median | **1.17 ms** |
| Latency, p95 | 2.07 ms |
| Latency, worst | 3.51 ms |
| Storage — rule source | 31 KB across four files |
| Storage — one shop's index | 35 indexed phrases for 11 products and customers |

## Read the accuracy number with the caution it deserves

**100% on the development set does not mean the rules are 100% accurate.**
The rules were written while looking at this data. The set is what the
keyword lists, the Banglish folds and the ending-stripping were tuned
against, so scoring well on it is close to circular — it says the rules do
what they were built to do, not that they will hold up on a sentence nobody
anticipated.

The number that will actually mean something is the one from Step 108,
against the held-out set that a different person writes without seeing any
of this. That set does not exist yet, on purpose (Step 76). Until then this
figure is a regression guard — it tells us if a change makes things worse —
and nothing stronger.

## Amount extraction

Reported honestly as having nothing to measure. Every question in the
development set asks *for* a number ("how much sugar", "how much does Rahim
owe"); none of them states one ("Rahim paid 500"). Money and quantity
extraction therefore has zero examples here, and a percentage over zero
cases would be an invented figure. Whoever writes the held-out set is not
steered away from such sentences, so the gap may close there.

## What measuring actually caught

Running this, rather than assuming, found two real bugs — both of which had
been passing every hand-written test:

1. **`overdue` never matched its own keyword.** The Banglish fold `v → b` is
   right for Bangla ("vai" is "bhai") but wrong for the English words people
   mix in: it turned "overdue" into "oberdue". Keywords are now looked for in
   both the folded and the unfolded spelling.
2. **"aj koto taka ashlo"** — *how much money came in today* — was understood
   as nothing at all, because it is a sales question containing no word for
   selling. "aslo" and its Bangla equivalents are now sales words.

A third thing surfaced from the latency column rather than the accuracy one:
the first version re-derived every word's grammatical endings once per intent
being tested, and the worst question took **24 ms**. Working the forms out
once per question instead brought the median from 2.2 ms to **1.17 ms** while
doing strictly more work than before.

## How to reproduce

```bash
cd android
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.hisab.app.domain.language.DevSetMeasurementTest
adb logcat -d | grep "STEP83|"
```

The test also asserts floors — 90% intent, 90% product and customer
matching, and no question slower than 20 ms — so a later change that makes
the rules worse fails the suite instead of quietly lowering these numbers.
