# Hisab — Efficient Claude Vibe-Coding Guide

## 1. Principle

Claude should be treated as an implementation agent, not as the permanent source of project truth.

The permanent source of truth must be the repository documentation and tests.

Claude should implement one bounded task at a time. A bounded task can touch both the Android app and the backend when they belong to the same feature — see `docs/PHASE_GUIDE.md`. Features are built end-to-end (local storage + screen + backend), not one layer at a time across the whole app.

---

# 2. Permanent Context Files

Maintain:

```text
CLAUDE.md
CURRENT_PHASE.md
DECISIONS.md
docs/PRD.md
docs/ARCHITECTURE.md
docs/DATA_MODEL.md
docs/SECURITY.md
```

`CLAUDE.md`, `CURRENT_PHASE.md`, and `DECISIONS.md` live at the repository root, since Claude reads them on every task. The other four live under `docs/`, matching the structure defined in `docs/PHASE_GUIDE.md`.

Claude should consult these instead of receiving the complete project explanation repeatedly.

---

# 3. CLAUDE.md

Keep `CLAUDE.md` short — stack, core rules, scope, quality bar. Do not put long research explanations into it.

The real file lives at the repository root: `CLAUDE.md`. This guide does not repeat its content — if you change a rule, edit that file directly, not a copy here.

---

# 4. CURRENT_PHASE.md

This should define exactly what Claude is allowed to work on. `docs/PHASE_GUIDE.md` is a numbered list of small steps (Step 1, Step 2, ...); `CURRENT_PHASE.md` just points at which step is current — it doesn't repeat the step's content, so the two can't drift apart.

Example:

```text
# Current Phase

M3 — Customer, Baki, Payment (Steps 48–61)

Current Step:
Step 54 — Build the Add Baki screen.

Allowed:
- Whatever Step 54 in docs/PHASE_GUIDE.md covers

Not Allowed:
- Sale/stock work (already done in M2)
- Sync hardening, forecasting, Ask Hisab, suggestions (later milestones)
- Unrelated UI

Definition of Done:
- the check described for Step 54 in docs/PHASE_GUIDE.md passes
```

Update this every time a step is checked off, not just when the milestone changes — that's the whole point of numbering the steps this small.

---

# 5. DECISIONS.md

Record important decisions once.

Example:

```text
# D001 — Baki Ledger

Decision:
Customer baki is calculated from append-only BakiEntry records.

Rejected:
Mutable Customer.balance field as the authoritative balance.

Reason:
Auditability, transaction recovery and synchronization correctness.
```

Another example:

```text
# D002 — Forecasting

Decision:
Benchmark lightweight methods before choosing a production algorithm.

Reason:
The research goal is resource-efficient decision support, not model complexity.
```

This prevents Claude from reopening settled architectural decisions.

---

# 6. Do Not Start With “Build Hisab”

Never prompt:

```text
Build the complete Hisab application.
```

That encourages:

- architectural invention;
- unnecessary dependencies;
- unfinished code;
- duplicated abstractions;
- large token consumption.

Use bounded tasks.

---

# 7. Recommended Task Size

`docs/PHASE_GUIDE.md` is already broken into small numbered steps — use one step as one task, most of the time.

Good task (one step from the plan):

```text
Step 54 — Build the Add Baki screen.
```

Also good (one step, when it happens to span Android and backend together, like the first few Product steps):

```text
Step 29 — Create the Product table in Postgres, mapped from the domain model.
```

Bad task (many steps, or many unrelated features, at once):

```text
Build all of Product, Sale, and Baki.
```

```text
Implement inventory, sales, customers, sync and AI.
```

A task can span the Android app and the backend when a single step calls for both — that is not "too big." It is too big when it covers more than one step, or steps from different milestones. One step should ideally correspond to one coherent commit.

---

# 8. Standard Claude Prompt

Use this structure:

