package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.BiometricCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Child sexual abuse or exploitation removed from an open letter, preserved for law enforcement:
 * US providers must report it to NCMEC and preserve it for a year (18 U.S.C. 2258A, as amended by
 * the REPORT Act). The copy is encrypted at rest under HANDWRITING_DATA_KEY, readable by admins
 * only (every read audited), and purged when {@link #PRESERVE} has passed. See
 * docs/launch-policies.md, T1.
 */
@Service
public class PreservedContent {

    static final Duration PRESERVE = Duration.ofDays(365);

    /** What an admin sees in the list: metadata only, never the text. */
    public record Summary(String letterHash, String author, String sentAt, long ledgerSeq, Instant preservedAt,
                          Instant preserveUntil, String reportId, Instant reportedAt) {}

    public record Copy(Summary summary, String body, String signature) {}

    private static final Logger log = LoggerFactory.getLogger(PreservedContent.class);

    private final JdbcClient jdbc;
    private final BiometricCipher cipher;
    private final AuditLog audit;
    private final Clock clock;

    PreservedContent(JdbcClient jdbc, BiometricCipher cipher, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    /** Copies the letter as it is now, before its text is removed. */
    void preserve(byte[] letterHash) {
        Map<String, Object> row = jdbc.sql("""
                SELECT o.author_id, a.public_key, o.sent_at, o.body, o.signature, o.ledger_seq
                  FROM open_letters o JOIN accounts a ON a.id = o.author_id WHERE o.letter_hash = :hash
                """)
                .param("hash", letterHash).query().singleRow();
        String body = (String) row.get("body");
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter's text is already gone");
        }
        UUID author = (UUID) row.get("author_id");
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO preserved_content (letter_hash, author_id, author_key, sent_at, body_encrypted, signature,
                                               ledger_seq, preserved_at, preserve_until)
                VALUES (:hash, :author, :key, :sentAt, :body, :signature, :seq, :at, :until)
                """)
                .param("hash", letterHash).param("author", author).param("key", row.get("public_key"))
                .param("sentAt", row.get("sent_at"))
                .param("body", cipher.encrypt(body, BiometricCipher.preservationContext(author)))
                .param("signature", row.get("signature")).param("seq", row.get("ledger_seq"))
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("until", OffsetDateTime.ofInstant(now.plus(PRESERVE), ZoneOffset.UTC))
                .update();
    }

    public List<Summary> list() {
        return jdbc.sql(SUMMARY + " ORDER BY preserved_at DESC").query(PreservedContent::summary).list();
    }

    /** The preserved text, for a report to NCMEC or a law-enforcement request. Audited. */
    @Transactional
    public Copy read(UUID actor, AdminRole role, byte[] letterHash) {
        Summary s = summaryOf(letterHash);
        Map<String, Object> row = jdbc.sql("SELECT author_id, body_encrypted, signature FROM preserved_content"
                        + " WHERE letter_hash = :hash")
                .param("hash", letterHash).query().singleRow();
        String body = cipher.decrypt((byte[]) row.get("body_encrypted"),
                BiometricCipher.preservationContext((UUID) row.get("author_id")));
        audit.record(actor, role, "preserved.read", s.letterHash(), Map.of());
        return new Copy(s, body, Base64Url.encode((byte[]) row.get("signature")));
    }

    /** Records the report made to NCMEC (its report number), so the same-day duty is tracked. */
    @Transactional
    public Summary recordReport(UUID actor, AdminRole role, byte[] letterHash, String reportId) {
        String id = reportId == null ? "" : reportId.trim();
        if (id.isEmpty() || id.length() > 100) {
            throw new IllegalArgumentException("A report id has 1 to 100 characters");
        }
        summaryOf(letterHash);
        jdbc.sql("UPDATE preserved_content SET report_id = :id, reported_at = :at WHERE letter_hash = :hash")
                .param("id", id).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("hash", letterHash).update();
        audit.record(actor, role, "preserved.reported", Base64Url.encode(letterHash), Map.of("reportId", id));
        return summaryOf(letterHash);
    }

    /** Deletes copies whose preservation period has passed. Runs daily. */
    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT24H")
    public int purgeExpired() {
        int purged = jdbc.sql("DELETE FROM preserved_content WHERE preserve_until < :now")
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        if (purged > 0) {
            log.info("Purged {} preserved copies past their preservation period", purged);
        }
        return purged;
    }

    private static final String SUMMARY = """
            SELECT letter_hash, author_key, sent_at, ledger_seq, preserved_at, preserve_until, report_id, reported_at
              FROM preserved_content
            """;

    private Summary summaryOf(byte[] letterHash) {
        return jdbc.sql(SUMMARY + " WHERE letter_hash = :hash").param("hash", letterHash)
                .query(PreservedContent::summary).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing preserved for this letter"));
    }

    private static Summary summary(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        OffsetDateTime reported = rs.getObject("reported_at", OffsetDateTime.class);
        return new Summary(Base64Url.encode(rs.getBytes("letter_hash")), Base64Url.encode(rs.getBytes("author_key")),
                rs.getString("sent_at"), rs.getLong("ledger_seq"),
                rs.getObject("preserved_at", OffsetDateTime.class).toInstant(),
                rs.getObject("preserve_until", OffsetDateTime.class).toInstant(), rs.getString("report_id"),
                reported == null ? null : reported.toInstant());
    }
}
