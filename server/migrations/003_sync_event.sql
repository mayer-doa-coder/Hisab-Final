-- The server must never apply the same sync event twice (D004, CLAUDE.md).
--
-- Until now that memory lived in the server process, so a restart forgot it
-- and a phone retrying an old push could apply a change again. It belongs in
-- the database, next to the data it protects.
--
-- Only accepted events are recorded. An event rejected for a stale revision is
-- not written here, so the phone can send it again after it catches up.

CREATE TABLE sync_event (
    event_id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX sync_event_shop_idx ON sync_event (shop_id, received_at);