```text
Read first:

- CLAUDE.md
- CURRENT_PHASE.md
- DATA_MODEL.md
- the relevant existing implementation files

Task:
Implement the StockMovement persistence layer.

Requirements:
- movement-based stock ledger
- quantity_delta is authoritative
- support restock, sale, return, damage and correction
- current stock must be derived from movements

Constraints:
- do not implement UI
- do not touch backend
- do not change unrelated modules
- do not add dependencies unless absolutely necessary

Before modifying:
1. inspect existing relevant files
2. state the minimal files that need changes
3. flag any conflict with current architecture

Then implement.

After implementation:
1. run relevant tests
2. fix only failures caused by this work
3. summarize files changed
4. summarize tests executed
5. state remaining issues
```

---

# 9. Let Claude Inspect Before Editing

For unfamiliar parts of the project, use:

```text
Inspect the relevant implementation first.

Do not make changes yet.

Explain:
- current data flow
- relevant files
- existing abstractions
- what should be reused
- the smallest implementation change required
```

Then request implementation.

This prevents duplicate services and parallel architecture.

---

# 10. Limit File Scope

Whenever possible specify:

```text
Only modify files under:

android/.../product/
server/src/modules/products/
```

Scope is limited by feature, not by layer — both the Android and backend folders for the feature in progress are fine to touch together; unrelated features are not.

Allow broader changes only when Claude can justify them.

This limits accidental refactoring.

---

# 11. Do Not Continuously Paste the PRD

The PRD should live in the repository.

Instead say:

```text
Read PRD.md sections 8–10 before implementing.
```

This saves tokens and prevents inconsistent copies of requirements.

---

# 12. Avoid Reprinting Code

If Claude edits files directly, do not request:

```text
Show me every complete modified file.
```

Instead ask:

```text
Summarize:
- files changed
- purpose of each change
- important implementation decisions
- tests run
```

Inspect the actual Git diff yourself.

---

# 13. Use Git Diff as the Review Interface

After each task:

```text
git status
git diff
```

Review:

- unexpected files;
- unnecessary dependencies;
- unrelated refactors;
- changed architecture;
- deleted tests;
- hard-coded values;
- security regressions.

Do not accept code only because tests pass.

---

# 14. Commit Small Units

Good history:

```text
feat: add product persistence
test: cover stock ledger calculations
feat: add baki ledger
feat: add cash sale transaction
feat: add outbox persistence
```

Avoid:

```text
feat: implement entire app
```

Small commits make Claude mistakes reversible.

---

# 15. Encode Requirements as Tests

Do not depend on conversation memory.

For example:

```text
credit500_payment200_credit100_balance400()
```

```text
saleOfThreeItems_reducesStockByThree()
```

```text
duplicateSyncEvent_doesNotDuplicateSale()
```

```text
offlineSale_survivesAppRestart()
```

Tests become permanent machine-readable project requirements.

---

# 16. Debugging Prompt

When something breaks, use:

```text
Run the failing test first.

Determine the root cause.

Do not redesign the architecture.

Apply the smallest correct fix.

Then run:
1. the original failing test
2. related module tests

Do not modify unrelated functionality.
```

Avoid:

```text
Fix all bugs.
```

---

# 17. Refactoring Prompt

Use:

```text
Refactor only the identified duplication/problem.

Behavior must remain unchanged.

Before editing:
- identify affected behavior
- identify existing tests protecting it

After editing:
- run those tests
- show whether any public API changed
```

Do not allow opportunistic project-wide cleanup during functional work.

---

# 18. Dependency Rule

Whenever Claude proposes a new dependency, require:

```text
Before adding this dependency, state:

1. why the standard library/current dependencies are insufficient
2. added binary/app-size impact if known
3. maintenance implications
4. whether this dependency is required in production
```

For Hisab, dependency count directly affects:

- application size;
- attack surface;
- build complexity;
- reproducibility.

---

# 19. Research Code Must Stay Separate

Keep:

```text
/research
    /language
    /forecasting
    /benchmarks
```

Research scripts may use heavier Python tooling.

Production Android should contain only the final lightweight implementation required at runtime.

Workflow:

```text
Research implementation
        ↓
Benchmark candidates
        ↓
Select approach
        ↓
Implement compact production equivalent
```

Do not copy entire experimental Python pipelines into production architecture.

---

# 20. Keep Experimental Results Out of Claude Memory

Store results in files:

```text
research/results/
```

Example:

```text
forecast_baselines.csv
language_results.csv
device_benchmarks.csv
sync_failure_results.csv
```

