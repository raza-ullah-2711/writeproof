-- Family archive, private entries (Task 14c-1, docs/family-archive.md): photos of old paper
-- letters, sealed end to end to the people the owner chooses. The server holds ciphertext only:
-- encrypted photos (blobs), an encrypted description, and one wrapped content key per reader.
-- Entries are signed by the owner's wallet and recorded on the ledger, so a copy is provably
-- unchanged since it was added. Like letters, entries can't be edited or deleted.

CREATE TABLE archive_entries (
    entry_hash  BYTEA       PRIMARY KEY CHECK (octet_length(entry_hash) = 32),
    owner_id    UUID        NOT NULL REFERENCES accounts (id),
    added_at    TEXT        NOT NULL, -- exactly as signed (ISO-8601 UTC, millis)
    iv          BYTEA       NOT NULL CHECK (octet_length(iv) = 12),
    ciphertext  BYTEA       NOT NULL CHECK (octet_length(ciphertext) BETWEEN 17 AND 65552),
    signature   BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    ledger_seq  BIGINT      NOT NULL UNIQUE REFERENCES ledger_entries (seq),
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX archive_entries_owner_idx ON archive_entries (owner_id, ledger_seq);

-- Who can open an entry: the owner and everyone it is shared with, each with the content key
-- wrapped to their encryption key. Position keeps the signed order.
CREATE TABLE archive_readers (
    entry_hash           BYTEA    NOT NULL REFERENCES archive_entries (entry_hash),
    position             SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 20),
    reader_id            UUID     NOT NULL REFERENCES accounts (id),
    ephemeral_public_key BYTEA    NOT NULL CHECK (octet_length(ephemeral_public_key) = 32),
    iv                   BYTEA    NOT NULL CHECK (octet_length(iv) = 12),
    wrapped_key          BYTEA    NOT NULL CHECK (octet_length(wrapped_key) = 48),
    PRIMARY KEY (entry_hash, position),
    UNIQUE (entry_hash, reader_id)
);

CREATE INDEX archive_readers_reader_idx ON archive_readers (reader_id);

-- Encrypted photos (iv || ciphertext), named by their SHA-256. Uploaded first, then attached to
-- the entry that signs their hashes. An unattached upload is the only thing that may be deleted
-- (abandoned uploads are cleaned up); attaching happens once and is final.
CREATE TABLE archive_blobs (
    blob_hash  BYTEA       PRIMARY KEY CHECK (octet_length(blob_hash) = 32),
    owner_id   UUID        NOT NULL REFERENCES accounts (id),
    bytes      BYTEA       NOT NULL,
    size       INTEGER     NOT NULL CHECK (size = octet_length(bytes)),
    created_at TIMESTAMPTZ NOT NULL,
    entry_hash BYTEA       REFERENCES archive_entries (entry_hash),
    position   SMALLINT    CHECK (position BETWEEN 0 AND 9),
    CHECK ((entry_hash IS NULL) = (position IS NULL)),
    UNIQUE (entry_hash, position)
);

CREATE INDEX archive_blobs_owner_idx ON archive_blobs (owner_id) WHERE entry_hash IS NULL;

CREATE TRIGGER archive_entries_append_only
    BEFORE UPDATE OR DELETE ON archive_entries
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER archive_entries_no_truncate
    BEFORE TRUNCATE ON archive_entries
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER archive_readers_append_only
    BEFORE UPDATE OR DELETE ON archive_readers
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER archive_readers_no_truncate
    BEFORE TRUNCATE ON archive_readers
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();

CREATE FUNCTION writeproof_archive_blob_rules() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.entry_hash IS NULL THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'archive_blobs: an attached photo can never be deleted';
    END IF;
    IF OLD.entry_hash IS NULL AND NEW.entry_hash IS NOT NULL
       AND (NEW.blob_hash, NEW.owner_id, NEW.bytes, NEW.size, NEW.created_at)
           IS NOT DISTINCT FROM (OLD.blob_hash, OLD.owner_id, OLD.bytes, OLD.size, OLD.created_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'archive_blobs is append-only (a photo can only be attached, once)';
END;
$$;

CREATE TRIGGER archive_blobs_rules
    BEFORE UPDATE OR DELETE ON archive_blobs
    FOR EACH ROW EXECUTE FUNCTION writeproof_archive_blob_rules();
CREATE TRIGGER archive_blobs_no_truncate
    BEFORE TRUNCATE ON archive_blobs
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
