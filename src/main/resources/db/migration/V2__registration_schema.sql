ALTER TABLE users
    ADD COLUMN nickname VARCHAR(30) NOT NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_users_nickname_length CHECK (CHAR_LENGTH(nickname) BETWEEN 3 AND 30),
    ADD CONSTRAINT ck_users_nickname_trimmed CHECK (nickname = BTRIM(nickname));

CREATE UNIQUE INDEX uq_users_nickname_ci ON users (LOWER(nickname));

ALTER TABLE auth
    ADD COLUMN user_id BIGINT NOT NULL,
    ADD COLUMN email VARCHAR(254) NOT NULL,
    ADD COLUMN password_hash VARCHAR(100) NOT NULL,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT fk_auth_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    ADD CONSTRAINT uq_auth_user UNIQUE (user_id),
    ADD CONSTRAINT uq_auth_email UNIQUE (email),
    ADD CONSTRAINT ck_auth_email_normalized CHECK (email = LOWER(email)),
    ADD CONSTRAINT ck_auth_password_hash_not_blank CHECK (CHAR_LENGTH(password_hash) > 0);
