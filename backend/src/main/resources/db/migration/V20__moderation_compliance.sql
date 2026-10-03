-- Moderation compliance (Task 7b, docs/launch-policies.md T1, T4, T6).

-- Two more categories: child sexual abuse or exploitation, and copyright infringement.
ALTER TABLE open_letter_reports DROP CONSTRAINT open_letter_reports_category_check;
ALTER TABLE open_letter_reports ADD CONSTRAINT open_letter_reports_category_check
    CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other', 'child_safety', 'copyright'));
ALTER TABLE open_letter_removals DROP CONSTRAINT open_letter_removals_category_check;
ALTER TABLE open_letter_removals ADD CONSTRAINT open_letter_removals_category_check
    CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other', 'child_safety', 'copyright',
                        'withdrawn'));

-- A removal is first a hold: the letter is hidden but its text kept, so the author can appeal.
-- Unless restored, the text is deleted when the hold ends (an open_letter_removals row then
-- blanks it, as before): after delete_after with no appeal, or when a moderator upholds an appeal.
CREATE TABLE open_letter_holds (
    id           BIGSERIAL   PRIMARY KEY,
    letter_hash  BYTEA       NOT NULL REFERENCES open_letters (letter_hash),
    category     TEXT        NOT NULL CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other',
                                                          'copyright')),
    held_at      TIMESTAMPTZ NOT NULL,
    held_by      BYTEA       NOT NULL CHECK (octet_length(held_by) = 32),
    delete_after TIMESTAMPTZ NOT NULL,
    appeal       TEXT        CHECK (char_length(appeal) BETWEEN 1 AND 1000),
    appealed_at  TIMESTAMPTZ,
    decision     TEXT        CHECK (decision IN ('restored', 'upheld', 'expired')),
    decided_at   TIMESTAMPTZ,
    CHECK ((appeal IS NULL) = (appealed_at IS NULL)),
    CHECK ((decision IS NULL) = (decided_at IS NULL))
);
-- At most one active hold per letter.
CREATE UNIQUE INDEX open_letter_holds_active ON open_letter_holds (letter_hash) WHERE decision IS NULL;

-- Child sexual abuse or exploitation: a copy preserved for law enforcement before the text is
-- removed (18 U.S.C. 2258A as amended by the REPORT Act: 1 year). Encrypted at rest like
-- handwriting; readable by admins only, and every read is audited. Purged after preserve_until.
CREATE TABLE preserved_content (
    letter_hash     BYTEA       PRIMARY KEY,
    author_id       UUID        NOT NULL REFERENCES accounts (id),
    author_key      BYTEA       NOT NULL CHECK (octet_length(author_key) = 32),
    sent_at         TEXT        NOT NULL,
    body_encrypted  BYTEA       NOT NULL,
    signature       BYTEA       NOT NULL,
    ledger_seq      BIGINT      NOT NULL,
    preserved_at    TIMESTAMPTZ NOT NULL,
    preserve_until  TIMESTAMPTZ NOT NULL,
    report_id       TEXT        CHECK (char_length(report_id) BETWEEN 1 AND 100),
    reported_at     TIMESTAMPTZ
);
