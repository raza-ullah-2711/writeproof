-- Wallet identity: an account *is* an Ed25519 public key. No usernames,
-- passwords or email are stored. Private keys never reach the server.
CREATE TABLE accounts (
    id          UUID        PRIMARY KEY,
    public_key  BYTEA       NOT NULL UNIQUE CHECK (octet_length(public_key) = 32),
    created_at  TIMESTAMPTZ NOT NULL
);

-- Single-use login nonces for challenge-response authentication.
CREATE TABLE auth_challenges (
    id          UUID        PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES accounts (id),
    nonce       BYTEA       NOT NULL CHECK (octet_length(nonce) = 32),
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);

CREATE INDEX auth_challenges_expires_at_idx ON auth_challenges (expires_at);
