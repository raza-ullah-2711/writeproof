package com.writeproof.admin;

import com.writeproof.handwriting.HandwritingProperties;
import com.writeproof.handwriting.HandwritingService;
import com.writeproof.ledger.LedgerService;
import com.writeproof.security.RateLimitFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Numbers for the admin dashboard. Only what the server can see anyway: counts and timestamps,
 * never letter content, contacts or handwriting (which it can't read).
 */
@Service
public class DashboardService {

    public record Dashboard(Instant generatedAt, Accounts accounts, Letters letters, Ledger ledger,
                            Handwriting handwriting, RateLimits rateLimits, SystemInfo system) {}

    public record Accounts(long total, long new7d, long new30d, long enrolled, long canReceiveLetters,
                           long backedUp, long withContacts, long calibrationContributors) {}

    public record Day(LocalDate date, long sealed, long open) {}

    public record Letters(long sealed, long sealed24h, long sealed7d, long replies, long open, long open7d,
                          List<Day> last30Days) {}

    public record Ledger(long size, Long lastCheckpointSize, Instant lastCheckpointAt, long unpublished) {}

    public record Handwriting(double threshold, long checksAccepted, long checksRejected, long lettersAccepted,
                              long lettersRejected) {}

    public record RateLimits(Map<String, Long> rejections) {}

    public record SystemInfo(String version, Instant startedAt, long uptimeSeconds, long databaseBytes) {}

    private static final int DAYS = 30;

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final HandwritingProperties handwriting;
    private final MeterRegistry meters;
    private final Clock clock;

    DashboardService(JdbcClient jdbc, LedgerService ledger, HandwritingProperties handwriting, MeterRegistry meters,
                     Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.handwriting = handwriting;
        this.meters = meters;
        this.clock = clock;
    }

    public Dashboard dashboard() {
        Instant now = clock.instant();
        return new Dashboard(now, accounts(), letters(now), ledger(), handwriting(), rateLimits(), system(now));
    }

    private Accounts accounts() {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM accounts) AS total,
                       (SELECT count(*) FROM accounts WHERE created_at > now() - interval '7 days') AS new7d,
                       (SELECT count(*) FROM accounts WHERE created_at > now() - interval '30 days') AS new30d,
                       (SELECT count(*) FROM handwriting_enrolments) AS enrolled,
                       (SELECT count(*) FROM accounts WHERE encryption_key IS NOT NULL) AS can_receive,
                       (SELECT count(*) FROM wallet_backups) AS backed_up,
                       (SELECT count(*) FROM contact_books) AS with_contacts,
                       (SELECT count(*) FROM calibration_contributors) AS contributors
                """)
                .query((rs, row) -> new Accounts(rs.getLong("total"), rs.getLong("new7d"), rs.getLong("new30d"),
                        rs.getLong("enrolled"), rs.getLong("can_receive"), rs.getLong("backed_up"),
                        rs.getLong("with_contacts"), rs.getLong("contributors")))
                .single();
    }

    private Letters letters(Instant now) {
        Letters totals = jdbc.sql("""
                SELECT (SELECT count(*) FROM letters) AS sealed,
                       (SELECT count(*) FROM letters WHERE created_at > now() - interval '1 day') AS sealed24h,
                       (SELECT count(*) FROM letters WHERE created_at > now() - interval '7 days') AS sealed7d,
                       (SELECT count(*) FROM letters WHERE in_reply_to IS NOT NULL) AS replies,
                       (SELECT count(*) FROM open_letters) AS open,
                       (SELECT count(*) FROM open_letters WHERE created_at > now() - interval '7 days') AS open7d
                """)
                .query((rs, row) -> new Letters(rs.getLong("sealed"), rs.getLong("sealed24h"), rs.getLong("sealed7d"),
                        rs.getLong("replies"), rs.getLong("open"), rs.getLong("open7d"), List.of()))
                .single();
        List<Day> days = jdbc.sql("""
                SELECT d::date AS day,
                       (SELECT count(*) FROM letters l
                         WHERE l.created_at >= d AND l.created_at < d + interval '1 day') AS sealed,
                       (SELECT count(*) FROM open_letters o
                         WHERE o.created_at >= d AND o.created_at < d + interval '1 day') AS open
                  FROM generate_series(date_trunc('day', now() AT TIME ZONE 'UTC') - make_interval(days => :days - 1),
                                       date_trunc('day', now() AT TIME ZONE 'UTC'), interval '1 day') AS d
                 ORDER BY d
                """)
                .param("days", DAYS)
                .query((rs, row) -> new Day(rs.getObject("day", LocalDate.class), rs.getLong("sealed"),
                        rs.getLong("open")))
                .list();
        return new Letters(totals.sealed(), totals.sealed24h(), totals.sealed7d(), totals.replies(), totals.open(),
                totals.open7d(), days);
    }

    private Ledger ledger() {
        long size = ledger.size();
        return jdbc.sql("SELECT size, published_at FROM ledger_checkpoints ORDER BY size DESC LIMIT 1")
                .query((rs, row) -> new Ledger(size, rs.getLong("size"),
                        rs.getObject("published_at", OffsetDateTime.class).toInstant(), size - rs.getLong("size")))
                .optional()
                .orElse(new Ledger(size, null, null, size));
    }

    private Handwriting handwriting() {
        return new Handwriting(handwriting.threshold(), count("check", "accepted"), count("check", "rejected"),
                count("letter", "accepted"), count("letter", "rejected"));
    }

    private long count(String purpose, String result) {
        Counter counter = meters.find(HandwritingService.VERIFICATIONS_METRIC)
                .tags("purpose", purpose, "result", result).counter();
        return counter == null ? 0 : (long) counter.count();
    }

    private RateLimits rateLimits() {
        Map<String, Long> rejections = new TreeMap<>();
        meters.find(RateLimitFilter.REJECTIONS_METRIC).counters()
                .forEach(c -> rejections.merge(c.getId().getTag("rule"), (long) c.count(), Long::sum));
        return new RateLimits(rejections);
    }

    private SystemInfo system(Instant now) {
        Instant started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        String version = DashboardService.class.getPackage().getImplementationVersion();
        long bytes = jdbc.sql("SELECT pg_database_size(current_database())").query(Long.class).single();
        return new SystemInfo(version == null ? "dev" : version, started, Math.max(0, now.getEpochSecond()
                - started.getEpochSecond()), bytes);
    }
}
