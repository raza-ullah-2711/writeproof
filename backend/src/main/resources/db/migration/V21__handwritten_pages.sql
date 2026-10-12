-- Handwritten pages (Task 14a, docs/handwritten-pages.md). An open letter v2 carries up to 4 pages
-- of handwriting; the body becomes an optional transcript. Pages are as immutable as the letter:
-- the only permitted change is blanking them when the letter's body is blanked by a recorded
-- takedown or withdrawal.

ALTER TABLE open_letters
    ADD COLUMN version SMALLINT NOT NULL DEFAULT 1 CHECK (version IN (1, 2)),
    ADD COLUMN replay  BOOLEAN  NOT NULL DEFAULT false CHECK (replay = false OR version = 2);

-- A v2 letter's transcript may be empty; a v1 letter still needs text.
ALTER TABLE open_letters DROP CONSTRAINT open_letters_body_check;
ALTER TABLE open_letters ADD CONSTRAINT open_letters_body_check
    CHECK (body IS NULL OR char_length(body) BETWEEN (CASE WHEN version = 2 THEN 0 ELSE 1 END) AND 10000);

CREATE TABLE open_letter_pages (
    letter_hash  BYTEA    NOT NULL REFERENCES open_letters (letter_hash),
    page_index   SMALLINT NOT NULL CHECK (page_index BETWEEN 0 AND 3),
    -- The page without its timing (format, device, pen, ink), as JSON; null once blanked.
    page_json    TEXT,
    -- One copy of a page seals one letter.
    ink_hash     BYTEA    NOT NULL UNIQUE CHECK (octet_length(ink_hash) = 32),
    timing_hash  BYTEA    NOT NULL CHECK (octet_length(timing_hash) = 32),
    -- {capturedAt, timing} only when the author opted in to replay; never stored otherwise.
    timing_json  TEXT,
    PRIMARY KEY (letter_hash, page_index)
);

-- The open_letters takedown rule, now also keeping the new columns unchanged.
CREATE OR REPLACE FUNCTION writeproof_open_letter_takedown_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.body IS NOT NULL AND NEW.body IS NULL
       AND (NEW.letter_hash, NEW.author_id, NEW.sent_at, NEW.signature, NEW.handwriting_hash,
            NEW.handwriting_score, NEW.ledger_seq, NEW.created_at, NEW.version, NEW.replay)
           IS NOT DISTINCT FROM
           (OLD.letter_hash, OLD.author_id, OLD.sent_at, OLD.signature, OLD.handwriting_hash,
            OLD.handwriting_score, OLD.ledger_seq, OLD.created_at, OLD.version, OLD.replay)
       AND EXISTS (SELECT 1 FROM open_letter_removals r WHERE r.letter_hash = OLD.letter_hash) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'open_letters is append-only (only a recorded takedown may remove a body)';
END;
$$;

-- Pages change only by being blanked, and only for a letter with a removal record.
CREATE FUNCTION writeproof_open_letter_page_takedown_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.page_json IS NULL AND NEW.timing_json IS NULL
       AND (NEW.letter_hash, NEW.page_index, NEW.ink_hash, NEW.timing_hash)
           IS NOT DISTINCT FROM (OLD.letter_hash, OLD.page_index, OLD.ink_hash, OLD.timing_hash)
       AND EXISTS (SELECT 1 FROM open_letter_removals r WHERE r.letter_hash = OLD.letter_hash) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'open_letter_pages is append-only (only a recorded takedown may blank a page)';
END;
$$;

CREATE TRIGGER open_letter_pages_takedown_only
    BEFORE UPDATE ON open_letter_pages
    FOR EACH ROW EXECUTE FUNCTION writeproof_open_letter_page_takedown_only();
CREATE TRIGGER open_letter_pages_no_delete
    BEFORE DELETE ON open_letter_pages
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER open_letter_pages_no_truncate
    BEFORE TRUNCATE ON open_letter_pages
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();

-- Whatever blanks a letter's body (a final takedown, a withdrawal on account deletion) blanks its
-- pages in the same statement, so no code path can leave the ink behind.
CREATE FUNCTION writeproof_blank_open_letter_pages() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE open_letter_pages SET page_json = NULL, timing_json = NULL
     WHERE letter_hash = NEW.letter_hash AND (page_json IS NOT NULL OR timing_json IS NOT NULL);
    RETURN NULL;
END;
$$;

CREATE TRIGGER open_letters_blank_pages
    AFTER UPDATE OF body ON open_letters
    FOR EACH ROW WHEN (OLD.body IS NOT NULL AND NEW.body IS NULL)
    EXECUTE FUNCTION writeproof_blank_open_letter_pages();

-- Child-safety preservation keeps the pages too (encrypted like the body).
ALTER TABLE preserved_content ADD COLUMN pages_encrypted BYTEA;
