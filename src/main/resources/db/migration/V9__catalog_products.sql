-- Evolução aditiva da loja: preserve todos os IDs, créditos, compras e equipamentos.
-- CAPTAIN e BUNDLE são categorias de produtos em preparação, não vendáveis nesta etapa.
ALTER TABLE store_items DROP CONSTRAINT store_items_category_check;
ALTER TABLE store_items ADD CONSTRAINT chk_store_items_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN','BUNDLE'));

ALTER TABLE player_equipment DROP CONSTRAINT player_equipment_category_check;
ALTER TABLE player_equipment ADD CONSTRAINT chk_player_equipment_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN'));

-- Only an explicit revision increment in the reviewed API catalog may update
-- price/name/availability. Existing V7 items are adopted without rewriting.
CREATE TABLE store_catalog_managed (
    item_id VARCHAR(40) PRIMARY KEY REFERENCES store_items(id) ON DELETE CASCADE,
    applied_revision INTEGER NOT NULL CHECK (applied_revision > 0)
);

-- Prepared bundle membership is recorded, but purchasing a bundle is rejected
-- until a future atomic entitlement/fulfillment implementation is approved.
CREATE TABLE store_bundle_components (
    bundle_id VARCHAR(40) NOT NULL REFERENCES store_items(id) ON DELETE CASCADE,
    item_id VARCHAR(40) NOT NULL REFERENCES store_items(id),
    PRIMARY KEY (bundle_id, item_id),
    CONSTRAINT chk_store_bundle_not_self CHECK (bundle_id <> item_id)
);
CREATE INDEX idx_store_bundle_component_item ON store_bundle_components(item_id);
