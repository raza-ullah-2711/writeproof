-- Threads (Task 11b). A reply's signed header (writeproof/letter/v3) commits to the hash of the
-- letter it answers, so the server can't re-thread letters. thread_id is the hash of the first
-- letter in the thread; it is set only on replies (a letter that starts a thread is its own
-- thread, COALESCE(thread_id, letter_hash)), so existing letters need no backfill, which the
-- append-only trigger would forbid anyway.
ALTER TABLE letters
    ADD COLUMN in_reply_to BYTEA REFERENCES letters (letter_hash),
    ADD COLUMN thread_id   BYTEA CHECK (octet_length(thread_id) = 32),
    ADD CONSTRAINT letters_reply_has_thread CHECK ((in_reply_to IS NULL) = (thread_id IS NULL));

CREATE INDEX letters_thread_idx ON letters ((COALESCE(thread_id, letter_hash)), ledger_seq);
