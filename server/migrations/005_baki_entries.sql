-- Baki entries added, paid, and undone by hand (Step 58).
--
-- Migration 004 created baki_entry only for what a credit sale writes
-- (credit_sale, reversal — D035, D038). Steps 52–57 added three more kinds on
-- the phone (credit, payment, entry_reversal — D041, D043); this is the
-- server catching up, the same way migration 002 widened change tracking to
-- a table that already existed.

ALTER TABLE baki_entry DROP CONSTRAINT baki_entry_type_known;
ALTER TABLE baki_entry ADD CONSTRAINT baki_entry_type_known
    CHECK (entry_type IN ('credit_sale', 'reversal', 'credit', 'payment', 'entry_reversal'));

-- A hand-written entry needs its own place in the shared pull order (D039).
-- Migration 004 only gave one to the two kinds a sale writes, since those
-- always travel inside the sale that made them and are ordered by the sale's
-- own change_seq.
ALTER TABLE baki_entry ADD COLUMN change_seq BIGINT NOT NULL DEFAULT nextval('change_seq');
CREATE INDEX baki_entry_standalone_change_seq_idx ON baki_entry (shop_id, change_seq)
    WHERE sale_id IS NULL;

-- An entry_reversal can undo its target once. Two devices undoing the same
-- hand-written credit or payment while both offline is caught here — the
-- same way sale_reversed_once_idx catches two devices reversing the same
-- sale. The phone's own transaction only guards one device at a time (D043);
-- this is the guarantee between devices, promised there and built here.
CREATE UNIQUE INDEX baki_entry_reversed_once_idx ON baki_entry (reference)
    WHERE entry_type = 'entry_reversal';
