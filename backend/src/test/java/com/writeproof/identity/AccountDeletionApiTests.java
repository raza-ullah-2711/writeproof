package com.writeproof.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.auth.LoginMessage;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.ledger.LedgerService;
import com.writeproof.letters.LetterEnvelope;
import com.writeproof.letters.LetterHashing;
import com.writeproof.openletters.OpenLetterHashing;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Account deletion, "close and forget" (docs/launch-policies.md). */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountDeletionApiTests {

    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LedgerService ledger;

    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(7007);
    private long seed = 10;

    private String b64(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    private ResponseEntity<Map> call(TestWallet as, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, as.headers()), Map.class);
    }

    private String handwritingNow() throws Exception {
        HandwritingSample s = hand.genuine(seed++, "pen");
        return json.writeValueAsString(new HandwritingSample(s.format(), s.version(), Instant.now().toString(),
                s.device(), s.width(), s.height(), s.strokes()));
    }

    private ResponseEntity<Map> sendLetter(TestWallet from, TestWallet to) throws Exception {
        String sentAt = SENT_AT.format(Instant.now());
        String handwriting = handwritingNow();
        LetterEnvelope envelope = new LetterEnvelope(1, b64(12), b64(200),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48)),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48)));
        String header = LetterHashing.headerV2(from.publicKey, to.publicKey, sentAt,
                LetterHashing.handwritingHash(handwriting));
        Map<String, Object> body = new HashMap<>();
        body.put("recipientPublicKey", Base64Url.encode(to.publicKey));
        body.put("sentAt", sentAt);
        body.put("envelope", envelope);
        body.put("handwriting", handwriting);
        body.put("signature", Base64Url.encode(TestWallet.sign(from.identity.getPrivate(),
                LetterHashing.signedMessage(LetterHashing.letterHash(header, envelope)))));
        return call(from, HttpMethod.POST, "/api/letters", body);
    }

    private String publish(TestWallet author, String text) throws Exception {
        String handwriting = handwritingNow();
        String sentAt = SENT_AT.format(Instant.now());
        byte[] hash = OpenLetterHashing.letterHash(author.publicKey, sentAt, LetterHashing.handwritingHash(handwriting),
                text);
        ResponseEntity<Map> published = call(author, HttpMethod.POST, "/api/me/open-letters", Map.of("sentAt", sentAt,
                "body", text, "handwriting", handwriting, "signature",
                Base64Url.encode(TestWallet.sign(author.identity.getPrivate(), LetterHashing.signedMessage(hash)))));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Base64Url.encode(hash);
    }

    private ResponseEntity<Map> requestDeletion(TestWallet as, UUID accountId, Instant at, boolean signItRight)
            throws Exception {
        String requestedAt = at.toString();
        byte[] message = DeletionMessage.of(signItRight ? accountId : UUID.randomUUID(), requestedAt);
        return call(as, HttpMethod.POST, "/api/me/deletion", Map.of("requestedAt", requestedAt,
                "signature", Base64Url.encode(TestWallet.sign(as.identity.getPrivate(), message))));
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    @Test
    void deletingAnAccountForgetsEverythingDeletableAndKeepsLettersImmutable() throws Exception {
        TestWallet alice = TestWallet.create(rest);
        TestWallet bob = TestWallet.create(rest);
        UUID aliceId = UUID.fromString((String) call(alice, HttpMethod.GET, "/api/me", null).getBody().get("accountId"));
        alice.registerEncryptionKey(rest);
        bob.registerEncryptionKey(rest);
        assertThat(call(alice, HttpMethod.POST, "/api/handwriting/enrolment", Map.of("samples",
                List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen")))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(sendLetter(alice, bob).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String openLetter = publish(alice, "A public note");
        call(alice, HttpMethod.POST, "/api/open-letters/" + openLetter + "/reports", Map.of("category", "other"));
        String lookupId = b64(32);
        assertThat(call(alice, HttpMethod.PUT, "/api/me/backup", Map.of("lookupId", lookupId, "blob",
                Map.of("format", "writeproof.wallet-backup", "version", 1, "publicKey", Base64Url.encode(alice.publicKey),
                        "salt", b64(32), "iv", b64(12), "ciphertext", b64(600), "createdAt", "2026-10-03T12:00:00.000Z")))
                .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(call(alice, HttpMethod.PUT, "/api/me/contacts", Map.of("baseVersion", 0, "ciphertext", b64(100)))
                .getStatusCode().is2xxSuccessful()).isTrue();
        call(alice, HttpMethod.POST, "/api/calibration/consent", null);
        long ledgerSize = ledger.size();
        // Everything that should go is there to begin with.
        for (String table : List.of("handwriting_enrolments", "handwriting_history", "calibration_contributors",
                "wallet_backups", "contact_books")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE account_id = :id", aliceId)).as(table).isPositive();
        }
        assertThat(count("SELECT count(*) FROM open_letter_reports WHERE reporter_id = :id", aliceId)).isOne();

        // Only a fresh request, signed by this wallet for this account, deletes it.
        assertThat(requestDeletion(alice, aliceId, Instant.now(), false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(requestDeletion(alice, aliceId, Instant.now().minusSeconds(3600), true).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("SELECT count(*) FROM accounts WHERE id = :id AND deleted_at IS NULL", aliceId)).isOne();
        assertThat(requestDeletion(alice, aliceId, Instant.now(), true).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Forgotten: everything the server could delete.
        for (String table : List.of("handwriting_enrolments", "handwriting_history", "calibration_contributors",
                "wallet_backups", "contact_books")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE account_id = :id", aliceId)).as(table).isZero();
        }
        assertThat(count("SELECT count(*) FROM accounts WHERE id = :id AND encryption_key IS NULL"
                + " AND deleted_at IS NOT NULL", aliceId)).isOne();
        assertThat(count("SELECT count(*) FROM open_letter_reports WHERE reporter_id = :id", aliceId)).isZero();
        assertThat(rest.getForEntity("/api/backups/" + lookupId, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Their open letter is withdrawn: the text is gone, the record and its ledger entry stay.
        assertThat(count("SELECT count(*) FROM open_letters WHERE author_id = :id AND body IS NULL", aliceId)).isOne();
        assertThat(jdbc.sql("SELECT category FROM open_letter_removals WHERE letter_hash = :h")
                .param("h", Base64Url.decode(openLetter)).query(String.class).single()).isEqualTo("withdrawn");
        assertThat(ledger.size()).isEqualTo(ledgerSize);

        // Closed: no session, no sign-in, no coming back, no new letters.
        assertThat(call(alice, HttpMethod.GET, "/api/me", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/auth/challenge", Map.of("publicKey", Base64Url.encode(alice.publicKey)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(rest.postForEntity("/api/accounts", Map.of("publicKey", Base64Url.encode(alice.publicKey)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(call(bob, HttpMethod.GET, "/api/accounts/by-key/" + Base64Url.encode(alice.publicKey), null)
                .getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(sendLetter(bob, alice).getStatusCode()).isEqualTo(HttpStatus.GONE);

        // Bob keeps the letter Alice sent him, marked as from a deleted account.
        List<Map<String, Object>> inbox = rest.exchange("/api/letters/inbox", HttpMethod.GET,
                new HttpEntity<>(bob.headers()), List.class).getBody();
        assertThat(inbox).hasSize(1);
        assertThat((Map<String, Object>) inbox.getFirst().get("sender")).containsEntry("deleted", true);
        assertThat((Map<String, Object>) inbox.getFirst().get("recipient")).containsEntry("deleted", false);
    }

    @Test
    void theSignedMessageIsFixed() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(new String(DeletionMessage.of(id, "2026-10-03T12:00:00.000Z")))
                .isEqualTo("writeproof/delete-account/v1\n00000000-0000-0000-0000-000000000001\n2026-10-03T12:00:00.000Z");
        assertThat(DeletionMessage.DOMAIN).isNotEqualTo(LoginMessage.DOMAIN);
    }
}
