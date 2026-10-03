-- Account management (Task 13b). Kept apart from accounts so identity stays as it was.
-- suspended_at: set while suspended. A suspended account can't send letters, reply or publish
--   open letters, but can still sign in, read its letters and back up its wallet.
-- sessions_revoked_at: tokens issued before this instant are rejected (forced sign-out).
CREATE TABLE account_status (
    account_id          UUID        PRIMARY KEY REFERENCES accounts (id),
    suspended_at        TIMESTAMPTZ,
    suspension_reason   TEXT        CHECK (char_length(suspension_reason) BETWEEN 1 AND 500),
    sessions_revoked_at TIMESTAMPTZ,
    CHECK ((suspended_at IS NULL) = (suspension_reason IS NULL))
);
