-- Hand-signed letters (v2): each letter commits to the SHA-256 of the handwriting sample that
-- signed it. The strokes themselves travel sealed inside the letter; the server keeps only the
-- hash and the similarity score it measured. Letters sent before this migration have NULLs (v1).
ALTER TABLE letters
    ADD COLUMN handwriting_hash  BYTEA UNIQUE CHECK (octet_length(handwriting_hash) = 32),
    ADD COLUMN handwriting_score DOUBLE PRECISION CHECK (handwriting_score BETWEEN 0 AND 1);

-- Recent letter signatures per account, so a signature lifted from an old letter (anyone holding
-- the device can decrypt sent letters) and resubmitted is caught as a replay. Bounded: only the
-- newest few are kept. Unlike letters, this is ordinary (deletable) personal data.
CREATE TABLE handwriting_history (
    id          BIGSERIAL   PRIMARY KEY,
    account_id  UUID        NOT NULL REFERENCES accounts (id),
    sample      JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX handwriting_history_account_idx ON handwriting_history (account_id, id DESC);
