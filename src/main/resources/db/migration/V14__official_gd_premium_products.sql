-- Existing wallets, prices and purchases retain their original semantics.
ALTER TABLE store_items ADD COLUMN premium_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE store_items ADD CONSTRAINT chk_premium_only_price CHECK (NOT premium_only OR price > 0);
