package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import com.writeproof.openletters.OpenLetterService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moderation of open letters (Task 13c), for moderators and admins. Open letters are public, so
 * moderators read their text; a takedown deletes the text for good while the letter's hash stays
 * on the ledger. The audit log records each decision but never copies the removed text.
 */
@Service
public class ModerationService {

    public record Report(long id, String category, String note, Instant createdAt, boolean fromAccount,
                         String resolution) {}

    /** A reported (or looked-up) open letter with its reports. {@code body} is null once removed. */
    public record Case(String letterHash, String author, String sentAt, String body, long ledgerSeq,
                       Map<String, Integer> openReportsByCategory, int openReports, Instant firstReportedAt,
                       List<Report> reports, Removal removal) {}

    public record Removal(String category, Instant at, String by) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Clock clock;

    ModerationService(JdbcClient jdbc, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** Letters with unresolved reports, most reported first, then oldest report first. */
    public List<Case> queue(int limit) {
        List<byte[]> hashes = jdbc.sql("""
                SELECT letter_hash FROM open_letter_reports WHERE resolved_at IS NULL
                 GROUP BY letter_hash ORDER BY count(*) DESC, min(created_at) LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, row) -> rs.getBytes(1))
                .list();
        return hashes.stream().map(this::caseOf).toList();
    }

    public Case letter(byte[] letterHash) {
        return caseOf(letterHash);
    }

    public long openReportCount() {
        return jdbc.sql("SELECT count(*) FROM open_letter_reports WHERE resolved_at IS NULL").query(Long.class)
                .single();
    }

    /** Resolves every open report on the letter as dismissed; the letter stays up. */
    @Transactional
    public int dismiss(UUID actor, AdminRole role, byte[] letterHash, String note) {
        Case c = caseOf(letterHash);
        int resolved = resolve(letterHash, "dismissed");
        if (resolved == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "There are no open reports on this letter");
        }
        audit.record(actor, role, "moderation.dismissed", c.letterHash(), detail(resolved, null, note));
        return resolved;
    }

    /**
     * Takes the letter down: its text is deleted permanently (the only change open_letters
     * allows), any open reports are resolved as removed, and the decision is audited.
     */
    @Transactional
    public void remove(UUID actor, AdminRole role, byte[] letterHash, String category, String note) {
        if (!OpenLetterService.REPORT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category must be one of " + OpenLetterService.REPORT_CATEGORIES);
        }
        Case c = caseOf(letterHash);
        if (c.removal() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already removed");
        }
        jdbc.sql("""
                INSERT INTO open_letter_removals (letter_hash, category, removed_at, removed_by)
                SELECT :hash, :category, :at, a.public_key FROM accounts a WHERE a.id = :actor
                """)
                .param("hash", letterHash).param("category", category)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("actor", actor)
                .update();
        jdbc.sql("UPDATE open_letters SET body = NULL WHERE letter_hash = :hash").param("hash", letterHash).update();
        int resolved = resolve(letterHash, "removed");
        audit.record(actor, role, "moderation.removed", c.letterHash(), detail(resolved, category, note));
    }

    private int resolve(byte[] letterHash, String resolution) {
        return jdbc.sql("""
                UPDATE open_letter_reports SET resolved_at = :at, resolution = :resolution
                 WHERE letter_hash = :hash AND resolved_at IS NULL
                """)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("resolution", resolution).param("hash", letterHash)
                .update();
    }

    private static Map<String, Object> detail(int reports, String category, String note) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reportsResolved", reports);
        if (category != null) {
            detail.put("category", category);
        }
        if (note != null && !note.isBlank()) {
            detail.put("note", note.trim().length() > 500 ? note.trim().substring(0, 500) : note.trim());
        }
        return detail;
    }

    private Case caseOf(byte[] letterHash) {
        List<Report> reports = jdbc.sql("""
                SELECT id, category, note, created_at, reporter_id IS NOT NULL AS from_account, resolution
                  FROM open_letter_reports WHERE letter_hash = :hash ORDER BY created_at
                """)
                .param("hash", letterHash)
                .query(ModerationService::report)
                .list();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Instant first = null;
        for (Report r : reports) {
            if (r.resolution() == null) {
                byCategory.merge(r.category(), 1, Integer::sum);
                first = first == null ? r.createdAt() : first;
            }
        }
        int open = byCategory.values().stream().mapToInt(Integer::intValue).sum();
        Instant firstReported = first;
        return jdbc.sql("""
                SELECT o.letter_hash, a.public_key, o.sent_at, o.body, o.ledger_seq,
                       r.category, r.removed_at, r.removed_by
                  FROM open_letters o
                  JOIN accounts a ON a.id = o.author_id
                  LEFT JOIN open_letter_removals r ON r.letter_hash = o.letter_hash
                 WHERE o.letter_hash = :hash
                """)
                .param("hash", letterHash)
                .query((rs, row) -> new Case(Base64Url.encode(rs.getBytes("letter_hash")),
                        Base64Url.encode(rs.getBytes("public_key")), rs.getString("sent_at"), rs.getString("body"),
                        rs.getLong("ledger_seq"), byCategory, open, firstReported, reports,
                        rs.getString("category") == null ? null : new Removal(rs.getString("category"),
                                rs.getObject("removed_at", OffsetDateTime.class).toInstant(),
                                Base64Url.encode(rs.getBytes("removed_by")))))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such open letter"));
    }

    private static Report report(ResultSet rs, int row) throws SQLException {
        return new Report(rs.getLong("id"), rs.getString("category"), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("from_account"),
                rs.getString("resolution"));
    }
}
