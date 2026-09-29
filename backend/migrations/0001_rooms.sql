-- A room is one JSON document (under 4 KB), read and written whole. The two millisecond columns
-- exist only so the retention sweep can find expired rooms without reading the documents.
CREATE TABLE rooms (
    code          TEXT PRIMARY KEY,
    data          JSONB  NOT NULL,
    last_write_ms BIGINT NOT NULL,
    outcome_ms    BIGINT
);

CREATE INDEX rooms_last_write_ms ON rooms (last_write_ms);
CREATE INDEX rooms_outcome_ms ON rooms (outcome_ms) WHERE outcome_ms IS NOT NULL;

-- A pilot code bound to the SHA-256 of its secret on the first write the server sees. The secret
-- is 160 random bits, so a fast hash is enough; only the hash is ever stored.
CREATE TABLE pilots (
    code        TEXT PRIMARY KEY,
    secret_hash BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
