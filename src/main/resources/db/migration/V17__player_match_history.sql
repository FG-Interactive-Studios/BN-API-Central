-- Permanent aggregates are independent of the bounded, per-player recent history.
CREATE TABLE player_match_statistics (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    total_matches BIGINT NOT NULL DEFAULT 0,
    wins BIGINT NOT NULL DEFAULT 0,
    losses BIGINT NOT NULL DEFAULT 0,
    draws BIGINT NOT NULL DEFAULT 0,
    current_win_streak BIGINT NOT NULL DEFAULT 0,
    best_win_streak BIGINT NOT NULL DEFAULT 0,
    CHECK (total_matches = wins + losses + draws),
    CHECK (wins >= 0 AND losses >= 0 AND draws >= 0 AND current_win_streak >= 0
        AND best_win_streak >= current_win_streak AND best_win_streak <= wins)
);

-- Only the UUID is retained permanently for idempotency, not the match details.
CREATE TABLE recorded_match_results (
    match_id UUID PRIMARY KEY
);

CREATE TABLE player_match_history (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_id UUID NOT NULL REFERENCES recorded_match_results(match_id),
    opponent_nickname VARCHAR(30) NOT NULL,
    mode_id TEXT NOT NULL,
    mode_name TEXT NOT NULL,
    result VARCHAR(4) NOT NULL CHECK (result IN ('WIN','LOSS','DRAW')),
    finish_reason TEXT NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL CHECK (hits >= 0),
    hits_received INTEGER NOT NULL CHECK (hits_received >= 0),
    PRIMARY KEY (user_id, match_id)
);
CREATE INDEX ix_player_match_history_recent ON player_match_history(user_id, finished_at DESC, match_id DESC);
