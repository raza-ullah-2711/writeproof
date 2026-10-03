-- Account deletion, "close and forget" (docs/launch-policies.md). A deleted account keeps its row,
-- because immutable letters reference it, but it can't sign in, register again or receive letters.
-- Everything else the server can delete is deleted (AccountDeletionService).
ALTER TABLE accounts ADD COLUMN deleted_at TIMESTAMPTZ;

-- An author's open letters are withdrawn on deletion: the same blanking as a takedown, recorded
-- as a removal of kind 'withdrawn' by the author's own key.
ALTER TABLE open_letter_removals DROP CONSTRAINT open_letter_removals_category_check;
ALTER TABLE open_letter_removals ADD CONSTRAINT open_letter_removals_category_check
    CHECK (category IN ('spam', 'harassment', 'illegal', 'impersonation', 'other', 'withdrawn'));
