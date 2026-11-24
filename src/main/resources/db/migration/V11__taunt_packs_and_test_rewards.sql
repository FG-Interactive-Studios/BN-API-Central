-- Monetização demonstrativa: todas as operações usam créditos virtuais, jamais dinheiro real.
-- Mantém os preços/saldos e compras atuais intactos.
ALTER TABLE store_items DROP CONSTRAINT chk_store_items_category;
ALTER TABLE store_items ADD CONSTRAINT chk_store_items_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN','BUNDLE','TAUNT'));

ALTER TABLE player_equipment DROP CONSTRAINT chk_player_equipment_category;
ALTER TABLE player_equipment ADD CONSTRAINT chk_player_equipment_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN','TAUNT'));

-- Recompensas idempotentes: exatamente uma por jogador/partida finalizada.
CREATE TABLE virtual_match_rewards (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_id UUID NOT NULL,
    credits INTEGER NOT NULL CHECK (credits > 0),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, match_id)
);
