package com.writeproof.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Production settings: real rate limits, scores hidden. */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "writeproof.rate-limits.scale=1",
        "writeproof.handwriting.expose-scores=false"})
@SuppressWarnings({"rawtypes", "unchecked"})
class HardeningTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TokenBucketRateLimiter limiter;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private final SecureRandom random = new SecureRandom();
    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(77);

    @BeforeEach
    void freshLimits() {
        limiter.clear();
    }

    private String b64(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    private static HandwritingSample now(HandwritingSample s) {
        return new HandwritingSample(s.format(), s.version(), Instant.now().toString(), s.device(), s.width(),
                s.height(), s.strokes());
    }

    private void enrol(TestWallet wallet) {
        ResponseEntity<Map> enrolled = rest.exchange("/api/handwriting/enrolment", HttpMethod.POST, new HttpEntity<>(
                Map.of("samples", List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen"))),
                wallet.headers()), Map.class);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void apiResponsesCarrySecurityHeaders() {
        HttpHeaders headers = rest.getForEntity("/actuator/health", String.class).getHeaders();

        assertThat(headers.getFirst("Content-Security-Policy")).contains("default-src 'none'", "frame-ancestors 'none'");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }

    @Test
    void loginChallengesAreLimitedPerAddress() {
        Map<String, String> body = Map.of("publicKey", b64(32)); // unknown key: 404s still count
        for (int i = 0; i < 30; i++) {
            assertThat(rest.postForEntity("/api/auth/challenge", body, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        ResponseEntity<Map> limited = rest.postForEntity("/api/auth/challenge", body, Map.class);

        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 600L);
        assertThat(limited.getBody()).containsEntry("status", 429);
        // Other endpoints have their own budgets.
        assertThat(rest.postForEntity("/api/accounts", Map.of("publicKey", b64(32)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void perAccountLimitsDoNotAffectOtherAccounts() throws Exception {
        TestWallet alice = TestWallet.create(rest);
        TestWallet bob = TestWallet.create(rest);
        Map<String, Object> upload = Map.of("lookupId", b64(32), "blob", Map.of("format", "zip"));
        for (int i = 0; i < 10; i++) {
            rest.exchange("/api/me/backup", HttpMethod.PUT, new HttpEntity<>(upload, alice.headers()), Map.class);
        }

        assertThat(rest.exchange("/api/me/backup", HttpMethod.PUT, new HttpEntity<>(upload, alice.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rest.exchange("/api/me/backup", HttpMethod.PUT, new HttpEntity<>(upload, bob.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // reached the endpoint
    }

    @Test
    void oversizedRequestBodiesAreRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] huge = new byte[(int) RequestSizeLimitFilter.MAX_BYTES + 1];

        ResponseEntity<String> response = rest.exchange("/api/accounts", HttpMethod.POST,
                new HttpEntity<>(huge, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void verificationHidesScoresButKeepsLivenessFlags() throws Exception {
        TestWallet alice = TestWallet.create(rest);
        enrol(alice);

        Map<String, Object> genuine = rest.exchange("/api/handwriting/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("sample", hand.genuine(50, "pen")), alice.headers()), Map.class).getBody();
        Map<String, Object> bot = rest.exchange("/api/handwriting/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("sample", SyntheticSignatures.bot(hand, "pen")), alice.headers()),
                Map.class).getBody();

        assertThat(genuine).containsEntry("verified", true).containsEntry("score", null)
                .containsEntry("shapeScore", null).containsEntry("durationScore", null);
        assertThat(bot).containsEntry("verified", false).containsEntry("score", null);
        assertThat((List<Object>) bot.get("livenessFlags")).contains("CONSTANT_VELOCITY");
    }

    @Test
    void handwritingIsStoredEncrypted() throws Exception {
        TestWallet alice = TestWallet.create(rest);
        enrol(alice);

        byte[] stored = jdbc.sql("""
                SELECT e.samples_encrypted FROM handwriting_enrolments e
                  JOIN accounts a ON a.id = e.account_id WHERE a.public_key = :key
                """).param("key", alice.publicKey).query(byte[].class).single();

        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("writeproof.handwriting").doesNotContain("strokes");
        assertThat(rest.exchange("/api/handwriting/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("sample", hand.genuine(51, "pen")), alice.headers()), Map.class).getBody())
                .containsEntry("verified", true); // still decrypts and matches
    }

    @Test
    void deletingAnEnrolmentTakesAFreshMatchingSignature() throws Exception {
        TestWallet alice = TestWallet.create(rest);
        enrol(alice);
        HandwritingSample forged = now(SyntheticSignatures.skilledForgery(hand, 9, "pen"));

        ResponseEntity<Map> refused = delete(alice, forged);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody()).containsEntry("match", false).doesNotContainKey("score");
        assertThat(enrolled(alice)).isTrue();

        assertThat(delete(alice, now(hand.genuine(60, "pen"))).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(enrolled(alice)).isFalse();

        // Free to enrol again afterwards.
        enrol(alice);
        assertThat(enrolled(alice)).isTrue();
    }

    private ResponseEntity<Map> delete(TestWallet wallet, HandwritingSample sample) {
        return rest.exchange("/api/handwriting/enrolment/deletion", HttpMethod.POST,
                new HttpEntity<>(Map.of("sample", sample), wallet.headers()), Map.class);
    }

    private boolean enrolled(TestWallet wallet) {
        return (Boolean) rest.exchange("/api/handwriting/enrolment", HttpMethod.GET,
                new HttpEntity<>(wallet.headers()), Map.class).getBody().get("enrolled");
    }
}
