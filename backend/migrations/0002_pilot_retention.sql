-- A pilot's code and secret hash are deleted 180 days after the pilot's last write, like a room.
-- Existing pilots count from the time of this migration.
ALTER TABLE pilots ADD COLUMN last_write_ms BIGINT NOT NULL
    DEFAULT (extract(epoch FROM now()) * 1000)::BIGINT;
ALTER TABLE pilots ALTER COLUMN last_write_ms DROP DEFAULT;
CREATE INDEX pilots_last_write_ms ON pilots (last_write_ms);
