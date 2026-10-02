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

    public Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT id, public_key, created_at FROM accounts WHERE id = :id")
                .param("id", id)
                .query(AccountRepository::map)
                .optional();
    }

    public Optional<Account> findByPublicKey(byte[] publicKey) {
        return jdbc.sql("SELECT id, public_key, created_at FROM accounts WHERE public_key = :publicKey")
                .param("publicKey", publicKey)
                .query(AccountRepository::map)
                .optional();
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getBytes("public_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
