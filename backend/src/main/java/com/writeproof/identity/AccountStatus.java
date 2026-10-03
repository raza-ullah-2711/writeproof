package com.writeproof.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Suspension and forced sign-out, set by admins (Task 13b) and enforced here. */
@Service
public class AccountStatus {

    public record Suspension(Instant since, String reason) {}

    public record Status(Suspension suspension, Instant sessionsRevokedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    AccountStatus(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Status of(UUID accountId) {
        return jdbc.sql("SELECT suspended_at, suspension_reason, sessions_revoked_at FROM account_status "
                        + "WHERE account_id = :id")
                .param("id", accountId)
                .query((rs, row) -> {
                    OffsetDateTime suspended = rs.getObject("suspended_at", OffsetDateTime.class);
                    OffsetDateTime revoked = rs.getObject("sessions_revoked_at", OffsetDateTime.class);
                    return new Status(
                            suspended == null ? null : new Suspension(suspended.toInstant(),
                                    rs.getString("suspension_reason")),
                            revoked == null ? null : revoked.toInstant());
                })
                .optional()
                .orElse(new Status(null, null));
    }

    /** Throws 403 if the account is suspended. Call before anything that sends or publishes. */
    public void requireCanSend(UUID accountId) {
        Suspension s = of(accountId).suspension();
        if (s != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account is suspended and can't send letters: " + s.reason());
        }
    }

    /** @return false if it was already suspended */
    public boolean suspend(UUID accountId, String reason) {
        return jdbc.sql("""
                INSERT INTO account_status (account_id, suspended_at, suspension_reason)
                VALUES (:id, :at, :reason)
                ON CONFLICT (account_id) DO UPDATE
                   SET suspended_at = EXCLUDED.suspended_at, suspension_reason = EXCLUDED.suspension_reason
                 WHERE account_status.suspended_at IS NULL
                """)
                .param("id", accountId).param("at", now()).param("reason", reason)
                .update() == 1;
    }

    /** @return false if it wasn't suspended */
    public boolean reinstate(UUID accountId) {
        return jdbc.sql("""
                UPDATE account_status SET suspended_at = NULL, suspension_reason = NULL
                 WHERE account_id = :id AND suspended_at IS NOT NULL
                """)
                .param("id", accountId).update() == 1;
    }

    /**
     * Signs the account out everywhere: every token issued up to now stops working. Rounded up to
     * the next second, because token issue times have whole-second precision.
     */
    public Instant revokeSessions(UUID accountId) {
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        jdbc.sql("""
                INSERT INTO account_status (account_id, sessions_revoked_at) VALUES (:id, :at)
                ON CONFLICT (account_id) DO UPDATE SET sessions_revoked_at = EXCLUDED.sessions_revoked_at
                """)
                .param("id", accountId).param("at", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)).update();
        return cutoff;
    }

    /** True if a token issued at {@code issuedAt} was revoked by a forced sign-out. */
    public boolean revoked(UUID accountId, Instant issuedAt) {
        Instant cutoff = of(accountId).sessionsRevokedAt();
        return cutoff != null && (issuedAt == null || issuedAt.isBefore(cutoff));
    }

    public Optional<Suspension> suspension(UUID accountId) {
        return Optional.ofNullable(of(accountId).suspension());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
