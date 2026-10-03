package com.writeproof.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, public_key, created_at, encryption_key, encryption_key_signature, deleted_at";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the account; returns {@code false} if the public key is already registered. */
    public boolean insertIfAbsent(Account account) {
        int rows = jdbc.sql("""
                INSERT INTO accounts (id, public_key, created_at)
                VALUES (:id, :publicKey, :createdAt)
                ON CONFLICT (public_key) DO NOTHING
                """)
                .param("id", account.id())
                .param("publicKey", account.publicKey())
                .param("createdAt", OffsetDateTime.ofInstant(account.createdAt(), ZoneOffset.UTC))
                .update();
        return rows == 1;
    }

    /** Sets the encryption key once; returns {@code false} if the account already has one. */
    public boolean setEncryptionKeyIfAbsent(UUID id, byte[] encryptionKey, byte[] signature) {
        return jdbc.sql("""
                UPDATE accounts SET encryption_key = :key, encryption_key_signature = :signature
                 WHERE id = :id AND encryption_key IS NULL
                """)
                .param("id", id)
                .param("key", encryptionKey)
                .param("signature", signature)
                .update() == 1;
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    public Optional<Account> findByPublicKey(byte[] publicKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE public_key = :publicKey")
                .param("publicKey", publicKey)
                .query(AccountRepository::map)
                .optional();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getBytes("public_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getBytes("encryption_key"),
                rs.getBytes("encryption_key_signature"),
                rs.getObject("deleted_at", OffsetDateTime.class) == null ? null
                        : rs.getObject("deleted_at", OffsetDateTime.class).toInstant());
    }

    /** The deleted accounts among {@code ids}, in one query (to label correspondents). */
    public java.util.Set<UUID> deletedAmong(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return java.util.Set.of();
        }
        return java.util.Set.copyOf(jdbc.sql("SELECT id FROM accounts WHERE deleted_at IS NOT NULL AND id IN (:ids)")
                .param("ids", ids).query(UUID.class).list());
    }
}
