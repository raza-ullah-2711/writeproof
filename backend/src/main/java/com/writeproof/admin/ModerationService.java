package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.AccountStatus;
import com.writeproof.openletters.OpenLetterService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Moderation of open letters (Task 13c, Task 7b), for moderators and admins. Open letters are
 * public, so moderators read their text. A takedown is first a <em>hold</em>: the letter is hidden,
 * its text kept for {@link #APPEAL_WINDOW} so the author can appeal, then deleted for good unless a
 * moderator restores it. Child sexual abuse or exploitation is never held: a copy is preserved for
 * law enforcement ({@link PreservedContent}), the text is removed at once and the author suspended.
 * {@link #STRIKES} final removals in {@link #STRIKE_WINDOW} suspend an author. The letter's hash
 * always stays on the ledger, and the audit log records every decision but never the text.
 * See docs/launch-policies.md (T1, T4, T6).
 */
@Service
public class ModerationService {

    public record Report(long id, String category, String note, Instant createdAt, boolean fromAccount,
                         String resolution) {}

    /** A reported (or looked-up) open letter with its reports. {@code body} is null once removed. */
    public record Case(String letterHash, String author, String sentAt, String body, long ledgerSeq,
                       Map<String, Integer> openReportsByCategory, int openReports, Instant firstReportedAt,
                       List<Report> reports, Removal removal, Hold hold) {}

    public record Removal(String category, Instant at, String by) {}

    /** An active takedown hold: hidden, text kept until {@code deleteAfter} unless appealed. */
    public record Hold(String category, Instant heldAt, Instant deleteAfter, String appeal, Instant appealedAt) {}

    static final String CHILD_SAFETY = "child_safety";
    static final Duration APPEAL_WINDOW = Duration.ofDays(14);
    static final int STRIKES = 3;
    static final Duration STRIKE_WINDOW = Duration.ofDays(90);

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final AccountStatus status;
    private final PreservedContent preserved;
    private final Clock clock;

    ModerationService(JdbcClient jdbc, AuditLog audit, AccountStatus status, PreservedContent preserved, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.status = status;
        this.preserved = preserved;
        this.clock = clock;
    }

    /** Takedowns the authors appealed, waiting for a moderator; oldest appeal first. */
    public List<Case> appeals(int limit) {
        return jdbc.sql("""
                SELECT letter_hash FROM open_letter_holds
                 WHERE decision IS NULL AND appealed_at IS NOT NULL ORDER BY appealed_at LIMIT :limit
                """)
                .param("limit", limit).query((rs, row) -> rs.getBytes(1)).list()
                .stream().map(this::caseOf).toList();
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
     * Takes the letter down. Usually a hold: hidden at once, text deleted after the appeal window.
     * For child sexual abuse or exploitation: preserved for law enforcement, removed at once, and the
     * author suspended. Open reports are resolved as removed either way, and the decision audited.
     */
    @Transactional
    public void remove(UUID actor, AdminRole role, byte[] letterHash, String category, String note) {
        if (!OpenLetterService.REPORT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("category must be one of " + OpenLetterService.REPORT_CATEGORIES);
        }
        Case c = caseOf(letterHash);
        if (c.removal() != null || c.hold() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter was already removed");
        }
        byte[] actorKey = keyOf(actor);
        Map<String, Object> detail = detail(0, category, note);
        if (CHILD_SAFETY.equals(category)) {
            preserved.preserve(letterHash);
            finalRemoval(letterHash, category, actorKey);
            if (status.suspend(authorOf(letterHash), "Removed for child sexual abuse or exploitation")) {
                audit.record(actor, role, "account.suspended", c.author(),
                        Map.of("reason", "child_safety removal of " + c.letterHash()));
            }
            detail.put("preserved", true);
        } else {
            Instant until = clock.instant().plus(APPEAL_WINDOW);
            jdbc.sql("""
                    INSERT INTO open_letter_holds (letter_hash, category, held_at, held_by, delete_after)
                    VALUES (:hash, :category, :at, :by, :until)
                    """)
                    .param("hash", letterHash).param("category", category).param("at", now()).param("by", actorKey)
                    .param("until", OffsetDateTime.ofInstant(until, ZoneOffset.UTC)).update();
            detail.put("hiddenUntilDeletion", until.toString());
        }
        detail.put("reportsResolved", resolve(letterHash, "removed"));
        audit.record(actor, role, "moderation.removed", c.letterHash(), detail);
    }

    /** Grants an appeal (or reverses a takedown): the hold ends and the letter is public again. */
    @Transactional
    public void restore(UUID actor, AdminRole role, byte[] letterHash, String note) {
        Case c = caseOf(letterHash);
        if (c.hold() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter has no takedown that can be reversed");
        }
        endHold(letterHash, "restored");
        audit.record(actor, role, "moderation.restored", c.letterHash(), detail(0, c.hold().category(), note));
    }

    /** Rejects an appeal (or ends the window early): the text is deleted for good now. */
    @Transactional
    public void uphold(UUID actor, AdminRole role, byte[] letterHash, String note) {
        Case c = caseOf(letterHash);
        if (c.hold() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This letter has no takedown waiting for a decision");
        }
        byte[] actorKey = keyOf(actor);
        finalRemoval(letterHash, c.hold().category(), actorKey);
        endHold(letterHash, "upheld");
        audit.record(actor, role, "moderation.upheld", c.letterHash(), detail(0, c.hold().category(), note));
        strikes(authorOf(letterHash), actorKey);
    }

    /** Deletes the text of holds whose appeal window ended without an appeal. Runs hourly. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    @Transactional
    public int expireHolds() {
        List<Object[]> due = jdbc.sql("""
                SELECT letter_hash, category, held_by FROM open_letter_holds
                 WHERE decision IS NULL AND appealed_at IS NULL AND delete_after <= :now
                """)
                .param("now", now())
                .query((rs, row) -> new Object[] {rs.getBytes(1), rs.getString(2), rs.getBytes(3)}).list();
        for (Object[] d : due) {
            byte[] hash = (byte[]) d[0];
            byte[] heldBy = (byte[]) d[2];
            finalRemoval(hash, (String) d[1], heldBy);
            endHold(hash, "expired");
            audit.recordAutomatic(heldBy, "moderation.hold-expired", Base64Url.encode(hash),
                    Map.of("category", d[1]));
            strikes(authorOf(hash), heldBy);
        }
        return due.size();
    }

    /** The final removal: an append-only record, then the text blanked (all open_letters allows). */
    private void finalRemoval(byte[] letterHash, String category, byte[] byKey) {
        jdbc.sql("""
                INSERT INTO open_letter_removals (letter_hash, category, removed_at, removed_by)
                VALUES (:hash, :category, :at, :by)
                """)
                .param("hash", letterHash).param("category", category).param("at", now()).param("by", byKey).update();
        jdbc.sql("UPDATE open_letters SET body = NULL WHERE letter_hash = :hash").param("hash", letterHash).update();
    }

    private void endHold(byte[] letterHash, String decision) {
        jdbc.sql("UPDATE open_letter_holds SET decision = :decision, decided_at = :at"
                        + " WHERE letter_hash = :hash AND decision IS NULL")
                .param("decision", decision).param("at", now()).param("hash", letterHash).update();
    }

    /** Suspends an author with {@link #STRIKES} final removals in {@link #STRIKE_WINDOW} (T6). */
    private void strikes(UUID author, byte[] onBehalfOf) {
        long removals = jdbc.sql("""
                SELECT count(*) FROM open_letter_removals r JOIN open_letters o ON o.letter_hash = r.letter_hash
                 WHERE o.author_id = :author AND r.category <> 'withdrawn' AND r.removed_at > :since
                """)
                .param("author", author)
                .param("since", OffsetDateTime.ofInstant(clock.instant().minus(STRIKE_WINDOW), ZoneOffset.UTC))
                .query(Long.class).single();
        if (removals >= STRIKES && status.suspend(author, removals + " letters removed in the last 90 days")) {
            audit.recordAutomatic(onBehalfOf, "account.suspended", Base64Url.encode(keyOf(author)),
                    Map.of("reason", "repeat offender", "removals", removals));
        }
    }

    private UUID authorOf(byte[] letterHash) {
        return jdbc.sql("SELECT author_id FROM open_letters WHERE letter_hash = :hash").param("hash", letterHash)
                .query(UUID.class).single();
    }

    private byte[] keyOf(UUID account) {
        return jdbc.sql("SELECT public_key FROM accounts WHERE id = :id").param("id", account).query(byte[].class)
                .single();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
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
                       r.category, r.removed_at, r.removed_by,
                       h.category AS hold_category, h.held_at, h.delete_after, h.appeal, h.appealed_at
                  FROM open_letters o
                  JOIN accounts a ON a.id = o.author_id
                  LEFT JOIN open_letter_removals r ON r.letter_hash = o.letter_hash
                  LEFT JOIN open_letter_holds h ON h.letter_hash = o.letter_hash AND h.decision IS NULL
                 WHERE o.letter_hash = :hash
                """)
                .param("hash", letterHash)
                .query((rs, row) -> new Case(Base64Url.encode(rs.getBytes("letter_hash")),
                        Base64Url.encode(rs.getBytes("public_key")), rs.getString("sent_at"), rs.getString("body"),
                        rs.getLong("ledger_seq"), byCategory, open, firstReported, reports,
                        rs.getString("category") == null ? null : new Removal(rs.getString("category"),
                                rs.getObject("removed_at", OffsetDateTime.class).toInstant(),
                                Base64Url.encode(rs.getBytes("removed_by"))),
                        rs.getString("hold_category") == null ? null : new Hold(rs.getString("hold_category"),
                                rs.getObject("held_at", OffsetDateTime.class).toInstant(),
                                rs.getObject("delete_after", OffsetDateTime.class).toInstant(),
                                rs.getString("appeal"),
                                rs.getObject("appealed_at", OffsetDateTime.class) == null ? null
                                        : rs.getObject("appealed_at", OffsetDateTime.class).toInstant())))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such open letter"));
    }

    private static Report report(ResultSet rs, int row) throws SQLException {
        return new Report(rs.getLong("id"), rs.getString("category"), rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("from_account"),
                rs.getString("resolution"));
    }
}
