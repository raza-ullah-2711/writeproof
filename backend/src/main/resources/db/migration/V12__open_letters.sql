-- Open letters (Task 11c): signed by hand and wallet and recorded on the ledger like sealed
-- letters, but not sealed. The body is public by the author's explicit choice; anyone with the
-- link (the letter hash) can read and verify it. The handwriting strokes are NOT published, only
-- their hash, as for sealed letters. Immutable, like everything else that reaches the ledger.
CREATE TABLE open_letters (
    letter_hash       BYTEA            PRIMARY KEY CHECK (octet_length(letter_hash) = 32),
    author_id         UUID             NOT NULL REFERENCES accounts (id),
    sent_at           TEXT             NOT NULL, -- exactly as signed (ISO-8601 UTC, millis)
    body              TEXT             NOT NULL CHECK (char_length(body) BETWEEN 1 AND 10000),
    signature         BYTEA            NOT NULL CHECK (octet_length(signature) = 64),
    handwriting_hash  BYTEA            NOT NULL UNIQUE CHECK (octet_length(handwriting_hash) = 32),
    handwriting_score DOUBLE PRECISION NOT NULL CHECK (handwriting_score BETWEEN 0 AND 1),
    ledger_seq        BIGINT           NOT NULL UNIQUE REFERENCES ledger_entries (seq),
    created_at        TIMESTAMPTZ      NOT NULL
);

CREATE INDEX open_letters_author_idx ON open_letters (author_id, ledger_seq);

CREATE TRIGGER open_letters_append_only
    BEFORE UPDATE OR DELETE ON open_letters
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER open_letters_no_truncate
    BEFORE TRUNCATE ON open_letters
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
