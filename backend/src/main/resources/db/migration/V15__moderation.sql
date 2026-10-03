-- Moderation of open letters (Task 13c).

-- Reports from readers (signed in or not). A signed-in reader can report a letter once.
CREATE TABLE open_letter_reports (
    id            BIGSERIAL   PRIMARY KEY,
    letter_hash   BYTEA       NOT NULL REFERENCES open_letters (letter_hash),
    reporter_id   UUID        REFERENCES accounts (id),
    category      TEXT        NOT NULL CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other')),
    note          TEXT        CHECK (char_length(note) <= 500),
    created_at    TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ,
    resolution    TEXT        CHECK (resolution IN ('dismissed', 'removed')),
    CHECK ((resolved_at IS NULL) = (resolution IS NULL))
);
CREATE UNIQUE INDEX open_letter_reports_once ON open_letter_reports (letter_hash, reporter_id)
    WHERE reporter_id IS NOT NULL;
CREATE INDEX open_letter_reports_open ON open_letter_reports (letter_hash) WHERE resolved_at IS NULL;

-- A takedown: the letter's text is deleted for good; its hash stays on the ledger and here.
CREATE TABLE open_letter_removals (
    letter_hash BYTEA       PRIMARY KEY REFERENCES open_letters (letter_hash),
    category    TEXT        NOT NULL CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other')),
    removed_at  TIMESTAMPTZ NOT NULL,
    removed_by  BYTEA       NOT NULL CHECK (octet_length(removed_by) = 32)
);
CREATE TRIGGER open_letter_removals_append_only
    BEFORE UPDATE OR DELETE ON open_letter_removals
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER open_letter_removals_no_truncate
    BEFORE TRUNCATE ON open_letter_removals
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();

-- Open letters stay append-only with exactly one exception: blanking the body of a letter that
-- has a removal record. Nothing else in the row may change, and nothing can be deleted.
ALTER TABLE open_letters ALTER COLUMN body DROP NOT NULL;

CREATE FUNCTION writeproof_open_letter_takedown_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.body IS NOT NULL AND NEW.body IS NULL
       AND (NEW.letter_hash, NEW.author_id, NEW.sent_at, NEW.signature, NEW.handwriting_hash,
            NEW.handwriting_score, NEW.ledger_seq, NEW.created_at)
           IS NOT DISTINCT FROM
           (OLD.letter_hash, OLD.author_id, OLD.sent_at, OLD.signature, OLD.handwriting_hash,
            OLD.handwriting_score, OLD.ledger_seq, OLD.created_at)
       AND EXISTS (SELECT 1 FROM open_letter_removals r WHERE r.letter_hash = OLD.letter_hash) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'open_letters is append-only (only a recorded takedown may remove a body)';
END;
$$;

DROP TRIGGER open_letters_append_only ON open_letters;
CREATE TRIGGER open_letters_takedown_only
    BEFORE UPDATE ON open_letters
    FOR EACH ROW EXECUTE FUNCTION writeproof_open_letter_takedown_only();
CREATE TRIGGER open_letters_no_delete
    BEFORE DELETE ON open_letters
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
