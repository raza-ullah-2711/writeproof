package com.writeproof.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deletes an account: "close and forget" (docs/launch-policies.md). Everything the server can
 * delete without touching immutable letters or the ledger goes, in one transaction:
 * handwriting, calibration samples, the wallet backup, the contact book, the encryption key and
 * any admin role. The author's open letters are withdrawn (the text is blanked like a takedown),
 * reports they filed lose their link to them, and every session ends. The account row stays as a
 * deleted marker: sealed letters reference it, and nobody can sign in, register it again or
 * write to it. The deleted user's copies of sealed letters can never be opened again, since only
 * their wallet could open them; the other party keeps theirs.
 */
@Service
public class AccountDeletionService {

    /** How far a signed request's timestamp may be from the server's clock. */
    static final Duration REQUEST_WINDOW = Duration.ofMinutes(5);

    private final AccountRepository accounts;
    private final AccountStatus status;
    private final JdbcClient jdbc;
    private final Clock clock;

    AccountDeletionService(AccountRepository accounts, AccountStatus status, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.status = status;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Deletes the account if its wallet signed {@link DeletionMessage} for it, just now. */
    @Transactional
    public void delete(UUID accountId, String requestedAt, byte[] signature) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists"));
        if (account.deleted()) {
            throw AccountService.gone();
        }
        Instant at;
        try {
            at = Instant.parse(requestedAt);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestedAt must be an ISO-8601 instant");
        }
        if (Duration.between(at, clock.instant()).abs().compareTo(REQUEST_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This deletion request is too old; try again");
        }
        if (!Ed25519.verify(account.publicKey(), DeletionMessage.of(accountId, requestedAt), signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The deletion request isn't signed by this wallet");
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (String table : new String[] {"handwriting_history", "handwriting_enrolments", "calibration_contributors",
                "wallet_backups", "contact_books"}) {
            jdbc.sql("DELETE FROM " + table + " WHERE account_id = :id").param("id", accountId).update();
        }
        jdbc.sql("DELETE FROM admin_roles WHERE public_key = :key").param("key", account.publicKey()).update();
        jdbc.sql("UPDATE open_letter_reports SET reporter_id = NULL WHERE reporter_id = :id")
                .param("id", accountId).update();

        // Withdraw their open letters: recorded like a takedown, by their own key, then blanked.
        jdbc.sql("""
                INSERT INTO open_letter_removals (letter_hash, category, removed_at, removed_by)
                SELECT o.letter_hash, 'withdrawn', :at, :key FROM open_letters o
                 WHERE o.author_id = :id AND o.body IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM open_letter_removals r WHERE r.letter_hash = o.letter_hash)
                """)
                .param("at", now).param("key", account.publicKey()).param("id", accountId).update();
        jdbc.sql("""
                UPDATE open_letter_reports SET resolved_at = :at, resolution = 'removed'
                 WHERE resolved_at IS NULL
                   AND letter_hash IN (SELECT letter_hash FROM open_letters WHERE author_id = :id AND body IS NOT NULL)
                """)
                .param("at", now).param("id", accountId).update();
        jdbc.sql("UPDATE open_letters SET body = NULL WHERE author_id = :id AND body IS NOT NULL")
                .param("id", accountId).update();

        jdbc.sql("""
                UPDATE accounts SET encryption_key = NULL, encryption_key_signature = NULL, deleted_at = :at
                 WHERE id = :id
                """)
                .param("at", now).param("id", accountId).update();
        status.revokeSessions(accountId);
    }
}
