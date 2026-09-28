# Language data (M5, Steps 75–77)

Two sets, kept apart on purpose. `docs/RESEARCH_PLAN.md` is the authority;
this page says where things are.

| | Development set | Held-out set |
| --- | --- | --- |
| Where | `dev/dev-set.jsonl` | not in this repository, by design |
| Who writes it | anyone, including the rule developer | only someone who is **not** the rule developer and has not seen the system |
| Templates allowed | yes — `generate-dev-set.mjs` | **no** |
| Used for | building and tuning the rules (Steps 73–74, 78–83) | the one final measurement, at Step 108 |
| Committed | yes | only its SHA-256 |

## Development set (Step 75)

110 examples, rebuilt by:

```bash
node research/language/generate-dev-set.mjs
```

Counts as generated: `GET_STOCK` 40, `GET_CUSTOMER_BAKI` 32, `GET_PERIOD_SALES`
17, `GET_OVERDUE` 11, `GET_TODAY_SALES` 10; romanized 40, bangla 35, mixed 35.
Every row carries the seven fields the plan requires — `id`, `text`,
`language_type`, `intent`, `entities`, `writer`, `set` — and no two rows share
a sentence, so nothing is silently weighted double when Step 83 measures
accuracy.

`language_type` is computed by the plan's fixed rule rather than judged:
Bengali letters only is `bangla`, Latin only is `romanized`, both is `mixed`,
and digits count for neither.

Customer names are made up (রহিম, করিম, সালমা, জসিম, নাসরিন). No real person's
data is in here.

## Held-out set (Steps 76–77)

See `heldout/README.md`. It has not been written, and could not have been
written here: Steps 73–74 were built by Claude, which disqualifies Claude from
authoring the sentences it would then be measured against. The situation list
for whoever does write it is `heldout/SITUATIONS.md`, in Bangla, with no
example sentences in it.

## Pipeline evidence

`normalization-stages.md` is Step 73's check — the plan's example sentences run
through the real pipeline with every stage shown, including the two bugs that
inspection caught.
