-- A pull has to answer "what changed since I last asked?" (Step 33).
--
-- `updated_at` cannot answer it reliably: two rows can share a timestamp, and
-- clocks move. A sequence gives every change its own ever-increasing number,
-- so a device can ask for everything above the last number it saw.
--
-- Inserts take the next number automatically. Updates must ask for a new one
-- explicitly, which the repository does — a change nobody numbers is a change
-- no device would ever be told about.

CREATE SEQUENCE product_change_seq;

ALTER TABLE product
    ADD COLUMN change_seq BIGINT NOT NULL DEFAULT nextval('product_change_seq');

CREATE INDEX product_shop_change_seq_idx ON product (shop_id, change_seq);
