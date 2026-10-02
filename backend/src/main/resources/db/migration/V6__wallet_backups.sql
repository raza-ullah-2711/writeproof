-- Encrypted wallet backups (one per account, replaceable). The blob is AES-256-GCM ciphertext
-- under a key derived from a recovery code the server never sees; lookup_id is derived from the
-- same code, so a new device can find the backup with the code alone.
CREATE TABLE wallet_backups (
    account_id  UUID        PRIMARY KEY REFERENCES accounts (id),
    lookup_id   BYTEA       NOT NULL UNIQUE CHECK (octet_length(lookup_id) = 32),
    blob        JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
