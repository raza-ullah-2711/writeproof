-- Signed checkpoints (tree heads) of the ledger's Merkle tree, published for outside witnesses.
-- signature = Ed25519 over "writeproof/checkpoint/v1\n" size "\n" root "\n" timestamp_millis,
-- by the ledger key (LEDGER_SIGNING_KEY). Append-only like the ledger itself.
CREATE TABLE ledger_checkpoints (
    size              BIGINT      PRIMARY KEY CHECK (size >= 0),
    root              BYTEA       NOT NULL CHECK (octet_length(root) = 32),
    timestamp_millis  BIGINT      NOT NULL,
    signature         BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    published_at      TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER ledger_checkpoints_append_only
    BEFORE UPDATE OR DELETE ON ledger_checkpoints
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER ledger_checkpoints_no_truncate
    BEFORE TRUNCATE ON ledger_checkpoints
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
