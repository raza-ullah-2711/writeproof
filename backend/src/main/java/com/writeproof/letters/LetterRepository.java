package com.writeproof.letters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.ledger.LedgerEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert and read only: letters are immutable (and the database enforces it). */
@Repository
class LetterRepository {

    private static final String SELECT = """
            SELECT l.id, l.sender_id, s.public_key AS sender_key, l.recipient_id, r.public_key AS recipient_key,
                   l.sent_at, l.envelope::text AS envelope, l.signature, l.letter_hash,
                   e.seq, e.prev_hash, e.payload_hash, e.recorded_at, e.entry_hash
              FROM letters l
              JOIN accounts s ON s.id = l.sender_id
              JOIN accounts r ON r.id = l.recipient_id
              JOIN ledger_entries e ON e.seq = l.ledger_seq
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    LetterRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(UUID id, UUID senderId, UUID recipientId, String sentAt, LetterEnvelope envelope, byte[] signature,
                byte[] letterHash, long ledgerSeq, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO letters (id, sender_id, recipient_id, sent_at, envelope, signature, letter_hash,
                                     ledger_seq, created_at)
                VALUES (:id, :sender, :recipient, :sentAt, CAST(:envelope AS jsonb), :signature, :hash, :seq, :createdAt)
                """)
                .param("id", id)
                .param("sender", senderId)
                .param("recipient", recipientId)
                .param("sentAt", sentAt)
                .param("envelope", write(envelope))
                .param("signature", signature)
                .param("hash", letterHash)
                .param("seq", ledgerSeq)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update();
    }

    boolean existsByHash(byte[] letterHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM letters WHERE letter_hash = :hash)")
                .param("hash", letterHash)
                .query(Boolean.class)
                .single();
    }

    Optional<Letter> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE l.id = :id").param("id", id).query(this::map).optional();
    }

    List<Letter> inbox(UUID recipientId) {
        return jdbc.sql(SELECT + " WHERE l.recipient_id = :id ORDER BY l.ledger_seq DESC")
                .param("id", recipientId).query(this::map).list();
    }

    List<Letter> sent(UUID senderId) {
        return jdbc.sql(SELECT + " WHERE l.sender_id = :id ORDER BY l.ledger_seq DESC")
                .param("id", senderId).query(this::map).list();
    }

    private Letter map(ResultSet rs, int row) throws SQLException {
        return new Letter(
                rs.getObject("id", UUID.class),
                rs.getObject("sender_id", UUID.class),
                rs.getBytes("sender_key"),
                rs.getObject("recipient_id", UUID.class),
                rs.getBytes("recipient_key"),
                rs.getString("sent_at"),
                read(rs.getString("envelope")),
                rs.getBytes("signature"),
                rs.getBytes("letter_hash"),
                new LedgerEntry(
                        rs.getLong("seq"),
                        rs.getBytes("prev_hash"),
                        rs.getBytes("payload_hash"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getBytes("entry_hash")));
    }

    private String write(LetterEnvelope envelope) {
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private LetterEnvelope read(String value) {
        try {
            return json.readValue(value, LetterEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored envelope is unreadable", e);
        }
    }
}
