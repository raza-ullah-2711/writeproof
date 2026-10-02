-- Encryption key for sealed delivery. X25519, signed by the account's Ed25519 identity key
-- so the server can't substitute its own key (senders verify the signature).
ALTER TABLE accounts
    ADD COLUMN encryption_key           BYTEA CHECK (octet_length(encryption_key) = 32),
    ADD COLUMN encryption_key_signature BYTEA CHECK (octet_length(encryption_key_signature) = 64);

-- Mock ledger: a hash-chained, append-only table behind LedgerService.
-- entry_hash = SHA-256("writeproof/ledger/v1\n" seq "\n" prev "\n" payload "\n" recorded_at_millis)
CREATE TABLE ledger_entries (
    seq           BIGINT      PRIMARY KEY,
    prev_hash     BYTEA       NOT NULL CHECK (octet_length(prev_hash) = 32),
    payload_hash  BYTEA       NOT NULL CHECK (octet_length(payload_hash) = 32),
    recorded_at   TIMESTAMPTZ NOT NULL,
    entry_hash    BYTEA       NOT NULL UNIQUE CHECK (octet_length(entry_hash) = 32)
);

-- Letters: ciphertext only. The server can verify the signature but never read the body.
CREATE TABLE letters (
    id            UUID        PRIMARY KEY,
    sender_id     UUID        NOT NULL REFERENCES accounts (id),
    recipient_id  UUID        NOT NULL REFERENCES accounts (id),
    sent_at       TEXT        NOT NULL, -- exactly as signed (ISO-8601 UTC, millis)
    envelope      JSONB       NOT NULL,
    signature     BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    letter_hash   BYTEA       NOT NULL UNIQUE CHECK (octet_length(letter_hash) = 32),
    ledger_seq    BIGINT      NOT NULL UNIQUE REFERENCES ledger_entries (seq),
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX letters_recipient_idx ON letters (recipient_id, ledger_seq);
CREATE INDEX letters_sender_idx ON letters (sender_id, ledger_seq);

-- Sent letters can't be edited or unsent, and the ledger can't be rewritten:
-- enforced by the database, not just by the absence of API endpoints.
CREATE FUNCTION writeproof_forbid_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER letters_append_only
    BEFORE UPDATE OR DELETE ON letters
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER letters_no_truncate
    BEFORE TRUNCATE ON letters
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
