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
- total (money)
- payment (cash or credit)
- customer_id (set for a credit sale, null for cash)
- reverses_sale_id (nullable; the sale this one undoes — see `../DECISIONS.md` D033)
- occurred_at
- server_received_at

`payment`, `customer_id` and `reverses_sale_id` were added after the first draft of this file. PRD section 8 requires choosing cash or baki and associating a customer with a credit sale, and a reversal cannot know whether to clear baki without knowing which it was. Undoing a sale writes a second Sale with a negative total rather than editing the first, so a day's takings already exclude anything reversed — `reverses_sale_id` is what links the two. Full reasoning: `../DECISIONS.md` D033.

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

A sale is never refused because this sum is too low, so it can go negative — that is a visible signal the ledger is missing a restock, not an error state (`../DECISIONS.md` D031).

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

`type` is one of five, stored as a language-neutral name (`../DECISIONS.md` D011, D041):

| type | amount_delta | written by | reference |
| --- | --- | --- | --- |
| `credit_sale` | positive | a credit sale (`completeCreditSale`) | the sale id |
| `reversal` | negative | undoing a credit sale (`reverseSale`) | the sale id |
| `credit` | positive | baki added by hand (`addCredit`) | null |
| `payment` | negative | the customer paying back (`receivePayment`) | null |
| `entry_reversal` | opposite of what it undoes | undoing a `credit` or `payment` (`reverseEntry`) | the original entry's id |

`reference` is never free text: it is a sale id, an entry id, or null. A credit sale's entry is undone only together with its sale and stock, never on its own (`../DECISIONS.md` D021), and a reversal is never itself reversed.

## SyncOutbox
- event_id (unique, never reused)
- entity_type
- entity_id
- operation
- payload
- base_revision (for updates/deletes of Product or Customer — see D017)
- client_timestamp
- status
- retry_count

## SyncMetadata
- device_id
- last_server_cursor

## Where the Server Differs From the Phone
The tables above are the shared shape. The Postgres side (`server/migrations/004_sales_stock_baki.sql`) adds three things the phone does not need, and it is deliberate that they stay server-only (D023, D026):

- **`shop_id` on every row**, including SaleItem, StockMovement and BakiEntry. A phone holds one shop, so it does not need the column; the server holds many, and every query is scoped by the shop in the session token (D015).
- **`sale_id` on StockMovement and BakiEntry.** The phone keeps that link in `source_reference`/`reference`; the server needs a real column so a pull can send a sale and everything it caused as one change, never half of one (D021, D038).
- **`change_seq` on Product, Customer, Sale and stand-alone StockMovement**, all from one shared sequence, which is what a pull's single cursor counts through (D039).

`server_received_at` is filled in by the server when a row arrives; `occurred_at` is always what the device said (D019).

## Rules
- Stock and baki are never stored as a single editable number. They are always calculated by adding up the ledger entries above. See `../DECISIONS.md` D001, D002, D020.
- Reversing a credit sale must atomically reverse the Sale, its StockMovement(s), and its BakiEntry together. See `../DECISIONS.md` D021.
- A write to a mutable entity (Product, Customer) must include the revision it was edited from; a write against a stale revision is rejected, not silently applied. See `../DECISIONS.md` D017.
