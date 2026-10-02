package com.writeproof.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local stand-in for a real chain: a table where every entry hashes its predecessor.
 * Appends are serialized with a transaction-scoped advisory lock, so concurrent writers can
 * never fork the chain. Database triggers reject UPDATE/DELETE/TRUNCATE (migration V4).
 */
@Service
public class MockLedgerService implements LedgerService {

    /** Arbitrary key for {@code pg_advisory_xact_lock}; only ledger appends take it. */
    private static final long APPEND_LOCK = 0x5772697465_4c6467L;
    private static final int VERIFY_PAGE = 1000;

    private final JdbcClient jdbc;
    private final Clock clock;

    MockLedgerService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry append(byte[] payloadHash) {
        if (payloadHash == null || payloadHash.length != 32) {
            throw new IllegalArgumentException("Payload hash must be 32 bytes");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", APPEND_LOCK).query().singleRow();
        Optional<LedgerEntry> head = head();
        long seq = head.map(e -> e.seq() + 1).orElse(1L);
        byte[] prev = head.map(LedgerEntry::entryHash).orElse(LedgerHashing.GENESIS_PREV_HASH);
        Instant recordedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LedgerEntry entry = new LedgerEntry(
                seq, prev, payloadHash, recordedAt, LedgerHashing.entryHash(seq, prev, payloadHash, recordedAt));
        jdbc.sql("""
                INSERT INTO ledger_entries (seq, prev_hash, payload_hash, recorded_at, entry_hash)
                VALUES (:seq, :prev, :payload, :recordedAt, :hash)
                """)
                .param("seq", seq)
                .param("prev", prev)
                .param("payload", payloadHash)
                .param("recordedAt", OffsetDateTime.ofInstant(recordedAt, ZoneOffset.UTC))
                .param("hash", entry.entryHash())
                .update();
        return entry;
    }

    @Override
    public Optional<LedgerEntry> entry(long seq) {
        return jdbc.sql("SELECT * FROM ledger_entries WHERE seq = :seq")
                .param("seq", seq)
                .query(MockLedgerService::map)
                .optional();
    }

    @Override
    public List<LedgerEntry> entries(long fromSeq, int limit) {
        return jdbc.sql("SELECT * FROM ledger_entries WHERE seq >= :from ORDER BY seq LIMIT :limit")
                .param("from", fromSeq)
                .param("limit", limit)
                .query(MockLedgerService::map)
                .list();
    }

    @Override
    public Optional<LedgerEntry> head() {
        return jdbc.sql("SELECT * FROM ledger_entries ORDER BY seq DESC LIMIT 1")
                .query(MockLedgerService::map)
                .optional();
    }

    @Override
    public ChainCheck verify() {
        byte[] expectedPrev = LedgerHashing.GENESIS_PREV_HASH;
        long expectedSeq = 1;
        while (true) {
            List<LedgerEntry> page = entries(expectedSeq, VERIFY_PAGE);
            for (LedgerEntry e : page) {
                boolean linked = e.seq() == expectedSeq && Arrays.equals(e.prevHash(), expectedPrev);
                byte[] recomputed = LedgerHashing.entryHash(e.seq(), e.prevHash(), e.payloadHash(), e.recordedAt());
                if (!linked || !Arrays.equals(recomputed, e.entryHash())) {
                    return new ChainCheck(false, expectedSeq - 1, expectedSeq);
                }
                expectedPrev = e.entryHash();
                expectedSeq++;
            }
            if (page.size() < VERIFY_PAGE) {
                return new ChainCheck(true, expectedSeq - 1, null);
            }
        }
    }

    private static LedgerEntry map(ResultSet rs, int row) throws SQLException {
        return new LedgerEntry(
                rs.getLong("seq"),
                rs.getBytes("prev_hash"),
                rs.getBytes("payload_hash"),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                rs.getBytes("entry_hash"));
    }
}
