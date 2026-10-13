-- Admin privileges are always assigned out-of-band by a trusted database operator.
-- Public account registration and JWT claims must never grant admin access.
ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE game_mode_settings (
    mode_id VARCHAR(32) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT REFERENCES users(id) ON DELETE SET NULL
);

-- Existing playable modes stay available after the migration.
-- Future Java-registered modes are disabled until an admin explicitly enables them.
INSERT INTO game_mode_settings(mode_id, enabled)
VALUES ('classic', TRUE),
       ('quick', TRUE),
       ('triangular', TRUE),
       ('triangle-tiles', TRUE),
       ('hexagons', TRUE);
