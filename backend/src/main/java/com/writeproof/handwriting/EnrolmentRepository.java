package com.writeproof.handwriting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EnrolmentRepository {

    record Enrolment(UUID accountId, List<HandwritingSample> samples, Instant createdAt) {}

    private static final TypeReference<List<HandwritingSample>> SAMPLES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final BiometricCipher cipher;

    EnrolmentRepository(JdbcClient jdbc, ObjectMapper json, BiometricCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    /** Removes the account's enrolment; returns whether there was one. */
    boolean delete(UUID accountId) {
        return jdbc.sql("DELETE FROM handwriting_enrolments WHERE account_id = :id").param("id", accountId).update() == 1;
    }

    /** Returns {@code false} if the account is already enrolled. */
    boolean insertIfAbsent(Enrolment enrolment) {
        return jdbc.sql("""
                INSERT INTO handwriting_enrolments (account_id, samples_encrypted, created_at)
                VALUES (:accountId, :samples, :createdAt)
                ON CONFLICT (account_id) DO NOTHING
                """)
                .param("accountId", enrolment.accountId())
                .param("samples", cipher.encrypt(write(enrolment.samples()),
                        BiometricCipher.enrolmentContext(enrolment.accountId())))
                .param("createdAt", OffsetDateTime.ofInstant(enrolment.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    Optional<Enrolment> find(UUID accountId) {
        return jdbc.sql("SELECT account_id, samples_encrypted, created_at FROM handwriting_enrolments WHERE account_id = :id")
                .param("id", accountId)
                .query((rs, row) -> new Enrolment(
                        rs.getObject("account_id", UUID.class),
                        read(cipher.decrypt(rs.getBytes("samples_encrypted"), BiometricCipher.enrolmentContext(accountId))),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private String write(List<HandwritingSample> samples) {
        try {
            return json.writeValueAsString(samples);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<HandwritingSample> read(String value) {
        try {
            return json.readValue(value, SAMPLES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored enrolment is unreadable", e);
        }
    }
}
