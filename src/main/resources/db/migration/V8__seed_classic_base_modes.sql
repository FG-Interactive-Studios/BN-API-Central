-- GD v1.2: enable only the two initial square 8x8 game modes by default.
-- The legacy experimental modes remain implemented but are not offered until
-- their base sprites and game-design rules are validated. Existing lobbies use
-- immutable snapshots; the migration affects only new room creation.
-- Explicit admin actions (updated_by set) are not overwritten on upgrade.
INSERT INTO game_mode_settings(mode_id, enabled)
VALUES ('classic', TRUE), ('classic-powers', TRUE)
ON CONFLICT (mode_id) DO NOTHING;

UPDATE game_mode_settings SET enabled = FALSE
WHERE mode_id IN ('quick','triangular','triangle-tiles','hexagons')
  AND updated_by IS NULL;
