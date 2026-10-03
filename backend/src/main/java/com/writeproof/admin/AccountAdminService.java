package com.writeproof.admin;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.AccountStatus;
import com.writeproof.security.TokenBucketRateLimiter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account management for admins (Task 13b): what the server knows about an account (metadata
 * only, never content) and the actions on it. Every action is written to the audit log in the
 * same transaction.
 */
@Service
public class AccountAdminService {

    public record Summary(UUID accountId, String publicKey, Instant createdAt, boolean enrolled,
                          boolean canReceiveLetters, boolean backedUp, long lettersSent, long lettersReceived,
                          long openLetters, AccountStatus.Suspension suspension, AdminRole role) {}

    public record Detail(Summary account, Instant lastLetterAt, boolean usesContacts, boolean calibrationContributor,
                         Instant sessionsRevokedAt, List<AuditLog.Entry> history) {}

    static final int MIN_QUERY = 4;

    private static final String SUMMARY = """
            SELECT a.id, a.public_key, a.created_at, a.encryption_key IS NOT NULL AS can_receive,
                   EXISTS (SELECT 1 FROM handwriting_enrolments h WHERE h.account_id = a.id) AS enrolled,
                   EXISTS (SELECT 1 FROM wallet_backups b WHERE b.account_id = a.id) AS backed_up,
                   (SELECT count(*) FROM letters l WHERE l.sender_id = a.id) AS sent,
                   (SELECT count(*) FROM letters l WHERE l.recipient_id = a.id) AS received,
                   (SELECT count(*) FROM open_letters o WHERE o.author_id = a.id) AS open_letters,
                   s.suspended_at, s.suspension_reason
              FROM accounts a LEFT JOIN account_status s ON s.account_id = a.id
            """;
    /** base64url of the key, as shown everywhere else, so admins can search by address prefix. */
    private static final String ADDRESS = "translate(rtrim(encode(a.public_key, 'base64'), '='), '+/', '-_')";

    private final JdbcClient jdbc;
    private final AccountStatus status;
    private final AdminRoles roles;
    private final AuditLog audit;
    private final TokenBucketRateLimiter limiter;

    AccountAdminService(JdbcClient jdbc, AccountStatus status, AdminRoles roles, AuditLog audit,
                        TokenBucketRateLimiter limiter) {
        this.jdbc = jdbc;
        this.status = status;
        this.roles = roles;
        this.audit = audit;
        this.limiter = limiter;
    }

    /** Newest accounts, or those whose address starts with {@code query} (at least 4 characters). */
    public List<Summary> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty() && (q.length() < MIN_QUERY || !q.matches("[A-Za-z0-9_-]+"))) {
            throw new IllegalArgumentException("Search by at least " + MIN_QUERY + " characters of an address");
        }
        return jdbc.sql(SUMMARY + (q.isEmpty() ? "" : " WHERE " + ADDRESS + " LIKE :prefix")
                        + " ORDER BY a.created_at DESC LIMIT :limit")
                .param("prefix", q + "%")
                .param("limit", limit)
                .query(this::summary)
                .list();
    }

    public Detail detail(UUID accountId) {
        Summary summary = find(accountId);
        return jdbc.sql("""
                SELECT (SELECT max(created_at) FROM (
                            SELECT created_at FROM letters WHERE sender_id = :id
                            UNION ALL SELECT created_at FROM open_letters WHERE author_id = :id) t) AS last_letter,
                       EXISTS (SELECT 1 FROM contact_books WHERE account_id = :id) AS contacts,
                       EXISTS (SELECT 1 FROM calibration_contributors WHERE account_id = :id) AS contributor
                """)
                .param("id", accountId)
                .query((rs, row) -> {
                    OffsetDateTime last = rs.getObject("last_letter", OffsetDateTime.class);
                    return new Detail(summary, last == null ? null : last.toInstant(), rs.getBoolean("contacts"),
                            rs.getBoolean("contributor"), status.of(accountId).sessionsRevokedAt(),
                            audit.forTarget(summary.publicKey(), 50));
                })
                .single();
    }

    @Transactional
    public void suspend(UUID actor, AdminRole actorRole, UUID accountId, String reason) {
        Summary target = find(accountId);
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 500) {
            throw new IllegalArgumentException("Give a reason of 1 to 500 characters; the account holder sees it");
        }
        if (target.role() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Remove their admin role before suspending them");
        }
        if (!status.suspend(accountId, why)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already suspended");
        }
        audit.record(actor, actorRole, "account.suspended", target.publicKey(), Map.of("reason", why));
    }

    @Transactional
    public void reinstate(UUID actor, AdminRole actorRole, UUID accountId) {
        Summary target = find(accountId);
        if (!status.reinstate(accountId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is not suspended");
        }
        audit.record(actor, actorRole, "account.reinstated", target.publicKey(),
                Map.of("wasSuspendedFor", target.suspension().reason()));
    }

    @Transactional
    public Instant signOut(UUID actor, AdminRole actorRole, UUID accountId) {
        Summary target = find(accountId);
        Instant cutoff = status.revokeSessions(accountId);
        audit.record(actor, actorRole, "account.signed-out", target.publicKey(), Map.of());
        return cutoff;
    }

    @Transactional
    public int resetRateLimits(UUID actor, AdminRole actorRole, UUID accountId) {
        Summary target = find(accountId);
        int cleared = limiter.forget(accountId.toString());
        audit.record(actor, actorRole, "account.rate-limits-reset", target.publicKey(), Map.of("buckets", cleared));
        return cleared;
    }

    private Summary find(UUID accountId) {
        return jdbc.sql(SUMMARY + " WHERE a.id = :id").param("id", accountId).query(this::summary).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account"));
    }

    private Summary summary(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        OffsetDateTime suspended = rs.getObject("suspended_at", OffsetDateTime.class);
        Optional<AdminRole> role = roles.roleOf(id);
        return new Summary(id, Base64Url.encode(rs.getBytes("public_key")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("enrolled"),
                rs.getBoolean("can_receive"), rs.getBoolean("backed_up"), rs.getLong("sent"), rs.getLong("received"),
                rs.getLong("open_letters"),
                suspended == null ? null : new AccountStatus.Suspension(suspended.toInstant(),
                        rs.getString("suspension_reason")),
                role.orElse(null));
    }
}
