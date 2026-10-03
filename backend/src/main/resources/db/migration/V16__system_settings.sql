-- System controls (Task 13d): switches admins flip at runtime. One row per setting; every change
-- is also written to admin_audit_log with the old and new value.
CREATE TABLE system_settings (
    key        TEXT        PRIMARY KEY CHECK (key IN ('registration_open', 'sending_enabled',
                                                      'open_letters_enabled', 'announcement')),
    value      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BYTEA       CHECK (octet_length(updated_by) = 32)
);

INSERT INTO system_settings (key, value, updated_at) VALUES
    ('registration_open', 'true', now()),
    ('sending_enabled', 'true', now()),
    ('open_letters_enabled', 'true', now()),
    ('announcement', '""', now());
