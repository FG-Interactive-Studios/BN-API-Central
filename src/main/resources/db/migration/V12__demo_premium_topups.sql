-- Simulated checkout: no real payment, idempotent per authenticated user.
CREATE TABLE demo_premium_topups (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    pack_id VARCHAR(20) NOT NULL CHECK (pack_id IN ('demo_100','demo_300','demo_700')),
    amount INTEGER NOT NULL CHECK (amount IN (100,300,700)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id,request_id)
);
