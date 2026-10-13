package com.writeproof.archive;

import com.writeproof.ledger.LedgerEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Ciphertext in, ciphertext out: the database enforces that entries can't change. */
@Repository
class ArchiveRepository {

    private static final String SELECT = """
            SELECT x.entry_hash, a.public_key AS owner_key, x.added_at, x.iv, x.ciphertext, x.signature,
                   e.seq, e.prev_hash, e.payload_hash, e.recorded_at, e.entry_hash AS ledger_hash
              FROM archive_entries x
              JOIN accounts a ON a.id = x.owner_id
              JOIN ledger_entries e ON e.seq = x.ledger_seq
            """;

    private final JdbcClient jdbc;

    ArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if a photo with this hash already exists (anyone's) */
    boolean insertBlob(byte[] hash, UUID ownerId, byte[] bytes, Instant at) {
        return jdbc.sql("""
                INSERT INTO archive_blobs (blob_hash, owner_id, bytes, size, created_at)
                VALUES (:hash, :owner, :bytes, :size, :at) ON CONFLICT (blob_hash) DO NOTHING
                """)
                .param("hash", hash).param("owner", ownerId).param("bytes", bytes).param("size", bytes.length)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update() == 1;
    }

    int pendingBlobs(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM archive_blobs WHERE owner_id = :o AND entry_hash IS NULL")
                .param("o", ownerId).query(Integer.class).single();
    }

    long storedBytes(UUID ownerId) {
        return jdbc.sql("SELECT COALESCE(sum(size), 0) FROM archive_blobs WHERE owner_id = :o")
                .param("o", ownerId).query(Long.class).single();
    }

    /** True if every hash is a photo this account uploaded and hasn't attached to an entry yet. */
    boolean ownsPending(UUID ownerId, List<byte[]> hashes) {
        for (byte[] h : hashes) {
            boolean ok = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM archive_blobs
                                    WHERE blob_hash = :h AND owner_id = :o AND entry_hash IS NULL)
                    """).param("h", h).param("o", ownerId).query(Boolean.class).single();
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    boolean exists(byte[] entryHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM archive_entries WHERE entry_hash = :h)")
                .param("h", entryHash).query(Boolean.class).single();
    }

    void insert(byte[] entryHash, UUID ownerId, String addedAt, byte[] iv, byte[] ciphertext, byte[] signature,
                long ledgerSeq, Instant at, List<UUID> readerIds, List<ArchiveHashing.Wrap> wraps,
                List<byte[]> blobHashes) {
        jdbc.sql("""
                INSERT INTO archive_entries (entry_hash, owner_id, added_at, iv, ciphertext, signature, ledger_seq,
                                             created_at)
                VALUES (:h, :o, :addedAt, :iv, :ct, :sig, :seq, :at)
                """)
                .param("h", entryHash).param("o", ownerId).param("addedAt", addedAt).param("iv", iv)
                .param("ct", ciphertext).param("sig", signature).param("seq", ledgerSeq)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
        for (int i = 0; i < wraps.size(); i++) {
            ArchiveHashing.Wrap w = wraps.get(i);
            jdbc.sql("""
                    INSERT INTO archive_readers (entry_hash, position, reader_id, ephemeral_public_key, iv, wrapped_key)
                    VALUES (:h, :p, :r, :eph, :iv, :wk)
                    """)
                    .param("h", entryHash).param("p", i).param("r", readerIds.get(i))
                    .param("eph", w.ephemeralPublicKey()).param("iv", w.iv()).param("wk", w.wrappedKey())
                    .update();
        }
        for (int i = 0; i < blobHashes.size(); i++) {
            jdbc.sql("UPDATE archive_blobs SET entry_hash = :h, position = :p WHERE blob_hash = :b")
                    .param("h", entryHash).param("p", i).param("b", blobHashes.get(i)).update();
        }
    }

    /** Entries this account can open (its own and those shared with it), newest first. */
    List<ArchiveEntry> forReader(UUID readerId) {
        return jdbc.sql(SELECT + """
                 WHERE x.entry_hash IN (SELECT entry_hash FROM archive_readers WHERE reader_id = :r)
                 ORDER BY x.ledger_seq DESC
                """)
                .param("r", readerId).query(this::map).list();
    }

    Optional<ArchiveEntry> forReader(UUID readerId, byte[] entryHash) {
        return jdbc.sql(SELECT + """
                 WHERE x.entry_hash = :h
                   AND EXISTS (SELECT 1 FROM archive_readers WHERE entry_hash = :h AND reader_id = :r)
                """)
                .param("h", entryHash).param("r", readerId).query(this::map).optional();
    }

    /** A photo, if this account may fetch it: a reader of its entry, or its uploader while unattached. */
    Optional<byte[]> blobFor(UUID accountId, byte[] blobHash) {
        return jdbc.sql("""
                SELECT b.bytes FROM archive_blobs b
                 WHERE b.blob_hash = :b
                   AND ((b.entry_hash IS NULL AND b.owner_id = :a)
                        OR EXISTS (SELECT 1 FROM archive_readers r WHERE r.entry_hash = b.entry_hash
                                                                     AND r.reader_id = :a))
                """)
                .param("b", blobHash).param("a", accountId).query(byte[].class).optional();
    }

    /** Uploads never attached to an entry, older than {@code before}. */
    int deleteAbandoned(Instant before) {
        return jdbc.sql("DELETE FROM archive_blobs WHERE entry_hash IS NULL AND created_at < :t")
                .param("t", OffsetDateTime.ofInstant(before, ZoneOffset.UTC)).update();
    }

    private ArchiveEntry map(ResultSet rs, int row) throws SQLException {
        byte[] hash = rs.getBytes("entry_hash");
        List<byte[]> blobs = jdbc.sql("SELECT blob_hash FROM archive_blobs WHERE entry_hash = :h ORDER BY position")
                .param("h", hash).query(byte[].class).list();
        List<ArchiveHashing.Wrap> readers = new ArrayList<>(jdbc.sql("""
                        SELECT a.public_key, r.ephemeral_public_key, r.iv, r.wrapped_key
                          FROM archive_readers r JOIN accounts a ON a.id = r.reader_id
                         WHERE r.entry_hash = :h ORDER BY r.position
                        """)
                .param("h", hash)
                .query((r, i) -> new ArchiveHashing.Wrap(r.getBytes(1), r.getBytes(2), r.getBytes(3), r.getBytes(4)))
                .list());
        return new ArchiveEntry(hash, rs.getBytes("owner_key"), rs.getString("added_at"), rs.getBytes("iv"),
                rs.getBytes("ciphertext"), rs.getBytes("signature"),
                new LedgerEntry(rs.getLong("seq"), rs.getBytes("prev_hash"), rs.getBytes("payload_hash"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(), rs.getBytes("ledger_hash")),
                blobs, readers);
    }
}
