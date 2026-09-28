# Held-out test set (Steps 76 and 77) — not written yet, on purpose

**This folder deliberately contains no sentences.** What is here is
everything *around* the held-out set: who may write it, what to write about
(`SITUATIONS.md`), the shape each row takes, and the freeze.

## Why the rule developer did not write it

`docs/RESEARCH_PLAN.md` allows only a person who meets all three:

1. **Not the rule developer** — not whoever built the normalizer and intent
   rules in Steps 73–74.
2. **Hasn't seen the system** — not the rule code, not the development set,
   not any of the system's answers.
3. **Knows shop talk** — a native Bangla speaker who knows how a shop
   actually sounds.

Steps 73 and 74 were built by Claude in this repository, so Claude is
disqualified by rule 1 and cannot write, edit, choose or read these
sentences before Step 108. Generating them anyway would have produced a file
that looks like a test set and silently isn't one: the rules would be
measured against the same patterns that inspired them, and the Step 108
accuracy number would be inflated by an unknown amount with nothing in the
repository showing why. That is the one failure mode freezing exists to
prevent, so the file is missing by design rather than quietly filled in.

**Templates are not allowed here either**, however tempting — they repeat the
patterns the rules were written against. `generate-dev-set.mjs` is for the
development set only.

## Who can write it, in practice

- **The project owner may write it, but only if they have not read
  `research/language/dev/dev-set.jsonl`, the `domain/language/` code, or any
  Ask Hisab output.** Reading any of those first disqualifies them under
  rule 2. If that boat has sailed, they can still recruit and coordinate
  writers — just not author sentences.
- At least **two** writers are needed regardless, so one person is never
  enough on their own.
- Writers are identified by a code (`W1`, `W2`), never a name.

## What a writer is given

Only `SITUATIONS.md` — eight situations described in Bangla, with no example
sentences anywhere in it. Writers must not be shown the development set, the
app's answers, or this project's code.

## The shape of each row

One JSON object per line (JSONL), the same fields the development set uses:

```json
{
  "id": "held-0001",
  "text": "<exactly as the writer typed it>",
  "language_type": "bangla | romanized | mixed",
  "intent": "GET_STOCK | GET_CUSTOMER_BAKI | GET_OVERDUE | GET_TODAY_SALES | GET_PERIOD_SALES | GET_LOW_STOCK | GET_PREDICTED_STOCKOUT | GET_REORDER",
  "entities": [{ "type": "product", "surface": "<words as written>", "value": "<normalized>" }],
  "writer": "W1",
  "set": "heldout"
}
```

`language_type` is set by a fixed rule, not a judgement: Bengali letters only
is `bangla`, Latin letters only is `romanized`, both is `mixed`; digits do not
count. Intents and entities are labeled by **someone other than the rule
developer**, and a second person independently labels a random 20% so
agreement can be reported (percent agreement, plus Cohen's kappa for intent).

## Before freezing

Remove any held-out sentence that matches a development sentence after simple
cleanup — lowercase, collapse spaces, Bangla digits to 0–9. A sentence that
appears in both sets is not held out.

## Freezing (Step 77)

```bash
node scripts/freeze-heldout.mjs hash <path-to-the-heldout-file>
```

That prints the SHA-256 and writes `heldout-v1.sha256` here. **Only the hash
is committed — never the sentences.** The root `.gitignore` refuses
`*.jsonl` in this folder so the file cannot be added by accident.

The file itself then stays with a writer or a labeler, not with the rule
developer. At Step 108 it is placed in `research/language/heldout/v1/` and
checked before anything is measured:

```bash
node scripts/freeze-heldout.mjs verify <path-to-the-heldout-file>
```

If the hash does not match the committed one, the set was edited after
freezing and no result computed from it counts.
