package com.writeproof.backup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WalletBackupRepository {

    record StoredBackup(UUID accountId, WalletBackupBlob blob, Instant createdAt) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    WalletBackupRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Stores the account's backup, replacing any previous one (whose recovery code then stops
     * working). Returns {@code false} if the lookup id already belongs to another account.
     */
    boolean upsert(UUID accountId, byte[] lookupId, WalletBackupBlob blob, Instant createdAt) {
        try {
            jdbc.sql("""
                    INSERT INTO wallet_backups (account_id, lookup_id, blob, created_at)
                    VALUES (:account, :lookup, CAST(:blob AS jsonb), :createdAt)
                    ON CONFLICT (account_id) DO UPDATE
                       SET lookup_id = EXCLUDED.lookup_id, blob = EXCLUDED.blob, created_at = EXCLUDED.created_at
                    """)
                    .param("account", accountId)
                    .param("lookup", lookupId)
                    .param("blob", write(blob))
                    .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    Optional<StoredBackup> findByLookupId(byte[] lookupId) {
        return find("lookup_id = :key", lookupId);
    }

    Optional<StoredBackup> findByAccount(UUID accountId) {
        return find("account_id = :key", accountId);
    }

    private Optional<StoredBackup> find(String where, Object key) {
        return jdbc.sql("SELECT account_id, blob::text AS blob, created_at FROM wallet_backups WHERE " + where)
                .param("key", key)
                .query((rs, row) -> new StoredBackup(
                        rs.getObject("account_id", UUID.class),
                        read(rs.getString("blob")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private String write(WalletBackupBlob blob) {
        try {
            return json.writeValueAsString(blob);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private WalletBackupBlob read(String value) {
        try {
            return json.readValue(value, WalletBackupBlob.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored backup is unreadable", e);
        }
    }
}
