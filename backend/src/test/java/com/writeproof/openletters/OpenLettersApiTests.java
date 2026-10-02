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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class OpenLettersApiTests {

    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private final AtomicLong seeds = new AtomicLong(5000);
    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(3003);
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

    private Map<String, Object> signed(TestWallet as, String body, String handwriting) throws Exception {
        String sentAt = SENT_AT.format(Instant.now());
        byte[] hash = OpenLetterHashing.letterHash(as.publicKey, sentAt, LetterHashing.handwritingHash(handwriting), body);
        Map<String, Object> request = new HashMap<>();
        request.put("sentAt", sentAt);
        request.put("body", body);
        request.put("signature", Base64Url.encode(TestWallet.sign(as.identity.getPrivate(),
                LetterHashing.signedMessage(hash))));
        request.put("handwriting", handwriting);
        return request;
    }

    private ResponseEntity<Map> publish(TestWallet as, Map<String, Object> request) {
        return rest.exchange("/api/me/open-letters", HttpMethod.POST, new HttpEntity<>(request, as.headers()),
                Map.class);
    }

    @Test
    void aPublishedLetterIsReadableAndVerifiableByAnyoneWithTheLink() throws Exception {
        String body = "To whom it may concern:\nI wrote this myself. ✍";
        ResponseEntity<Map> published = publish(author, signed(author, body, signsNow()));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String hash = (String) published.getBody().get("letterHash");
        assertThat(published.getHeaders().getLocation()).hasToString("/api/open-letters/" + hash);

        Map<String, Object> read = rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody(); // no login

        assertThat(read).containsEntry("body", body).containsEntry("author", Base64Url.encode(author.publicKey))
                .doesNotContainKey("handwriting").doesNotContainKey("authorId");
        byte[] recomputed = OpenLetterHashing.letterHash(author.publicKey, (String) read.get("sentAt"),
                Base64Url.decode((String) read.get("handwritingHash")), (String) read.get("body"));
        assertThat(Base64Url.encode(recomputed)).isEqualTo(hash);
        assertThat(Ed25519.verify(author.publicKey, LetterHashing.signedMessage(recomputed),
                Base64Url.decode((String) read.get("signature")))).isTrue();
        Map<String, Object> ledger = (Map<String, Object>) read.get("ledger");
        assertThat(ledger.get("payloadHash")).isEqualTo(hash);
        assertThat((Double) read.get("handwritingScore")).isBetween(0.0, 1.0);

        List<Map<String, Object>> mine = rest.exchange("/api/me/open-letters", HttpMethod.GET,
                new HttpEntity<>(author.headers()), List.class).getBody();
        assertThat(mine).extracting(l -> l.get("letterHash")).containsExactly(hash);
    }

    @Test
    void rejectsForgedSignaturesReusedHandwritingAndBadBodies() throws Exception {
        TestWallet mallory = TestWallet.create(rest);
        String handwriting = signsNow();
        Map<String, Object> forged = signed(mallory, "I am the author", handwriting);
        assertThat(publish(author, forged).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map<String, Object> request = signed(author, "First", handwriting);
        assertThat(publish(author, request).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(publish(author, request).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(publish(author, signed(author, "Second, same strokes", handwriting)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> edited = signed(author, "Original", signsNow());
        edited.put("body", "Edited after signing");
        assertThat(publish(author, edited).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(publish(author, signed(author, "x".repeat(10_001), signsNow())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(publish(author, signed(author, "   ", signsNow())).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void publishingNeedsEnrolledHandwritingAndALogin() throws Exception {
        TestWallet newcomer = TestWallet.create(rest);

        assertThat(publish(newcomer, signed(newcomer, "Hello", signsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rest.postForEntity("/api/me/open-letters", signed(author, "Hi", signsNow()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/me/open-letters", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownOrMalformedLinksAndNoWayToChangeALetter() throws Exception {
        assertThat(rest.getForEntity("/api/open-letters/" + Base64Url.encode(new byte[32]), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/api/open-letters/short", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        String hash = (String) publish(author, signed(author, "Permanent", signsNow())).getBody().get("letterHash");

        assertThat(rest.exchange("/api/open-letters/" + hash, HttpMethod.DELETE, new HttpEntity<>(author.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThatThrownBy(() -> jdbc.sql("UPDATE open_letters SET body = 'changed' WHERE letter_hash = :h")
                .param("h", Base64Url.decode(hash)).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM open_letters WHERE letter_hash = :h")
                .param("h", Base64Url.decode(hash)).update()).hasMessageContaining("append-only");
    }

    /** Shared with frontend open-letter-format.spec.ts. */
    @Test
    void formatVector() {
        byte[] author = new byte[32];
        for (int i = 0; i < 32; i++) {
            author[i] = (byte) (i + 1);
        }
        byte[] handwritingHash = LetterHashing.handwritingHash("{\"format\":\"writeproof.handwriting\"}");

        assertThat(Base64Url.encode(OpenLetterHashing.bodyHash("Dear world,\nhello. ✍")))
                .isEqualTo("h-UOIzwEcPgmIvLyKrxbUJRS7I4UHy_BCsflqL9ePYY");
        assertThat(Base64Url.encode(OpenLetterHashing.letterHash(author, "2026-10-02T12:00:00.000Z", handwritingHash,
                "Dear world,\nhello. ✍"))).isEqualTo("itdvQZnNNB8MKApaQ228VyrJ8vt9G37LpRutcNboHq4");
    }
}
