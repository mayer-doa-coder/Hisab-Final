# data/

Room database and network calls live here — see `docs/ARCHITECTURE.md` and
`DECISIONS.md` D026.

`sync/` (Steps 13-14): `SyncOutboxEntity`/`SyncOutboxDao` and
`SyncMetadataEntity`/`SyncMetadataDao` — the local tables every feature's
sync writes to, once a feature exists to write. Nothing writes to them yet;
Product's DAO (M1, Step 23) is the first real writer.

`domain/` holds the rules; this layer only stores and fetches.
