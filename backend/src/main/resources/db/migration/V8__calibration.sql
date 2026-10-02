-- Opt-in calibration data. Contributors write an assigned made-up "practice name" (never their
-- real signature) and imitate other contributors' practice names, so the matcher's accuracy can
-- be measured on real hands. Samples are filed under a pseudonymous contributor id and encrypted
-- with the handwriting data key. Withdrawing consent deletes everything (ON DELETE CASCADE).
CREATE TABLE calibration_contributors (
    contributor_id UUID        PRIMARY KEY,
    account_id     UUID        NOT NULL UNIQUE REFERENCES accounts (id),
    practice_name  TEXT        NOT NULL,
    consented_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE calibration_samples (
    id                    BIGSERIAL   PRIMARY KEY,
    contributor_id        UUID        NOT NULL REFERENCES calibration_contributors (contributor_id) ON DELETE CASCADE,
    kind                  TEXT        NOT NULL CHECK (kind IN ('genuine', 'forgery')),
    -- For forgeries: whose practice name was imitated.
    target_contributor_id UUID        REFERENCES calibration_contributors (contributor_id) ON DELETE CASCADE,
    device                TEXT        NOT NULL CHECK (device IN ('pen', 'touch', 'mouse')),
    sample_encrypted      BYTEA       NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    CHECK ((kind = 'forgery') = (target_contributor_id IS NOT NULL))
);

CREATE INDEX calibration_samples_contributor_idx ON calibration_samples (contributor_id, kind);
