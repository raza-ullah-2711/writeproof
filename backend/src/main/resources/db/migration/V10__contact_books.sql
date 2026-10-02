-- Each account's contact book (petnames for addresses), encrypted in the browser with a key
-- derived from the wallet. The server stores ciphertext only and never learns who anyone's
-- contacts are. `version` increases by one on every write; clients send the version they edited
-- so concurrent edits from two devices can't silently overwrite each other.
CREATE TABLE contact_books (
    account_id  UUID        PRIMARY KEY REFERENCES accounts (id),
    version     BIGINT      NOT NULL CHECK (version >= 1),
    ciphertext  BYTEA       NOT NULL CHECK (octet_length(ciphertext) BETWEEN 28 AND 262172),
    updated_at  TIMESTAMPTZ NOT NULL
);