When Claude needs results, point it to the specific file rather than pasting long experiment outputs.

---

# 21. Fresh Context Strategy

Start a fresh Claude conversation/context when:

- switching major phase;
- old context contains many failed attempts;
- Claude begins referring to obsolete architecture;
- the conversation becomes dominated by debugging history.

A fresh context should read:

```text
CLAUDE.md
CURRENT_PHASE.md
relevant architecture document
relevant implementation files
```

This is usually more reliable than preserving a huge conversation.

---

# 22. Do Not Load the Entire Repository by Default

Preferred context order:

```text
1. CLAUDE.md
2. CURRENT_PHASE.md
3. relevant PRD section
4. relevant architecture/data-model section
5. relevant implementation files
6. relevant tests
```

Avoid automatically loading:

- every source file;
- every research paper;
- complete historical chats;
- all benchmark outputs.

---

# 23. End-of-Task Summary

At the end of each task ask Claude to provide only:

```text
Completed:
- ...

Files changed:
- ...

Tests:
- ...

Architecture decisions:
- none / ...

Remaining:
- ...

Recommended next task:
- ...
```

Keep this concise.

---

# 24. Update CURRENT_PHASE.md

After a meaningful milestone, update:

```text
Completed work
Current state
Tests passing
Known problems
Next exact task
```

Do not store conversational history.

---

# 25. Manual Review Checklist

Before accepting Claude-generated work, manually check:

## Architecture

- Does it follow existing architecture?
- Did it create duplicate abstractions?
- Did it violate ledger design?
- Did it introduce unnecessary coupling?

## Scope

- Is the change inside the current phase?
- Did Claude implement unrequested features?

## Dependencies

- Were unnecessary packages added?

## Security

- Were credentials hard-coded?
- Was sensitive data logged?
- Are authorization checks preserved?

## Data

- Are monetary operations safe?
- Are database transactions atomic?
- Can history be silently mutated?
- Are complementary item suggestions derived from the Sale/SaleItem ledger rather than a separately editable source?

## Offline

- Does the functionality still work without network access where required?

## Localization and Typography

- Does every new user-facing string ship with both a Bangla and an English version?
- Is Bangla still the default, with English always available and switchable?
- Are only Noto Sans Bengali and Merriweather used, applied correctly per script, across every UI state including mixed-language strings?
- Do safety/status outcome codes remain language-neutral, with Bangla and English messages carrying the same meaning?

## Tests

- Are important behaviors tested?
- Were existing tests weakened or deleted?

---

# 26. Manual Research Validation Rule

Never accept Claude's research conclusion simply because it sounds plausible.

For:

- forecasting methodology;
- evaluation metrics;
- SoftwareX requirements;
- security standards;
- Bangla/Banglish literature;
- research comparisons;

you should independently verify the relevant sources.

Claude can implement the agreed methodology.

It should not silently redefine it.

---

# 27. Phase Transition Rule

Claude should not decide when a phase is complete.

You decide after manually checking:

```text
requirements
implementation
tests
research relevance
architecture
security
```

Then update `CURRENT_PHASE.md`.

---

# 28. Recommended Overall Workflow

```text
You manually validate requirement
        ↓
Update PRD/architecture if necessary
        ↓
Set CURRENT_PHASE
        ↓
Claude inspects relevant files
        ↓
Claude performs one bounded task
        ↓
Automated tests
        ↓
You inspect Git diff
        ↓
You manually verify behavior
        ↓
Commit
        ↓
Next bounded task
```

---

# 29. Research Workflow

Research decisions should follow:

```text
Literature review
        ↓
Manual methodology decision
        ↓
Research protocol written
        ↓
Claude implements experiment
        ↓
Experiment executed
        ↓
Results stored
        ↓
Manual interpretation
        ↓
Production decision
```

Claude should assist the research process, not replace methodological judgment.

---

# 30. Core Rule

Use Claude to **write, test, debug and refactor bounded implementation tasks**.

Do not use Claude as the only authority for:

- project scope;
- research novelty;
- experimental validity;
- publication claims;
- security claims;
- final architectural decisions.

Those remain your manual checkpoints.