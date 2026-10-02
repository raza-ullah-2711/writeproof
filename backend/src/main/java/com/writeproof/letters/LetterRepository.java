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
                   l.handwriting_hash, l.handwriting_score, l.in_reply_to,
                   COALESCE(l.thread_id, l.letter_hash) AS thread_id,
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

    /** A conversation as seen by one of its two parties. */
    record ThreadSummary(byte[] threadId, UUID counterpartId, byte[] counterpartKey, int letterCount,
                         long latestSeq, String latestSentAt) {}

    /** {@code inReplyTo} and {@code threadId} are null for a letter that starts a thread. */
    void insert(UUID id, UUID senderId, UUID recipientId, String sentAt, LetterEnvelope envelope, byte[] signature,
                byte[] letterHash, long ledgerSeq, Instant createdAt, byte[] handwritingHash, double handwritingScore,
                byte[] inReplyTo, byte[] threadId) {
        jdbc.sql("""
                INSERT INTO letters (id, sender_id, recipient_id, sent_at, envelope, signature, letter_hash,
                                     ledger_seq, created_at, handwriting_hash, handwriting_score, in_reply_to, thread_id)
                VALUES (:id, :sender, :recipient, :sentAt, CAST(:envelope AS jsonb), :signature, :hash, :seq, :createdAt,
                        :handwritingHash, :handwritingScore, :inReplyTo, :threadId)
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
                .param("handwritingHash", handwritingHash)
                .param("handwritingScore", handwritingScore)
                .param("inReplyTo", inReplyTo)
                .param("threadId", threadId)
                .update();
    }

    /** One signature seals one letter, sealed or open. */
    boolean existsByHandwritingHash(byte[] handwritingHash) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM letters WHERE handwriting_hash = :hash)
                    OR EXISTS (SELECT 1 FROM open_letters WHERE handwriting_hash = :hash)
                """)
                .param("hash", handwritingHash)
                .query(Boolean.class)
                .single();
    }

    boolean existsByHash(byte[] letterHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM letters WHERE letter_hash = :hash)")
                .param("hash", letterHash)
                .query(Boolean.class)
                .single();
    }

    Optional<Letter> findByHash(byte[] letterHash) {
        return jdbc.sql(SELECT + " WHERE l.letter_hash = :hash").param("hash", letterHash).query(this::map).optional();
    }

    List<ThreadSummary> threads(UUID accountId) {
        return jdbc.sql("""
                SELECT t.thread, t.counterpart, a.public_key, count(*) AS letters, max(t.ledger_seq) AS latest_seq,
                       (array_agg(t.sent_at ORDER BY t.ledger_seq DESC))[1] AS latest_sent_at
                  FROM (SELECT COALESCE(thread_id, letter_hash) AS thread, ledger_seq, sent_at,
                               CASE WHEN sender_id = :id THEN recipient_id ELSE sender_id END AS counterpart
                          FROM letters
                         WHERE sender_id = :id OR recipient_id = :id) t
                  JOIN accounts a ON a.id = t.counterpart
                 GROUP BY t.thread, t.counterpart, a.public_key
                 ORDER BY latest_seq DESC
                """)
                .param("id", accountId)
                .query((rs, row) -> new ThreadSummary(rs.getBytes("thread"), rs.getObject("counterpart", UUID.class),
                        rs.getBytes("public_key"), rs.getInt("letters"), rs.getLong("latest_seq"),
                        rs.getString("latest_sent_at")))
                .list();
    }

    List<Letter> thread(UUID accountId, byte[] threadId) {
        return jdbc.sql(SELECT + """
                 WHERE COALESCE(l.thread_id, l.letter_hash) = :thread
                   AND (l.sender_id = :id OR l.recipient_id = :id)
                 ORDER BY l.ledger_seq
                """)
                .param("thread", threadId).param("id", accountId).query(this::map).list();
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
                        rs.getBytes("entry_hash")),
                rs.getBytes("handwriting_hash"),
                rs.getObject("handwriting_score", Double.class),
                rs.getBytes("in_reply_to"),
                rs.getBytes("thread_id"));
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
