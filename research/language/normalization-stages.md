# Step 73 — the pipeline, stage by stage

Captured on 2026-09-28 by running the real pipeline
(`com.hisab.app.domain.language.TextNormalizer.normalize`) over the example
sentences in `docs/PHASE_GUIDE.md` Step 75, plus one that exercises Bangla
digits, upper case and punctuation together.

This file is a record for reading. The same values are asserted in
`TextNormalizerTest`, so if the pipeline changes, the tests fail — this page
does not silently go stale on its own without something else going red.

```text
input      | rahim er baki koto
unicode    | rahim er baki koto
lowercased | rahim er baki koto
digits     | rahim er baki koto
tokens     | [rahim, er, baki, koto]
romanized  | [rahim, er, baki, koto]

input      | রহিমের baki কত
unicode    | রহিমের baki কত
lowercased | রহিমের baki কত
digits     | রহিমের baki কত
tokens     | [রহিমের, baki, কত]
romanized  | [রহিমের, baki, কত]

input      | coke koyta ase
unicode    | coke koyta ase
lowercased | coke koyta ase
digits     | coke koyta ase
tokens     | [coke, koyta, ase]
romanized  | [coke, koyta, ache]

input      | চিনি stock কত
unicode    | চিনি stock কত
lowercased | চিনি stock কত
digits     | চিনি stock কত
tokens     | [চিনি, stock, কত]
romanized  | [চিনি, stock, কত]

input      | ajke koto sell hoise
unicode    | ajke koto sell hoise
lowercased | ajke koto sell hoise
digits     | ajke koto sell hoise
tokens     | [ajke, koto, sell, hoise]
romanized  | [aj, koto, bikri, hoyeche]

input      | COKE 500ML stock ৩৫০ ache?
unicode    | COKE 500ML stock ৩৫০ ache?
lowercased | coke 500ml stock ৩৫০ ache?
digits     | coke 500ml stock 350 ache?
tokens     | [coke, 500ml, stock, 350, ache]
romanized  | [coke, 500ml, stock, 350, ache]
```

## What each stage is doing, and the two bugs this inspection caught

- **unicode** — NFC. Bangla arrives composed or decomposed depending on the
  keyboard; without this, the same word typed on two phones is not equal.
- **lowercased** — root locale, not the device's. A Turkish phone lowercases
  "I" to a dotless "ı", which would stop "STOCK" matching "stock".
- **digits** — ৩৫০ becomes 350, reusing the same `normalizeDigits` the money
  parser uses rather than a second copy.
- **tokens** — punctuation splits words; a decimal point inside a number does
  not, so "১২.৫০" stays one amount.
- **romanized** — Latin-script words only: `ase → ache`, `ajke → aj`,
  `sell → bikri`, `hoise → hoyeche`. Bangla script is passed through
  untouched, which is why রহিমের and চিনি appear unchanged above.

Two real bugs were found by looking at this output rather than assuming it:

1. **Bangla vowel signs were being thrown away.** A vowel sign such as ি is a
   combining mark, not a letter, so `isLetterOrDigit` said false and the
   tokenizer treated it as punctuation: চিনি came out as `[চ, ন]`. Nearly
   every Bangla word would have been mangled. Marks, the hasant that builds
   conjuncts, and the zero-width joiners are now all part of the word.
2. **`kotoo` folded to `kotu`.** "oo" is usually a real Banglish sound
   (khoob → khub), so it is read as a sound before doubled letters are
   collapsed. That is the right general rule, so the common words it would
   mis-handle carry their own entries in the spelling table instead.
