
ALTER TABLE users
    ADD COLUMN avatar_id VARCHAR(32) NOT NULL DEFAULT 'captain',
    ADD CONSTRAINT ck_users_avatar_id
        CHECK (avatar_id IN ('captain', 'submarine', 'destroyer', 'carrier'));
