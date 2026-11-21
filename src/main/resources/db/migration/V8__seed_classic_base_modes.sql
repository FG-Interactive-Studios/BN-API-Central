-- Both square base modes are available on first installation and on upgrade
-- when not previously configured. Preserve every existing admin decision,
-- including explicit false, together with its audit metadata.
INSERT INTO game_mode_settings(mode_id, enabled)
VALUES ('classic', TRUE), ('classic-powers', TRUE)
ON CONFLICT (mode_id) DO NOTHING;
