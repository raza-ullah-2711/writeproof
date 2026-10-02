package com.writeproof.letters;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
class LettersApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    private final SecureRandom random = new SecureRandom();
    private TestWallet alice;
    private TestWallet bob;

    @BeforeEach
    void wallets() throws Exception {
        alice = TestWallet.create(rest);
        bob = TestWallet.create(rest);
        alice.registerEncryptionKey(rest);
        bob.registerEncryptionKey(rest);
    }

    /** The server can't decrypt, so random bytes of the right sizes stand in for real ciphertext. */
    private LetterEnvelope envelope() {
        return new LetterEnvelope(1, b64(12), b64(200),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48)),
                new LetterEnvelope.WrappedKey(b64(32), b64(12), b64(48)));
    }

    private String b64(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private Map<String, Object> signedLetter(TestWallet from, TestWallet to, String sentAt, LetterEnvelope envelope)
            throws Exception {
        byte[] hash = LetterHashing.letterHash(LetterHashing.header(from.publicKey, to.publicKey, sentAt), envelope);
        byte[] signature = TestWallet.sign(from.identity.getPrivate(), LetterHashing.signedMessage(hash));
        Map<String, Object> body = new HashMap<>();
        body.put("recipientPublicKey", Base64Url.encode(to.publicKey));
        body.put("sentAt", sentAt);
        body.put("envelope", envelope);
        body.put("signature", Base64Url.encode(signature));
        return body;
    }

    private ResponseEntity<Map> send(TestWallet as, Map<String, Object> letter) {
        return rest.exchange("/api/letters", HttpMethod.POST, new HttpEntity<>(letter, as.headers()), Map.class);
    }

    private ResponseEntity<List> list(TestWallet as, String box) {
        return rest.exchange("/api/letters/" + box, HttpMethod.GET, new HttpEntity<>(as.headers()), List.class);
    }

    @Test
    void aSignedLetterIsAppendedToTheLedgerAndDeliveredAsCiphertext() throws Exception {
        LetterEnvelope envelope = envelope();
        String sentAt = now();
        ResponseEntity<Map> sent = send(alice, signedLetter(alice, bob, sentAt, envelope));

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> letter = sent.getBody();
        byte[] expectedHash = LetterHashing.letterHash(LetterHashing.header(alice.publicKey, bob.publicKey, sentAt), envelope);
        assertThat(letter.get("letterHash")).isEqualTo(Base64Url.encode(expectedHash));
        Map<String, Object> ledger = (Map<String, Object>) letter.get("ledger");
        assertThat(ledger.get("payloadHash")).isEqualTo(Base64Url.encode(expectedHash));
        byte[] entryHash = LedgerHashing.entryHash(((Number) ledger.get("seq")).longValue(),
                Base64Url.decode((String) ledger.get("prevHash")), expectedHash,
                Instant.ofEpochMilli(((Number) ledger.get("recordedAtMillis")).longValue()));
        assertThat(ledger.get("entryHash")).isEqualTo(Base64Url.encode(entryHash));

        List<Map<String, Object>> inbox = list(bob, "inbox").getBody();
        assertThat(inbox).extracting(l -> l.get("letterId")).contains(letter.get("letterId"));
        assertThat(list(alice, "sent").getBody()).extracting(l -> ((Map) l).get("letterId")).contains(letter.get("letterId"));
        assertThat(list(alice, "inbox").getBody()).isEmpty();

        // What's stored is exactly the ciphertext envelope that was signed.
        String stored = jdbc.sql("SELECT envelope::text FROM letters WHERE id = CAST(:id AS uuid)")
                .param("id", letter.get("letterId")).query(String.class).single();
        assertThat(stored).contains(envelope.ciphertext());
    }

    @Test
    void onlyTheSenderAndRecipientCanReadALetter() throws Exception {
        String id = (String) send(alice, signedLetter(alice, bob, now(), envelope())).getBody().get("letterId");
        TestWallet eve = TestWallet.create(rest);

        assertThat(get(alice, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(bob, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(eve, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lettersCannotBeEditedOrUnsent() throws Exception {
        String id = (String) send(alice, signedLetter(alice, bob, now(), envelope())).getBody().get("letterId");

        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            ResponseEntity<Map> response = rest.exchange("/api/letters/" + id, method,
                    new HttpEntity<>(Map.of(), alice.headers()), Map.class);
            assertThat(response.getStatusCode()).as(method.name()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        // Not even with direct database access.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM letters WHERE id = CAST(:id AS uuid)").param("id", id).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void aLetterSignedByAnotherKeyIsRejected() throws Exception {
        Map<String, Object> forged = signedLetter(bob, bob, now(), envelope());
        forged.put("signature", signedLetter(alice, bob, (String) forged.get("sentAt"),
                (LetterEnvelope) forged.get("envelope")).get("signature"));

        // Bob's session claims to send, but the signature is Alice's: it can't verify under Bob's key.
        assertThat(send(bob, forged).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void changingTheCiphertextAfterSigningBreaksTheSignature() throws Exception {
        Map<String, Object> letter = signedLetter(alice, bob, now(), envelope());
        LetterEnvelope e = (LetterEnvelope) letter.get("envelope");
        letter.put("envelope", new LetterEnvelope(1, e.iv(), b64(200), e.recipientKey(), e.senderKey()));

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aStaleOrMalformedTimestampIsRejected() throws Exception {
        String old = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS).toString();
        assertThat(send(alice, signedLetter(alice, bob, old, envelope())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(send(alice, signedLetter(alice, bob, "2026-10-02 12:00", envelope())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void theSameLetterCannotBeSentTwice() throws Exception {
        Map<String, Object> letter = signedLetter(alice, bob, now(), envelope());

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void malformedEnvelopesAndUnknownRecipientsAreRejected() throws Exception {
        Map<String, Object> badIv = signedLetter(alice, bob, now(),
                new LetterEnvelope(1, b64(8), b64(200), envelope().recipientKey(), envelope().senderKey()));
        assertThat(send(alice, badIv).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        TestWallet stranger = TestWallet.create(rest); // registered but never set an encryption key
        assertThat(send(alice, signedLetter(alice, stranger, now(), envelope())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map<String, Object> nobody = signedLetter(alice, bob, now(), envelope());
        nobody.put("recipientPublicKey", b64(32));
        assertThat(send(alice, nobody).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theEncryptionKeyMustBeSignedByTheIdentityKeyAndIsSetOnce() throws Exception {
        TestWallet carol = TestWallet.create(rest);
        byte[] key = new byte[32];
        random.nextBytes(key);
        byte[] wrongSigner = TestWallet.sign(alice.identity.getPrivate(),
                com.writeproof.identity.EncryptionKeyBinding.of(carol.publicKey, key));

        ResponseEntity<Map> rejected = rest.exchange("/api/me/encryption-key", HttpMethod.PUT, new HttpEntity<>(
                Map.of("encryptionKey", Base64Url.encode(key), "signature", Base64Url.encode(wrongSigner)),
                carol.headers()), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        byte[] registered = carol.registerEncryptionKey(rest);
        Map<String, Object> lookup = rest.exchange("/api/accounts/by-key/" + Base64Url.encode(carol.publicKey),
                HttpMethod.GET, new HttpEntity<>(alice.headers()), Map.class).getBody();
        assertThat(lookup).containsEntry("encryptionKey", Base64Url.encode(registered));

        byte[] replacement = new byte[32];
        random.nextBytes(replacement);
        byte[] signed = TestWallet.sign(carol.identity.getPrivate(),
                com.writeproof.identity.EncryptionKeyBinding.of(carol.publicKey, replacement));
        ResponseEntity<Map> conflict = rest.exchange("/api/me/encryption-key", HttpMethod.PUT, new HttpEntity<>(
                Map.of("encryptionKey", Base64Url.encode(replacement), "signature", Base64Url.encode(signed)),
                carol.headers()), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theLedgerCanBeReadAndVerified() throws Exception {
        send(alice, signedLetter(alice, bob, now(), envelope()));

        List<Map<String, Object>> entries = rest.exchange("/api/ledger/entries?from=1&limit=1000", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), List.class).getBody();
        assertThat(entries).isNotEmpty();
        assertThat(entries.getFirst().get("seq")).isEqualTo(1);
        Map<String, Object> check = rest.exchange("/api/ledger/verify", HttpMethod.GET,
                new HttpEntity<>(alice.headers()), Map.class).getBody();
        assertThat(check).containsEntry("intact", true);
    }

    @Test
    void requiresAuthentication() {
        assertThat(rest.getForEntity("/api/letters/inbox", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/ledger/entries", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> get(TestWallet as, String id) {
        return rest.exchange("/api/letters/" + id, HttpMethod.GET, new HttpEntity<>(as.headers()), Map.class);
    }
}
