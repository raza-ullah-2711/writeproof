package com.writeproof.openletters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Insert and read only: open letters are immutable (and the database enforces it). */
@Repository
class OpenLetterRepository {

    private static final String SELECT = """
            SELECT o.letter_hash, o.author_id, a.public_key AS author_key, o.sent_at, o.body, o.signature,
                   o.handwriting_hash, o.handwriting_score, o.version, o.replay,
                   e.seq, e.prev_hash, e.payload_hash, e.recorded_at, e.entry_hash,
                   COALESCE(r.category, h.category) AS removal_category,
                   COALESCE(r.removed_at, h.held_at) AS removed_at,
                   CASE WHEN r.letter_hash IS NULL THEN h.delete_after END AS appeal_until,
                   (r.letter_hash IS NULL AND h.appealed_at IS NOT NULL) AS appealed
              FROM open_letters o
              JOIN accounts a ON a.id = o.author_id
              JOIN ledger_entries e ON e.seq = o.ledger_seq
              LEFT JOIN open_letter_removals r ON r.letter_hash = o.letter_hash
              LEFT JOIN open_letter_holds h ON h.letter_hash = o.letter_hash AND h.decision IS NULL
            """;

    /** A page's timing as stored when the author opted in to replay. */
    private record Timing(String capturedAt, long[][] timing) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    OpenLetterRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(OpenLetter l, Instant createdAt) {
        if (l.removed()) {
            throw new IllegalArgumentException("A new letter can't be removed");
        }
        jdbc.sql("""
                INSERT INTO open_letters (letter_hash, author_id, sent_at, body, signature, handwriting_hash,
                                          handwriting_score, ledger_seq, created_at, version, replay)
                VALUES (:hash, :author, :sentAt, :body, :signature, :handwritingHash, :score, :seq, :createdAt,
                        :version, :replay)
                """)
                .param("hash", l.letterHash())
                .param("author", l.authorId())
                .param("sentAt", l.sentAt())
                .param("body", l.body())
                .param("signature", l.signature())
                .param("handwritingHash", l.handwritingHash())
                .param("score", l.handwritingScore())
                .param("seq", l.ledgerEntry().seq())
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("version", l.version())
                .param("replay", l.replay())
                .update();
    }

    /**
     * Stores a letter's pages, each with its timing hash. The timing itself is stored only when the
     * author opted in to replay; otherwise it was used for the checks and is dropped here.
     */
    void insertPages(byte[] letterHash, List<HandwrittenPage> pages, boolean replay) {
        for (int i = 0; i < pages.size(); i++) {
            HandwrittenPage p = pages.get(i);
            HandwrittenPage inkOnly = new HandwrittenPage(p.format(), p.version(), null, p.device(), p.pen(), p.ink(),
                    null, null);
            jdbc.sql("""
                    INSERT INTO open_letter_pages (letter_hash, page_index, page_json, ink_hash, timing_hash, timing_json)
                    VALUES (:hash, :index, :page, :inkHash, :timingHash, :timing)
                    """)
                    .param("hash", letterHash).param("index", i).param("page", write(inkOnly))
                    .param("inkHash", p.inkHash()).param("timingHash", p.computeTimingHash())
                    .param("timing", replay ? write(new Timing(p.capturedAt(), p.timing())) : null)
                    .update();
        }
    }

    /** True if any of these pages was already published (one copy of a page seals one letter). */
    boolean pageUsed(List<byte[]> inkHashes) {
        for (byte[] inkHash : inkHashes) {
            if (jdbc.sql("""
                            SELECT EXISTS (SELECT 1 FROM open_letter_pages WHERE ink_hash = :hash)
                                OR EXISTS (SELECT 1 FROM letter_pages WHERE ink_hash = :hash)
                            """)
                    .param("hash", inkHash).query(Boolean.class).single()) {
                return true;
            }
        }
        return false;
    }

