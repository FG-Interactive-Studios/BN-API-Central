-- MVP: exclusively virtual, non-transferable TEST credits. No payment processor.
CREATE TABLE store_items (
    id VARCHAR(40) PRIMARY KEY,
    category VARCHAR(16) NOT NULL CHECK (category IN ('SKIN', 'SOUND')),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(255) NOT NULL,
    price INTEGER NOT NULL CHECK (price >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE player_wallets (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    credits INTEGER NOT NULL DEFAULT 300 CHECK (credits >= 0)
);
CREATE TABLE player_items (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id VARCHAR(40) NOT NULL REFERENCES store_items(id),
    purchased_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, item_id)
);
CREATE TABLE player_equipment (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category VARCHAR(16) NOT NULL CHECK (category IN ('SKIN', 'SOUND')),
    item_id VARCHAR(40) NOT NULL,
    PRIMARY KEY (user_id, category),
    CONSTRAINT fk_player_equipment_owned_item
        FOREIGN KEY (user_id, item_id) REFERENCES player_items(user_id, item_id)
);
CREATE INDEX idx_player_items_user ON player_items(user_id);
INSERT INTO store_items (id, category, name, description, price, enabled, display_order) VALUES
('skin_default', 'SKIN', 'Frota Original', 'Acabamento azul tradicional das embarcações.', 0, TRUE, 10),
('skin_coral', 'SKIN', 'Coral', 'Tons quentes de coral nos seus navios.', 90, TRUE, 20),
('skin_abyss', 'SKIN', 'Abissal', 'Navios com acabamento violeta das profundezas.', 140, TRUE, 30),
('sound_default', 'SOUND', 'Sons Padrão', 'Áudio padrão do jogo, quando disponível.', 0, TRUE, 40),
('sound_sonar', 'SOUND', 'Sonar Retrô', 'Pacote de sons aguardando entrega do Game Design.', 90, FALSE, 50),
('sound_deep', 'SOUND', 'Mar Profundo', 'Pacote de sons aguardando entrega do Game Design.', 140, FALSE, 60);
