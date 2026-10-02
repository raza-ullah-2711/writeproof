-- Handwriting enrolment: the 3-5 reference samples a verification is compared against.
-- Raw stroke dynamics are kept (not derived features) so the matcher can evolve without
-- re-enrolment. This is biometric data; see docs/handwriting-verification.md.
CREATE TABLE handwriting_enrolments (
    account_id  UUID        PRIMARY KEY REFERENCES accounts (id),
    samples     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
