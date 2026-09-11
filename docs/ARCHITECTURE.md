# Architecture

## Overview
Hisab is an Android app that works fully offline, plus a backend server the app syncs with when internet is available. The phone app never waits for the server to finish a sale, add baki, or update stock — everything is saved on the device first.

## Android App
- Kotlin + Jetpack Compose for the UI.
- Room for the local database.
- WorkManager for background sync.
- Every screen reads and writes through the local database first.

## Backend
- TypeScript + Fastify + PostgreSQL.
- One codebase (a modular monolith), split into modules: auth, shops, products, customers, sales, inventory, baki, sync.
- No microservices.

## Data Flow For a Normal Sale
1. The shopkeeper completes a sale on the phone.
2. The app saves the sale, its stock movement, and (if credit) its baki entry in one local database transaction.
3. In that same transaction, the app writes a sync event to a local outbox.
4. The screen updates right away — the app does not wait for the server.
5. WorkManager sends the outbox event to the backend once internet is available.
6. The backend checks the event's unique ID. If it was already applied, it is ignored. If not, it is applied once and acknowledged.
7. The app marks the event as synced.

This sync foundation (client-generated IDs, outbox, push, pull) is built once, starting with the first feature (Product, M1) — not thrown away and rebuilt in M4. M4 only hardens it. See `../DECISIONS.md` D016.

## Ledgers, Not Editable Balances
- Stock is the sum of StockMovement records, not a single editable number.
- Baki is the sum of BakiEntry records, not a single editable balance.
- Current stock is always derived, never its own Product field.
- Exact fields: `DATA_MODEL.md`. Why: `../DECISIONS.md` D001, D002, D020.

## Mutable Entities Need a Conflict Rule
Product and Customer, unlike the ledgers above, can be edited in place — and can be edited on two offline devices at once. Each carries a `revision`; a write must include the revision it edited from, and a write against a stale revision is rejected rather than silently overwritten. Deletes are tombstones (`deleted_at`), not row removal. Full policy: `../DECISIONS.md` D017.

## Layering: Room, Domain Model, API DTO, Postgres Are Not One Shape
```text
Room entity ↔ mapper ↔ domain model ↔ mapper ↔ API DTO ↔ backend domain ↔ PostgreSQL row
```
They represent the same concept but not the same fields — Android-only sync/local state shouldn't leak into the server schema, and server-only fields shouldn't leak into the UI model. See `../DECISIONS.md` D023.

## Security Boundaries
- The backend derives which shop a request belongs to from the authenticated session — it never trusts a shop_id supplied by the client. This exists from the first endpoint, not only after later hardening. See `../DECISIONS.md` D015.
- One shop can never see or change another shop's data.
- Sensitive keys and tokens live in the Android Keystore, not in plain app storage.
- All network traffic uses HTTPS.
- Full list: `SECURITY.md`.

## Backup Is Not Sync
Sync propagates every change, including a mistake, to every device. Backup must allow restoring to an earlier point in time regardless of what has since synced. See `../DECISIONS.md` D024.

## Language and Fonts
- Bangla text renders in Noto Sans Bengali. English text renders in Merriweather.
- Every user-facing string has a Bangla and an English version.
- Language switching and persistence use Android's built-in per-app language APIs, not a custom mechanism. See `../DECISIONS.md` D014 and `LOCALIZATION.md`.

## Complementary Item Suggestions
- Suggestions are computed from the Sale/SaleItem history already stored on the device. No separate editable "suggestion" record overrides that history.
- Computation happens on the device. No cloud call is required.

## Full Details
This file covers the shape of the system only. For exact requirements, read `PRD.md`. For the build order, read `PHASE_GUIDE.md`.
