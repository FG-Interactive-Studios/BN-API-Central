-- Keep sale availability independent from versioned asset release and acquired rights.
ALTER TABLE store_items ADD COLUMN sale_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE store_items ADD COLUMN sale_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE store_items ADD COLUMN sale_updated_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
