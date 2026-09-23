-- Sales, stock and baki in Postgres (Step 45).
--
-- The phone already keeps these as ledgers (Steps 38 and 40); this is the
-- server's copy of the same rows, so a sale made offline can be pushed here
-- and a second device can pull it (Steps 46–47).
--
-- What differs from the phone's tables, and why (D023, D026):
--   * Every table carries shop_id. On the phone there is only one shop, so
--     sale lines and stock movements do not need it; here every query is
--     scoped to the shop in the session token (D015), and that is simplest
--     and safest when the column is on the row itself.
--   * stock_movement and baki_entry carry sale_id when a sale caused them.
--     The phone keeps that link in source_reference/reference; the server
--     needs it as a real column so a pull can send a sale and everything it
--     caused as one change, never half of one (D021).
--   * server_received_at is filled in here, by the server, the moment a row
--     arrives. occurred_at is always what the phone said (D019).
--
-- No foreign key points at product. A sale can reach the server before the
-- product it sold does — sync order, or a product still waiting behind a
-- conflict — and refusing the sale would lose it rather than delay it. The
-- same reasoning the phone's schema uses.
--
-- Money is BIGINT poisha and quantity BIGINT scaled by 1000. Never floating
-- point (CLAUDE.md, D019).

-- One change counter for every table a device pulls. A pull asks for
-- everything after one number; that only works if all tables draw their
-- numbers from the same sequence. It was created for products alone
-- (migration 002), so it is renamed rather than duplicated. The product
-- column's default refers to the sequence itself, not its name, so it keeps
-- working through the rename.
ALTER SEQUENCE product_change_seq RENAME TO change_seq;

CREATE TABLE customer (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    name TEXT NOT NULL,
    phone TEXT,
    revision INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    change_seq BIGINT NOT NULL DEFAULT nextval('change_seq'),

    CONSTRAINT customer_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT customer_revision_starts_at_one CHECK (revision >= 1)
);

CREATE INDEX customer_shop_name_idx ON customer (shop_id, lower(name));
CREATE INDEX customer_shop_change_seq_idx ON customer (shop_id, change_seq);

CREATE TABLE sale (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    total_poisha BIGINT NOT NULL,
    payment TEXT NOT NULL,
    customer_id TEXT REFERENCES customer (id),
    reverses_sale_id TEXT REFERENCES sale (id),
    occurred_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    change_seq BIGINT NOT NULL DEFAULT nextval('change_seq'),

    CONSTRAINT sale_payment_known CHECK (payment IN ('cash', 'credit')),
    -- A credit sale names who owes it; a cash sale names nobody.
    CONSTRAINT sale_customer_matches_payment CHECK ((payment = 'credit') = (customer_id IS NOT NULL)),
    CONSTRAINT sale_does_not_reverse_itself CHECK (reverses_sale_id IS NULL OR reverses_sale_id <> id)
);

-- A sale can be undone once. Two devices reversing the same sale while
-- offline is caught here, when the second one arrives.
CREATE UNIQUE INDEX sale_reversed_once_idx ON sale (reverses_sale_id) WHERE reverses_sale_id IS NOT NULL;
CREATE INDEX sale_shop_occurred_idx ON sale (shop_id, occurred_at DESC);
CREATE INDEX sale_shop_change_seq_idx ON sale (shop_id, change_seq);

CREATE TABLE sale_item (
    sale_id TEXT NOT NULL REFERENCES sale (id) ON DELETE CASCADE,
    product_id TEXT NOT NULL,
    shop_id TEXT NOT NULL,
    quantity_scaled BIGINT NOT NULL,
    unit_price_poisha BIGINT NOT NULL,

    PRIMARY KEY (sale_id, product_id),
    CONSTRAINT sale_item_quantity_not_zero CHECK (quantity_scaled <> 0),
    CONSTRAINT sale_item_price_not_negative CHECK (unit_price_poisha >= 0)
);

CREATE TABLE stock_movement (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    movement_type TEXT NOT NULL,
    quantity_delta_scaled BIGINT NOT NULL,
    source_reference TEXT,
    -- Set when a sale (or its reversal) caused this movement; null for a
    -- restock, damage or shelf count, which stand on their own.
    sale_id TEXT REFERENCES sale (id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    change_seq BIGINT NOT NULL DEFAULT nextval('change_seq'),

    CONSTRAINT stock_movement_type_known CHECK (
        movement_type IN ('restock', 'sale', 'return', 'damage', 'correction')
    )
);

-- Current stock is always a SUM over this table (D002, D020).
CREATE INDEX stock_movement_shop_product_idx ON stock_movement (shop_id, product_id);
CREATE INDEX stock_movement_sale_idx ON stock_movement (sale_id);
CREATE INDEX stock_movement_standalone_change_seq_idx ON stock_movement (shop_id, change_seq)
    WHERE sale_id IS NULL;

CREATE TABLE baki_entry (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    customer_id TEXT NOT NULL REFERENCES customer (id),
    amount_delta_poisha BIGINT NOT NULL,
    entry_type TEXT NOT NULL,
    reference TEXT,
    sale_id TEXT REFERENCES sale (id) ON DELETE CASCADE,
    due_date DATE,
    occurred_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT baki_entry_type_known CHECK (entry_type IN ('credit_sale', 'reversal'))
);

-- A customer's baki is always a SUM over this table (D001).
CREATE INDEX baki_entry_shop_customer_idx ON baki_entry (shop_id, customer_id);
CREATE INDEX baki_entry_sale_idx ON baki_entry (sale_id);
