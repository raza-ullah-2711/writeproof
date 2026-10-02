package com.writeproof.handwriting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The newest signatures that sealed an account's letters, for replay detection. */
@Repository
class SignatureHistoryRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final BiometricCipher cipher;

    SignatureHistoryRepository(JdbcClient jdbc, ObjectMapper json, BiometricCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    void deleteAll(UUID accountId) {
        jdbc.sql("DELETE FROM handwriting_history WHERE account_id = :id").param("id", accountId).update();
    }

    List<HandwritingSample> recent(UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT sample_encrypted FROM handwriting_history
                 WHERE account_id = :id ORDER BY id DESC LIMIT :limit
                """)
                .param("id", accountId)
                .param("limit", limit)
                .query((rs, row) -> read(cipher.decrypt(rs.getBytes(1), BiometricCipher.historyContext(accountId))))
                .list();
    }

    void add(UUID accountId, HandwritingSample sample, Instant at, int keep) {
        jdbc.sql("""
                INSERT INTO handwriting_history (account_id, sample_encrypted, created_at)
                VALUES (:id, :sample, :at)
                """)
                .param("id", accountId)
                .param("sample", cipher.encrypt(write(sample), BiometricCipher.historyContext(accountId)))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                DELETE FROM handwriting_history
                 WHERE account_id = :id
                   AND id NOT IN (SELECT id FROM handwriting_history WHERE account_id = :id ORDER BY id DESC LIMIT :keep)
                """)
                .param("id", accountId)
                .param("keep", keep)
                .update();
    }

    private String write(HandwritingSample sample) {
        try {
            return json.writeValueAsString(sample);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HandwritingSample read(String value) {
        try {
            return json.readValue(value, HandwritingSample.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored signature is unreadable", e);
        }
    }
}
