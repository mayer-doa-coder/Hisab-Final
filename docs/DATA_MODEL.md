# Data Model

The minimum set of tables Hisab needs. Full detail: `PRD.md`, section 24.

## Value-Type Rules (apply to every table below)
- **Money** fields are integers in the smallest currency unit (poisha for BDT). Never floating-point.
- **Quantity** fields are integers scaled by 1000 (3 decimal places): 0.5 kg = 500, 3 pieces = 3000. Never floating-point.
- **id** on every entity here is globally unique and generated on the device when the record is created — the same ID is used locally and on the server, no separate local/server ID mapping (`../DECISIONS.md` D018).
- Mutable entities (Product, Customer) carry `revision`, `updated_at`, and `deleted_at` for conflict detection and tombstoned deletes (`../DECISIONS.md` D017). Ledger entities (Sale, SaleItem, StockMovement, BakiEntry) don't need these — they're never edited in place.
- Transaction entities record `occurred_at` (when it actually happened, set by the device) separately from `server_received_at` (when the backend processed the sync event) — see `../DECISIONS.md` D019. Never treat server-receipt time as transaction time.

## Shop
- id
- name
- currency (default "BDT")
- timezone
- created_at

## User
- id
- shop_id
- role
- email
- password_hash

## Product
- id
- shop_id
- name
- aliases (other names/spellings a customer might use)
- unit (e.g. piece, kg, bottle)
- purchase_price (money)
- selling_price (money)
- active (true/false)
- revision
- updated_at
- deleted_at (nullable)

## Customer
- id
- shop_id
- name
- phone (optional)
- revision
- updated_at
- deleted_at (nullable)

## Sale
- id
- shop_id
- occurred_at
- server_received_at
- total (money)

## SaleItem
- sale_id
- product_id
- quantity (quantity)
- unit_price (money)

## StockMovement
- id
- product_id
- movement_type (restock, sale, return, damage, correction)
- quantity_delta (quantity; can be negative)
- source_reference (what caused this movement, e.g. a sale ID)
- occurred_at
- server_received_at

Current stock for a product = sum of all its StockMovement.quantity_delta values. This is never stored as a Product field — see the Rule below and `../DECISIONS.md` D020.

## BakiEntry
- id
- customer_id
- amount_delta (money; can be negative for a payment)
- type
- reference
- due_date (optional)
- occurred_at
- server_received_at

Current baki for a customer = sum of all their BakiEntry.amount_delta values.

## SyncOutbox
- event_id (unique, never reused)
- entity_type
- entity_id
- operation
- payload
- base_revision (for updates/deletes of Product or Customer — see D017)
- status
- retry_count

## SyncMetadata
- device_id
- last_server_cursor

## Rules
- Stock and baki are never stored as a single editable number. They are always calculated by adding up the ledger entries above. See `../DECISIONS.md` D001, D002, D020.
- Reversing a credit sale must atomically reverse the Sale, its StockMovement(s), and its BakiEntry together. See `../DECISIONS.md` D021.
- A write to a mutable entity (Product, Customer) must include the revision it was edited from; a write against a stale revision is rejected, not silently applied. See `../DECISIONS.md` D017.
