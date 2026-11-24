-- Only presentation categories; no battle rule or damage changes.
ALTER TABLE store_items DROP CONSTRAINT chk_store_items_category;
ALTER TABLE store_items ADD CONSTRAINT chk_store_items_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN','BUNDLE','TAUNT','ATTACK','CELEBRATION','FUNERAL','TITLE','COMMENTATOR','FRAME','BOARD_THEME'));
ALTER TABLE player_equipment DROP CONSTRAINT chk_player_equipment_category;
ALTER TABLE player_equipment ADD CONSTRAINT chk_player_equipment_category
    CHECK (category IN ('SKIN','SOUND','CAPTAIN','TAUNT','ATTACK','CELEBRATION','FUNERAL','TITLE','COMMENTATOR','FRAME','BOARD_THEME'));
