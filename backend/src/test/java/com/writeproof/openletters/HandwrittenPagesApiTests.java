package com.writeproof.openletters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.identity.Ed25519;
import com.writeproof.letters.LetterHashing;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Open letters with handwritten pages (Task 14a, docs/handwritten-pages.md). */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class HandwrittenPagesApiTests {

    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    // Unique per run and per test: one copy of a page seals one letter, across the whole database.
    private final AtomicLong seeds = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(4004);
    private final SyntheticSignatures.Writer pageHand = new SyntheticSignatures.Writer(4111);
    private TestWallet author;

    @BeforeEach
    void wallet() throws Exception {
        author = TestWallet.create(rest);
        ResponseEntity<Map> enrolled = rest.exchange("/api/handwriting/enrolment", HttpMethod.POST, new HttpEntity<>(
                Map.of("samples", List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen"))),
                author.headers()), Map.class);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private String signsNow() throws Exception {
        HandwritingSample s = hand.genuine(seeds.incrementAndGet(), "pen");
        return json.writeValueAsString(new HandwritingSample(s.format(), s.version(), Instant.now().toString(),
                s.device(), s.width(), s.height(), s.strokes()));
    }

    /** A fresh page of three lines of live-looking writing. */
    private HandwrittenPage freshPage(Instant capturedAt) {
        long seed = seeds.incrementAndGet() * 10;
        return TestPages.page(capturedAt, "fountain", "blue-black", List.of(pageHand.genuine(seed, "pen"),
                pageHand.genuine(seed + 1, "pen"), pageHand.genuine(seed + 2, "pen")));
    }

    /** A publish request whose wallet signature covers the transcript, the pages and the replay choice. */
    private Map<String, Object> signed(String transcript, List<HandwrittenPage> pages, boolean replay)
            throws Exception {
        String handwriting = signsNow();
        String sentAt = SENT_AT.format(Instant.now());
        List<byte[]> pageHashes = new ArrayList<>();
        for (HandwrittenPage p : pages) {
            pageHashes.add(HandwrittenPage.pageHash(p.inkHash(), p.computeTimingHash()));
        }
        byte[] hash = OpenLetterHashing.letterHashV2(author.publicKey, sentAt,
                LetterHashing.handwritingHash(handwriting), transcript, OpenLetterHashing.pagesHash(replay, pageHashes));
        Map<String, Object> request = new HashMap<>();
        request.put("sentAt", sentAt);
        request.put("body", transcript);
        request.put("signature", Base64Url.encode(TestWallet.sign(author.identity.getPrivate(),
                LetterHashing.signedMessage(hash))));
        request.put("handwriting", handwriting);
        request.put("pages", pages);
        request.put("replay", replay);
        return request;
    }

    private ResponseEntity<Map> publish(Map<String, Object> request) {
        return rest.exchange("/api/me/open-letters", HttpMethod.POST, new HttpEntity<>(request, author.headers()),
                Map.class);
    }

    private HandwrittenPage asPage(Object published) {
        return json.convertValue(published, HandwrittenPage.class);
    }

    @Test
    void anyoneCanCheckThePagesAgainstWhatTheAuthorSignedWhileTheTimingStaysPrivate() throws Exception {
        List<HandwrittenPage> pages = List.of(freshPage(Instant.now()), freshPage(Instant.now()));
        ResponseEntity<Map> published = publish(signed("", pages, false));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String hash = (String) published.getBody().get("letterHash");

        Map<String, Object> read = rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody(); // no login

        assertThat(read).containsEntry("version", 2).containsEntry("replay", false).containsEntry("body", "");
        List<Map<String, Object>> shown = (List<Map<String, Object>>) read.get("pages");
        assertThat(shown).hasSize(2);
        assertThat(shown).allSatisfy(p -> assertThat(p).containsKeys("ink", "pen", "timingHash")
                .doesNotContainKeys("timing", "capturedAt"));
        // The reader's check: ink + timing hash give each page hash, and those the signed letter hash.
        List<byte[]> pageHashes = new ArrayList<>();
        for (Map<String, Object> p : shown) {
            HandwrittenPage page = asPage(p).validate(false);
            pageHashes.add(HandwrittenPage.pageHash(page.inkHash(), Base64Url.decode(page.timingHash())));
        }
        byte[] recomputed = OpenLetterHashing.letterHashV2(author.publicKey, (String) read.get("sentAt"),
                Base64Url.decode((String) read.get("handwritingHash")), "",
                OpenLetterHashing.pagesHash(false, pageHashes));
        assertThat(Base64Url.encode(recomputed)).isEqualTo(hash);
        assertThat(Ed25519.verify(author.publicKey, LetterHashing.signedMessage(recomputed),
                Base64Url.decode((String) read.get("signature")))).isTrue();
        // Not just hidden: the server never kept the timing.
        assertThat(jdbc.sql("SELECT count(*) FROM open_letter_pages WHERE letter_hash = :h AND timing_json IS NULL")
                .param("h", Base64Url.decode(hash)).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void withReplayTheTimingIsPublishedAndMatchesItsHash() throws Exception {
        HandwrittenPage page = freshPage(Instant.now());
        String hash = (String) publish(signed("Dear Grandma,", List.of(page), true)).getBody().get("letterHash");

        Map<String, Object> read = rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody();

        assertThat(read).containsEntry("replay", true).containsEntry("body", "Dear Grandma,");
        HandwrittenPage shown = asPage(((List<?>) read.get("pages")).getFirst()).validate(true);
        assertThat(shown.capturedAt()).isEqualTo(page.capturedAt());
        assertThat(Base64Url.encode(shown.computeTimingHash())).isEqualTo(shown.timingHash());
        assertThat(shown.ink()).isDeepEqualTo(page.ink());
    }

    @Test
    void rejectsStaleScriptedRepeatedOrTooManyPagesAndPagesChangedAfterSigning() throws Exception {
        HandwrittenPage stale = freshPage(Instant.now().minus(Duration.ofHours(13)));
        assertThat(publish(signed("", List.of(stale), false)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        HandwrittenPage scripted = TestPages.page(Instant.now(), "pencil", "ink",
                List.of(SyntheticSignatures.bot(pageHand, "pen"), SyntheticSignatures.bot(pageHand, "pen")));
        ResponseEntity<Map> robot = publish(signed("", List.of(scripted), false));
        assertThat(robot.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        HandwrittenPage page = freshPage(Instant.now());
        assertThat(publish(signed("", List.of(page, page), false)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(publish(signed("", List.of(page), false)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(publish(signed("Again", List.of(page), false)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        List<HandwrittenPage> five = List.of(freshPage(Instant.now()), freshPage(Instant.now()),
                freshPage(Instant.now()), freshPage(Instant.now()), freshPage(Instant.now()));
        assertThat(publish(signed("", five, false)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Signed with replay off, sent claiming replay on: the hash no longer matches the signature.
        Map<String, Object> flipped = signed("", List.of(freshPage(Instant.now())), false);
        flipped.put("replay", true);
        assertThat(publish(flipped).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aTakedownBlanksThePagesWithTheTextAndNothingElseCanChangeThem() throws Exception {
        String hash = (String) publish(signed("", List.of(freshPage(Instant.now())), true)).getBody()
                .get("letterHash");
        byte[] h = Base64Url.decode(hash);

        assertThatThrownBy(() -> jdbc.sql("UPDATE open_letter_pages SET page_json = NULL WHERE letter_hash = :h")
                .param("h", h).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM open_letter_pages WHERE letter_hash = :h")
                .param("h", h).update()).hasMessageContaining("append-only");

        jdbc.sql("""
                INSERT INTO open_letter_removals (letter_hash, category, removed_at, removed_by)
                VALUES (:h, 'spam', now(), :by)
                """).param("h", h).param("by", author.publicKey).update();
        jdbc.sql("UPDATE open_letters SET body = NULL WHERE letter_hash = :h").param("h", h).update();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM open_letter_pages
                 WHERE letter_hash = :h AND page_json IS NULL AND timing_json IS NULL
                """).param("h", h).query(Integer.class).single()).isEqualTo(1);
        Map<String, Object> read = rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody();
        assertThat(read.get("body")).isNull();
        assertThat(read.get("pages")).isNull();
        assertThat(read.get("removed")).isNotNull();
    }
}
