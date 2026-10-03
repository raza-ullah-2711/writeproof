package com.writeproof.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.letters.LetterEnvelope;
import com.writeproof.security.TokenBucketRateLimiter;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class AccountAdminApiTests {

    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private TokenBucketRateLimiter limiter;

    private final SecureRandom random = new SecureRandom();
    private TestWallet admin;
    private TestWallet user;

    @BeforeEach
    void wallets() throws Exception {
        admin = TestWallet.bootstrapAdmin(rest);
        user = TestWallet.create(rest);
        user.registerEncryptionKey(rest);
    }

    private UUID id(TestWallet w) {
        return jdbc.sql("SELECT id FROM accounts WHERE public_key = :k").param("k", w.publicKey).query(UUID.class)
                .single();
    }

    private ResponseEntity<Map> call(TestWallet as, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, as.headers()), Map.class);
    }

    private ResponseEntity<List> list(TestWallet as, String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(as.headers()), List.class);
    }

    private String b64(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    /** A well-formed send that fails only on its (bogus) signature: 422 unless suspended (403). */
    private ResponseEntity<Map> trySendLetter(TestWallet from, TestWallet to) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("recipientPublicKey", Base64Url.encode(to.publicKey));
        body.put("sentAt", SENT_AT.format(Instant.now()));
        body.put("envelope", new LetterEnvelope(1, b64(12), b64(200),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48)),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48))));
        body.put("signature", b64(64));
        body.put("handwriting", json.writeValueAsString(new SyntheticSignatures.Writer(9).genuine(1, "pen")));
        return call(from, HttpMethod.POST, "/api/letters", body);
    }

    private ResponseEntity<Map> tryPublish(TestWallet from) throws Exception {
        return call(from, HttpMethod.POST, "/api/me/open-letters", Map.of("sentAt", SENT_AT.format(Instant.now()),
                "body", "Hello", "signature", b64(64),
                "handwriting", json.writeValueAsString(new SyntheticSignatures.Writer(9).genuine(2, "pen"))));
    }

    @Test
    void findsAccountsByAddressPrefixAndShowsMetadataOnly() {
        String address = Base64Url.encode(user.publicKey);

        List<Map<String, Object>> found = list(admin, "/api/admin/accounts?query=" + address.substring(0, 10)).getBody();
        List<Map<String, Object>> newest = list(admin, "/api/admin/accounts?limit=5").getBody();

        assertThat(found).extracting(a -> a.get("publicKey")).contains(address);
        assertThat(newest).hasSizeLessThanOrEqualTo(5);
        Map<String, Object> detail = call(admin, HttpMethod.GET, "/api/admin/accounts/" + id(user), null).getBody();
        Map<String, Object> account = (Map<String, Object>) detail.get("account");
        assertThat(account).containsEntry("publicKey", address).containsEntry("canReceiveLetters", true)
                .containsEntry("enrolled", false).containsEntry("lettersSent", 0).containsEntry("suspension", null)
                .containsEntry("role", null);
        assertThat(detail).containsKeys("lastLetterAt", "usesContacts", "history");
        assertThat(detail.toString()).doesNotContain("ciphertext").doesNotContain("encryptionKey\"");
        assertThat(call(admin, HttpMethod.GET, "/api/admin/accounts?query=ab", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.GET, "/api/admin/accounts/" + UUID.randomUUID(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void suspensionBlocksSendingButNotReadingAndIsAudited() throws Exception {
        TestWallet friend = TestWallet.create(rest);
        friend.registerEncryptionKey(rest);
        assertThat(trySendLetter(user, friend).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(call(admin, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/suspension",
                Map.of("reason", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/suspension",
                Map.of("reason", "Spam reports")).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(admin, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/suspension",
                Map.of("reason", "Again")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> blocked = trySendLetter(user, friend);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) blocked.getBody().get("detail")).contains("suspended").contains("Spam reports");
        assertThat(tryPublish(user).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(list(user, "/api/letters/inbox").getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> status = call(user, HttpMethod.GET, "/api/me/status", null).getBody();
        assertThat((Map<String, Object>) status.get("suspension")).containsEntry("reason", "Spam reports");

        assertThat(call(admin, HttpMethod.DELETE, "/api/admin/accounts/" + id(user) + "/suspension", null)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(admin, HttpMethod.DELETE, "/api/admin/accounts/" + id(user) + "/suspension", null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(trySendLetter(user, friend).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(call(user, HttpMethod.GET, "/api/me/status", null).getBody()).containsEntry("suspension", null);

        List<Map<String, Object>> history = (List<Map<String, Object>>) call(admin, HttpMethod.GET,
                "/api/admin/accounts/" + id(user), null).getBody().get("history");
        assertThat(history).extracting(e -> e.get("action")).containsExactly("account.reinstated", "account.suspended");
        assertThat((Map<String, Object>) history.get(1).get("detail")).containsEntry("reason", "Spam reports");
        assertThat(history.get(1)).containsEntry("actor", Base64Url.encode(admin.publicKey));
    }

    @Test
    void adminsCantBeSuspendedAndModeratorsCantManageAccounts() throws Exception {
        assertThat(call(admin, HttpMethod.POST, "/api/admin/accounts/" + id(admin) + "/suspension",
                Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        TestWallet moderator = TestWallet.create(rest);
        jdbc.sql("INSERT INTO admin_roles (public_key, role, granted_at) VALUES (:k, 'MODERATOR', :at)")
                .param("k", moderator.publicKey).param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        assertThat(call(moderator, HttpMethod.GET, "/api/admin/accounts", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(moderator, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/suspension",
                Map.of("reason", "x")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(user, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/sign-out", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void forcedSignOutEndsExistingSessionsButNotNewOnes() throws Exception {
        assertThat(call(user, HttpMethod.GET, "/api/me", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> out = call(admin, HttpMethod.POST, "/api/admin/accounts/" + id(user) + "/sign-out", null);
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(call(user, HttpMethod.GET, "/api/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Instant cutoff = Instant.parse((String) out.getBody().get("tokensBefore"));
        while (Instant.now().isBefore(cutoff)) {
            Thread.sleep(50); // tokens carry whole seconds; a sign-in after the cutoff second is fresh
        }
        TestWallet again = TestWallet.create(rest, user.identity);
        assertThat(call(again, HttpMethod.GET, "/api/me", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(admin, HttpMethod.GET, "/api/admin/me", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resettingRateLimitsClearsOnlyThatAccountsBuckets() {
        String mine = "send-letter:" + id(user);
        String theirs = "send-letter:" + UUID.randomUUID();
        for (String key : List.of(mine, theirs)) {
            limiter.tryConsume(key, 1, Duration.ofHours(1));
            assertThat(limiter.tryConsume(key, 1, Duration.ofHours(1)).allowed()).isFalse();
        }

        Map<String, Object> reset = call(admin, HttpMethod.POST,
                "/api/admin/accounts/" + id(user) + "/rate-limits/reset", null).getBody();

        assertThat(((Number) reset.get("buckets")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(limiter.tryConsume(mine, 1, Duration.ofHours(1)).allowed()).isTrue();
        assertThat(limiter.tryConsume(theirs, 1, Duration.ofHours(1)).allowed()).isFalse();
    }
}
