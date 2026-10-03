-- Where each published checkpoint is anchored in a public transparency log (Rekor): a DSSE entry
-- whose payload is the checkpoint's signed message, signed by the ledger key. Witnesses check the
-- entry in the log itself (docs/ledger.md, "Anchoring in a public log"). Append-only.
CREATE TABLE ledger_anchors (
    size             BIGINT      PRIMARY KEY, -- a size in ledger_checkpoints
    log_url          TEXT        NOT NULL,
    log_index        BIGINT      NOT NULL CHECK (log_index >= 0),
    entry_uuid       TEXT        NOT NULL CHECK (entry_uuid ~ '^[0-9a-f]{64,80}$'),
    integrated_time  BIGINT      NOT NULL,
    anchored_at      TIMESTAMPTZ NOT NULL
);

CREATE TRIGGER ledger_anchors_append_only
    BEFORE UPDATE OR DELETE ON ledger_anchors
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER ledger_anchors_no_truncate
    BEFORE TRUNCATE ON ledger_anchors
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
