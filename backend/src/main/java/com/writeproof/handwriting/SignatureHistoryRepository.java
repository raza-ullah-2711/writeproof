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

    SignatureHistoryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<HandwritingSample> recent(UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT sample::text FROM handwriting_history
                 WHERE account_id = :id ORDER BY id DESC LIMIT :limit
                """)
                .param("id", accountId)
                .param("limit", limit)
                .query(String.class)
                .list()
                .stream()
                .map(this::read)
                .toList();
    }

    void add(UUID accountId, HandwritingSample sample, Instant at, int keep) {
        jdbc.sql("""
                INSERT INTO handwriting_history (account_id, sample, created_at)
                VALUES (:id, CAST(:sample AS jsonb), :at)
                """)
                .param("id", accountId)
                .param("sample", write(sample))
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
