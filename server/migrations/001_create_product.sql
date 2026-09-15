-- Product, the first real entity (Step 29).
--
-- Column names are snake_case, the SQL convention used in docs/DATA_MODEL.md.
-- The shape is mapped from the domain model, not copied from the Room entity
-- on the phone (Step 7): the phone keeps aliases in one text column because
-- SQLite has no array type, while Postgres stores them as a real array.
--
-- Money is BIGINT poisha — never a float (CLAUDE.md). Stock is deliberately
-- absent: it is always summed from stock movements (D020).

CREATE TABLE product (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    name TEXT NOT NULL,
    aliases TEXT[] NOT NULL DEFAULT '{}',
    unit TEXT NOT NULL,
    purchase_price_poisha BIGINT,
    selling_price_poisha BIGINT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    revision INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT product_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT product_selling_price_not_negative CHECK (selling_price_poisha >= 0),
    CONSTRAINT product_purchase_price_not_negative CHECK (
        purchase_price_poisha IS NULL OR purchase_price_poisha >= 0
    ),
    CONSTRAINT product_revision_starts_at_one CHECK (revision >= 1)
);

-- Every query is scoped to one shop (D015), and the list is ordered by name.
CREATE INDEX product_shop_name_idx ON product (shop_id, lower(name));
