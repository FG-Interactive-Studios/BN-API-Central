-- Reserved premium balance for approved UI. No payment processing or direct spending.
-- Existing wallets receive 0; no credits are reset or converted.
ALTER TABLE player_wallets
  ADD COLUMN premium_credits INTEGER NOT NULL DEFAULT 0 CHECK (premium_credits >= 0);
