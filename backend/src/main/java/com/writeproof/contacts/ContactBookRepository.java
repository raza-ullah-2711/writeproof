package com.writeproof.contacts;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ContactBookRepository {

    record StoredBook(long version, byte[] ciphertext, Instant updatedAt) {}

    private final JdbcClient jdbc;

    ContactBookRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredBook> find(UUID accountId) {
        return jdbc.sql("SELECT version, ciphertext, updated_at FROM contact_books WHERE account_id = :account")
                .param("account", accountId)
                .query((rs, row) -> new StoredBook(rs.getLong("version"), rs.getBytes("ciphertext"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Writes version {@code baseVersion + 1}, but only if the stored book is still at
     * {@code baseVersion} (0: there is none yet). Returns {@code false} on a lost race.
     */
    boolean write(UUID accountId, long baseVersion, byte[] ciphertext, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        if (baseVersion == 0) {
            return jdbc.sql("""
                    INSERT INTO contact_books (account_id, version, ciphertext, updated_at)
                    VALUES (:account, 1, :ciphertext, :at)
                    ON CONFLICT (account_id) DO NOTHING
                    """)
                    .param("account", accountId).param("ciphertext", ciphertext).param("at", at)
                    .update() == 1;
        }
        return jdbc.sql("""
                UPDATE contact_books SET version = version + 1, ciphertext = :ciphertext, updated_at = :at
                 WHERE account_id = :account AND version = :base
                """)
                .param("account", accountId).param("ciphertext", ciphertext).param("at", at)
                .param("base", baseVersion)
                .update() == 1;
    }
}
