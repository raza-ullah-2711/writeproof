-- Admin side (Task 13a). Admins sign in with their wallet like everyone else; their role comes
-- from this table (granted in the admin area) or from ADMIN_PUBLIC_KEYS (bootstrap admins, always
-- ADMIN while listed). Keyed by public key, so a role can be granted before the account exists.
CREATE TABLE admin_roles (
    public_key  BYTEA       PRIMARY KEY CHECK (octet_length(public_key) = 32),
    role        TEXT        NOT NULL CHECK (role IN ('ADMIN', 'MODERATOR')),
    granted_at  TIMESTAMPTZ NOT NULL,
    granted_by  BYTEA       CHECK (octet_length(granted_by) = 32)
);

-- Every admin action, permanently: who (by key), what, on what, with details. Append-only.
CREATE TABLE admin_audit_log (
    id         BIGSERIAL   PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL,
    actor_key  BYTEA       NOT NULL CHECK (octet_length(actor_key) = 32),
    actor_role TEXT        NOT NULL,
    action     TEXT        NOT NULL,
    target     TEXT,
    detail     JSONB       NOT NULL DEFAULT '{}'
);

CREATE TRIGGER admin_audit_log_append_only
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION writeproof_forbid_mutation();
CREATE TRIGGER admin_audit_log_no_truncate
    BEFORE TRUNCATE ON admin_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION writeproof_forbid_mutation();