    /** A letter's pages as published: blanked pages (after a takedown) are left out. */
    List<HandwrittenPage> pages(byte[] letterHash) {
        return jdbc.sql("""
                SELECT page_json, timing_hash, timing_json FROM open_letter_pages
                 WHERE letter_hash = :hash AND page_json IS NOT NULL ORDER BY page_index
                """)
                .param("hash", letterHash)
                .query((rs, row) -> {
                    HandwrittenPage page = read(rs.getString("page_json"), HandwrittenPage.class);
                    String timingJson = rs.getString("timing_json");
                    String timingHash = Base64Url.encode(rs.getBytes("timing_hash"));
                    if (timingJson == null) {
                        return new HandwrittenPage(page.format(), page.version(), null, page.device(), page.pen(),
                                page.ink(), null, timingHash);
                    }
                    Timing t = read(timingJson, Timing.class);
                    return new HandwrittenPage(page.format(), page.version(), t.capturedAt(), page.device(),
                            page.pen(), page.ink(), t.timing(), timingHash);
                })
                .list();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored page is unreadable", e);
        }
    }

    /** One signature seals one letter, sealed or open. */
    boolean handwritingUsed(byte[] handwritingHash) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM open_letters WHERE handwriting_hash = :hash)
                    OR EXISTS (SELECT 1 FROM letters WHERE handwriting_hash = :hash)
                """)
                .param("hash", handwritingHash)
                .query(Boolean.class)
                .single();
    }

    boolean exists(byte[] letterHash) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM open_letters WHERE letter_hash = :hash)")
                .param("hash", letterHash).query(Boolean.class).single();
    }

    Optional<OpenLetter> find(byte[] letterHash) {
        return jdbc.sql(SELECT + " WHERE o.letter_hash = :hash").param("hash", letterHash).query(this::map)
                .optional();
    }

    List<OpenLetter> byAuthor(UUID authorId) {
        return jdbc.sql(SELECT + " WHERE o.author_id = :author ORDER BY o.ledger_seq DESC")
                .param("author", authorId).query(this::map).list();
    }

    private OpenLetter map(ResultSet rs, int row) throws SQLException {
        OpenLetter.Removal removal = rs.getString("removal_category") == null ? null : new OpenLetter.Removal(
                rs.getString("removal_category"), rs.getObject("removed_at", OffsetDateTime.class).toInstant(),
                rs.getObject("appeal_until", OffsetDateTime.class) == null ? null
                        : rs.getObject("appeal_until", OffsetDateTime.class).toInstant(),
                rs.getBoolean("appealed"));
        byte[] letterHash = rs.getBytes("letter_hash");
        return new OpenLetter(
                letterHash,
                rs.getObject("author_id", UUID.class),
                rs.getBytes("author_key"),
                rs.getString("sent_at"),
                removal == null ? rs.getString("body") : null, // hidden during a hold, gone after
                rs.getBytes("signature"),
                rs.getBytes("handwriting_hash"),
                rs.getDouble("handwriting_score"),
                new LedgerEntry(
                        rs.getLong("seq"),
                        rs.getBytes("prev_hash"),
                        rs.getBytes("payload_hash"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        rs.getBytes("entry_hash")),
                removal,
                rs.getInt("version"),
                rs.getBoolean("replay"),
                // Hidden during a hold, gone after, like the body.
                removal == null && rs.getInt("version") == 2 ? pages(letterHash) : List.of());
    }

    /** Records the author's appeal of an active hold; false if there's none to appeal (or it's late). */
    boolean appeal(byte[] letterHash, UUID authorId, String text, Instant at) {
        return jdbc.sql("""
                UPDATE open_letter_holds h SET appeal = :text, appealed_at = :at
                  FROM open_letters o
                 WHERE h.letter_hash = :hash AND o.letter_hash = h.letter_hash AND o.author_id = :author
                   AND h.decision IS NULL AND h.appealed_at IS NULL AND h.delete_after > :at
                """)
                .param("text", text).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("hash", letterHash).param("author", authorId)
                .update() == 1;
    }

    /** @return false if this reader (when signed in) already reported the letter */
    boolean report(byte[] letterHash, UUID reporterId, String category, String note, Instant at) {
        try {
            jdbc.sql("""
                    INSERT INTO open_letter_reports (letter_hash, reporter_id, category, note, created_at)
                    VALUES (:hash, :reporter, :category, :note, :at)
                    """)
                    .param("hash", letterHash).param("reporter", reporterId).param("category", category)
                    .param("note", note).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }
}
