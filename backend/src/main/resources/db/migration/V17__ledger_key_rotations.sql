-- Rotations of the ledger key. signature = Ed25519 by the old key over
-- "writeproof/key-rotation/v1\n" old_key "\n" new_key "\n" size "\n" root "\n" timestamp_millis
-- (keys and root base64url). The old key vouches for checkpoints up to size, the new key from size on.
-- A key is rotated away from at most once and never comes back. Append-only like the ledger itself.
CREATE TABLE ledger_key_rotations (
    id                BIGSERIAL   PRIMARY KEY,
    old_key           BYTEA       NOT NULL UNIQUE CHECK (octet_length(old_key) = 32),
    new_key           BYTEA       NOT NULL UNIQUE CHECK (octet_length(new_key) = 32),
    size              BIGINT      NOT NULL CHECK (size >= 0),
    root              BYTEA       NOT NULL CHECK (octet_length(root) = 32),
    timestamp_millis  BIGINT      NOT NULL,
    signature         BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    rotated_at        TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER ledger_key_rotations_append_only
    BEFORE UPDATE OR DELETE ON ledger_key_rotations
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER ledger_key_rotations_no_truncate
    BEFORE TRUNCATE ON ledger_key_rotations
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
