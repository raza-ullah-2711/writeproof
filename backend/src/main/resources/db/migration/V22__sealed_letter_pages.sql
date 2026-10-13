-- Handwritten pages in sealed letters (Task 14b, docs/handwritten-pages.md). The pages travel
-- inside the ciphertext, so the server never sees them; it only knows each page's ink hash, which
-- the sender's wallet signed into the v4 header. That lets it refuse a page that already sealed
-- another letter (sealed or open). The recipient's browser checks the hashes against the pages.
CREATE TABLE letter_pages (
    letter_hash BYTEA    NOT NULL REFERENCES letters (letter_hash),
    page_index  SMALLINT NOT NULL CHECK (page_index BETWEEN 0 AND 3),
    ink_hash    BYTEA    NOT NULL UNIQUE CHECK (octet_length(ink_hash) = 32),
    PRIMARY KEY (letter_hash, page_index)
);

CREATE TRIGGER letter_pages_append_only
    BEFORE UPDATE OR DELETE ON letter_pages
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER letter_pages_no_truncate
    BEFORE TRUNCATE ON letter_pages
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
