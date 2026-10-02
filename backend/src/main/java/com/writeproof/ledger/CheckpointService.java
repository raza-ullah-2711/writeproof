package com.writeproof.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signs checkpoints of the ledger and publishes them. Clients get a fresh one for every proof;
 * stored, published ones are what outside witnesses keep the operator to.
 */
@Service
public class CheckpointService {

    private final LedgerService ledger;
    private final LedgerSigner signer;
    private final CheckpointPublisher publisher;
    private final JdbcClient jdbc;
    private final Clock clock;

    CheckpointService(LedgerService ledger, LedgerSigner signer, CheckpointPublisher publisher, JdbcClient jdbc,
                      Clock clock) {
        this.ledger = ledger;
        this.signer = signer;
        this.publisher = publisher;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A freshly signed checkpoint of the ledger as it is now. */
    public Checkpoint current() {
        long size = ledger.size();
        return sign(size, ledger.root(size));
    }

    /** Stores and publishes a checkpoint if the ledger grew since the last published one. */
    @Scheduled(fixedDelayString = "${writeproof.ledger.checkpoint-interval:PT10M}",
               initialDelayString = "${writeproof.ledger.checkpoint-interval:PT10M}")
    @Transactional
    public Optional<Checkpoint> publishIfGrown() {
        Checkpoint checkpoint = current();
        long published = latestPublished().map(Checkpoint::size).orElse(0L);
        if (checkpoint.size() <= published) {
            return Optional.empty();
        }
        jdbc.sql("""
                INSERT INTO ledger_checkpoints (size, root, timestamp_millis, signature, published_at)
                VALUES (:size, :root, :ts, :sig, :at)
                ON CONFLICT (size) DO NOTHING
                """)
                .param("size", checkpoint.size())
                .param("root", checkpoint.root())
                .param("ts", checkpoint.timestampMillis())
                .param("sig", checkpoint.signature())
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        publisher.publish(checkpoint);
        return Optional.of(checkpoint);
    }

    public Optional<Checkpoint> latestPublished() {
        return jdbc.sql("SELECT * FROM ledger_checkpoints ORDER BY size DESC LIMIT 1")
                .query(CheckpointService::map).optional();
    }

    public List<Checkpoint> published(long afterSize, int limit) {
        return jdbc.sql("SELECT * FROM ledger_checkpoints WHERE size > :after ORDER BY size LIMIT :limit")
                .param("after", afterSize).param("limit", limit)
                .query(CheckpointService::map).list();
    }

    private Checkpoint sign(long size, byte[] root) {
        long ts = clock.instant().toEpochMilli();
        return new Checkpoint(size, root, ts, signer.sign(Checkpoint.signedMessage(size, root, ts)));
    }

    private static Checkpoint map(ResultSet rs, int row) throws SQLException {
        return new Checkpoint(rs.getLong("size"), rs.getBytes("root"), rs.getLong("timestamp_millis"),
                rs.getBytes("signature"));
    }
}
