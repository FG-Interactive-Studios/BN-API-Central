
-- Preserve audit/history; revoke all but the newest unrevoked session for each player.
-- New logins revoke remaining sessions before inserting a replacement.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY user_id ORDER BY created_at DESC, id DESC
    ) AS rank_for_user
    FROM auth_sessions
    WHERE revoked_at IS NULL
)
UPDATE auth_sessions AS sessions
SET revoked_at = CURRENT_TIMESTAMP
FROM ranked
WHERE sessions.id = ranked.id AND ranked.rank_for_user > 1;

-- Expired rows are intentionally included in the uniqueness predicate:
-- a login explicitly revokes them so they cannot accumulate as unrevoked sessions.
CREATE UNIQUE INDEX uq_auth_sessions_one_unrevoked_per_user
    ON auth_sessions(user_id)
    WHERE revoked_at IS NULL;
