package com.writeproof.letters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.identity.EncryptionKeyBinding;
import com.writeproof.ledger.LedgerHashing;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
class LettersApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong seeds = new AtomicLong(1000);
    private final SyntheticSignatures.Writer aliceHand = new SyntheticSignatures.Writer(1001);
    private TestWallet alice;
    private TestWallet bob;

    @BeforeEach
    void wallets() throws Exception {
        alice = TestWallet.create(rest);
        bob = TestWallet.create(rest);
        alice.registerEncryptionKey(rest);
        bob.registerEncryptionKey(rest);
        enrol(alice, aliceHand);
    }

    private void enrol(TestWallet wallet, SyntheticSignatures.Writer hand) {
        ResponseEntity<Map> enrolled = rest.exchange("/api/handwriting/enrolment", HttpMethod.POST, new HttpEntity<>(
                Map.of("samples", List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen"))),
                wallet.headers()), Map.class);
        assertThat(enrolled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** A fresh genuine signature by Alice, captured now, as the exact JSON string that gets hashed. */
    private String aliceSignsNow() throws Exception {
        return toJson(capturedAt(aliceHand.genuine(seeds.incrementAndGet(), "pen"), Instant.now()));
    }

    private String toJson(HandwritingSample sample) throws Exception {
        return json.writeValueAsString(sample);
    }

    private static HandwritingSample capturedAt(HandwritingSample s, Instant at) {
        return new HandwritingSample(s.format(), s.version(), at.toString(), s.device(), s.width(), s.height(),
                s.strokes());
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

    /**
     * Exactly what the browser's {@code toISOString()} produces: always three fraction digits.
     * ({@code Instant.toString()} drops the fraction on whole seconds, which the server rejects.)
     */
    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static String iso(Instant instant) {
        return SENT_AT.format(instant);
    }

    private static String now() {
        return iso(Instant.now());
    }

    private Map<String, Object> signedLetter(TestWallet from, TestWallet to, String sentAt, LetterEnvelope envelope,
                                             String handwriting) throws Exception {
        String header = LetterHashing.headerV2(from.publicKey, to.publicKey, sentAt,
                LetterHashing.handwritingHash(handwriting));
        byte[] signature = TestWallet.sign(from.identity.getPrivate(),
                LetterHashing.signedMessage(LetterHashing.letterHash(header, envelope)));
        Map<String, Object> body = new HashMap<>();
        body.put("recipientPublicKey", Base64Url.encode(to.publicKey));
        body.put("sentAt", sentAt);
        body.put("envelope", envelope);
        body.put("signature", Base64Url.encode(signature));
        body.put("handwriting", handwriting);
        return body;
    }

    /** A reply: the v3 header commits to the hash of the letter it answers. */
    private Map<String, Object> signedReply(TestWallet from, TestWallet to, String inReplyTo, String handwriting)
            throws Exception {
        String sentAt = now();
        LetterEnvelope envelope = envelope();
        String header = LetterHashing.headerV3(from.publicKey, to.publicKey, sentAt,
                LetterHashing.handwritingHash(handwriting), Base64Url.decode(inReplyTo));
        byte[] signature = TestWallet.sign(from.identity.getPrivate(),
                LetterHashing.signedMessage(LetterHashing.letterHash(header, envelope)));
        Map<String, Object> body = new HashMap<>();
        body.put("recipientPublicKey", Base64Url.encode(to.publicKey));
        body.put("sentAt", sentAt);
        body.put("envelope", envelope);
        body.put("signature", Base64Url.encode(signature));
        body.put("handwriting", handwriting);
        body.put("inReplyTo", inReplyTo);
        return body;
    }

    private Map<String, Object> aliceToBob() throws Exception {
        return signedLetter(alice, bob, now(), envelope(), aliceSignsNow());
    }

    private ResponseEntity<Map> send(TestWallet as, Map<String, Object> letter) {
        return rest.exchange("/api/letters", HttpMethod.POST, new HttpEntity<>(letter, as.headers()), Map.class);
    }

    private ResponseEntity<List> list(TestWallet as, String box) {
        return rest.exchange("/api/letters/" + box, HttpMethod.GET, new HttpEntity<>(as.headers()), List.class);
    }

    @Test
    void aHandSignedLetterIsAppendedToTheLedgerAndDeliveredAsCiphertext() throws Exception {
        LetterEnvelope envelope = envelope();
        String sentAt = now();
        String handwriting = aliceSignsNow();
        ResponseEntity<Map> sent = send(alice, signedLetter(alice, bob, sentAt, envelope, handwriting));

        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> letter = sent.getBody();
        byte[] handwritingHash = LetterHashing.handwritingHash(handwriting);
        byte[] expectedHash = LetterHashing.letterHash(
                LetterHashing.headerV2(alice.publicKey, bob.publicKey, sentAt, handwritingHash), envelope);
        assertThat(letter.get("letterHash")).isEqualTo(Base64Url.encode(expectedHash));
        assertThat(letter.get("handwritingHash")).isEqualTo(Base64Url.encode(handwritingHash));
        assertThat((Double) letter.get("handwritingScore")).isGreaterThanOrEqualTo(0.5);
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

        // The letter row holds ciphertext and the handwriting hash, never the strokes.
        String row = jdbc.sql("SELECT row_to_json(l)::text FROM letters l WHERE id = CAST(:id AS uuid)")
                .param("id", letter.get("letterId")).query(String.class).single();
        assertThat(row).contains(envelope.ciphertext()).doesNotContain("strokes");
    }

    @Test
    void sendingRequiresEnrolledHandwriting() throws Exception {
        Map<String, Object> letter = signedLetter(bob, alice, now(), envelope(), aliceSignsNow());

        ResponseEntity<Map> response = send(bob, letter);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) response.getBody().get("detail")).contains("Enrol your handwriting");
    }

    @Test
    void aSignatureInSomeoneElsesHandIsRejectedWithItsScore() throws Exception {
        String forged = toJson(capturedAt(
                SyntheticSignatures.skilledForgery(aliceHand, seeds.incrementAndGet(), "pen"), Instant.now()));

        ResponseEntity<Map> response = send(alice, signedLetter(alice, bob, now(), envelope(), forged));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("match", false);
        assertThat((Double) response.getBody().get("score")).isLessThan(0.5);
        assertThat(list(bob, "inbox").getBody()).isEmpty();
    }

    @Test
    void oneSignatureCannotSealTwoLetters() throws Exception {
        String handwriting = aliceSignsNow();

        assertThat(send(alice, signedLetter(alice, bob, now(), envelope(), handwriting)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(send(alice, signedLetter(alice, bob, now(), envelope(), handwriting)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aSignatureLiftedFromAnEarlierLetterIsAReplay() throws Exception {
        HandwritingSample original = capturedAt(aliceHand.genuine(seeds.incrementAndGet(), "pen"), Instant.now());
        assertThat(send(alice, signedLetter(alice, bob, now(), envelope(), toJson(original))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // Whoever holds the device can decrypt sent letters; re-submitting those strokes, slightly
        // moved and scaled so the hash differs, must still fail.
        HandwritingSample lifted = new HandwritingSample(original.format(), 1, Instant.now().toString(),
                original.device(), original.width(), original.height(), original.strokes().stream()
                        .map(stroke -> stroke.stream().map(p -> new HandwritingSample.StrokePoint(
                                p.x() * 1.05 + 3, p.y() * 1.05 + 2, p.t(), p.pressure(), p.penDown())).toList())
                        .toList());
        ResponseEntity<Map> response = send(alice, signedLetter(alice, bob, now(), envelope(), toJson(lifted)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((List<Object>) response.getBody().get("livenessFlags")).contains("REPLAY");
    }

    @Test
    void aSignatureWrittenLongAgoIsStale() throws Exception {
        String old = toJson(capturedAt(aliceHand.genuine(seeds.incrementAndGet(), "pen"),
                Instant.now().minus(1, ChronoUnit.HOURS)));

        ResponseEntity<Map> response = send(alice, signedLetter(alice, bob, now(), envelope(), old));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((List<Object>) response.getBody().get("livenessFlags")).contains("STALE");
    }

    @Test
    void theWalletSignatureCommitsToTheExactHandwriting() throws Exception {
        Map<String, Object> letter = signedLetter(alice, bob, now(), envelope(), aliceSignsNow());
        letter.put("handwriting", aliceSignsNow()); // a different (genuine) signature than the one signed over

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void handwritingIsRequiredAndMustBeASample() throws Exception {
        Map<String, Object> missing = aliceToBob();
        missing.remove("handwriting");
        assertThat(send(alice, missing).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(send(alice, signedLetter(alice, bob, now(), envelope(), "{\"not\":\"a sample\"}")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheSenderAndRecipientCanReadALetter() throws Exception {
        String id = (String) send(alice, aliceToBob()).getBody().get("letterId");
        TestWallet eve = TestWallet.create(rest);

        assertThat(get(alice, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(bob, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(eve, id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void lettersCannotBeEditedOrUnsent() throws Exception {
        String id = (String) send(alice, aliceToBob()).getBody().get("letterId");

        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            ResponseEntity<Map> response = rest.exchange("/api/letters/" + id, method,
                    new HttpEntity<>(Map.of(), alice.headers()), Map.class);
            assertThat(response.getStatusCode()).as(method.name()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }
        // Not even with direct database access.
        assertThatThrownBy(() ->
                jdbc.sql("DELETE FROM letters WHERE id = CAST(:id AS uuid)").param("id", id).update())
                .hasMessageContaining("append-only");
    }

    @Test
    void aLetterSignedByAnotherWalletIsRejected() throws Exception {
        String sentAt = now();
        LetterEnvelope envelope = envelope();
        String handwriting = aliceSignsNow();
        Map<String, Object> letter = signedLetter(alice, bob, sentAt, envelope, handwriting);
        letter.put("signature", signedLetter(bob, bob, sentAt, envelope, handwriting).get("signature"));

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void changingTheCiphertextAfterSigningBreaksTheSignature() throws Exception {
        Map<String, Object> letter = aliceToBob();
        LetterEnvelope e = (LetterEnvelope) letter.get("envelope");
        letter.put("envelope", new LetterEnvelope(1, e.iv(), b64(200), e.recipientKey(), e.senderKey()));

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aStaleOrMalformedTimestampIsRejected() throws Exception {
        String old = iso(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(send(alice, signedLetter(alice, bob, old, envelope(), aliceSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(send(alice, signedLetter(alice, bob, "2026-10-02 12:00", envelope(), aliceSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aTimestampOnAWholeSecondIsAccepted() throws Exception {
        // Regression: "…:14.000Z" must be accepted (and written with its fraction by clients).
        String wholeSecond = iso(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        assertThat(wholeSecond).endsWith(".000Z");

        assertThat(send(alice, signedLetter(alice, bob, wholeSecond, envelope(), aliceSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theSameLetterCannotBeSentTwice() throws Exception {
        Map<String, Object> letter = aliceToBob();

        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(alice, letter).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void malformedEnvelopesAndUnknownRecipientsAreRejected() throws Exception {
        Map<String, Object> badIv = signedLetter(alice, bob, now(),
                new LetterEnvelope(1, b64(8), b64(200), envelope().recipientKey(), envelope().senderKey()),
                aliceSignsNow());
        assertThat(send(alice, badIv).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        TestWallet stranger = TestWallet.create(rest); // registered but never set an encryption key
        assertThat(send(alice, signedLetter(alice, stranger, now(), envelope(), aliceSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        Map<String, Object> nobody = aliceToBob();
        nobody.put("recipientPublicKey", b64(32));
        assertThat(send(alice, nobody).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theEncryptionKeyMustBeSignedByTheIdentityKeyAndIsSetOnce() throws Exception {
        TestWallet carol = TestWallet.create(rest);
        byte[] key = new byte[32];
        random.nextBytes(key);
        byte[] wrongSigner = TestWallet.sign(alice.identity.getPrivate(), EncryptionKeyBinding.of(carol.publicKey, key));

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
        byte[] signed = TestWallet.sign(carol.identity.getPrivate(), EncryptionKeyBinding.of(carol.publicKey, replacement));
        ResponseEntity<Map> conflict = rest.exchange("/api/me/encryption-key", HttpMethod.PUT, new HttpEntity<>(
                Map.of("encryptionKey", Base64Url.encode(replacement), "signature", Base64Url.encode(signed)),
                carol.headers()), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theLedgerCanBeReadAndVerified() throws Exception {
        send(alice, aliceToBob());

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

    private final SyntheticSignatures.Writer bobHand = new SyntheticSignatures.Writer(2002);

    private String bobSignsNow() throws Exception {
        return toJson(capturedAt(bobHand.genuine(seeds.incrementAndGet(), "pen"), Instant.now()));
    }

    private List<Map<String, Object>> threads(TestWallet as) {
        return rest.exchange("/api/letters/threads", HttpMethod.GET, new HttpEntity<>(as.headers()), List.class)
                .getBody();
    }

    /** Object, not List: errors come back as a problem-detail map. */
    private ResponseEntity<Object> thread(TestWallet as, String threadId) {
        return rest.exchange("/api/letters/threads/" + threadId, HttpMethod.GET, new HttpEntity<>(as.headers()),
                Object.class);
    }

    @Test
    void aNewLetterStartsItsOwnThread() throws Exception {
        Map<String, Object> letter = send(alice, aliceToBob()).getBody();

        assertThat(letter.get("inReplyTo")).isNull();
        assertThat(letter.get("threadId")).isEqualTo(letter.get("letterHash"));
    }

    @Test
    void repliesFormAThreadInWhichEachLetterCommitsToTheOneItAnswers() throws Exception {
        enrol(bob, bobHand);
        Map<String, Object> first = send(alice, aliceToBob()).getBody();
        String firstHash = (String) first.get("letterHash");
        ResponseEntity<Map> reply = send(bob, signedReply(bob, alice, firstHash, bobSignsNow()));
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String replyHash = (String) reply.getBody().get("letterHash");
        Map<String, Object> second = send(alice, signedReply(alice, bob, replyHash, aliceSignsNow())).getBody();
        send(alice, aliceToBob()); // a separate conversation

        assertThat(reply.getBody()).containsEntry("inReplyTo", firstHash).containsEntry("threadId", firstHash);
        assertThat(second).containsEntry("inReplyTo", replyHash).containsEntry("threadId", firstHash);

        List<Map<String, Object>> aliceThreads = threads(alice);
        assertThat(aliceThreads).hasSize(2);
        Map<String, Object> conversation = aliceThreads.stream()
                .filter(t -> firstHash.equals(t.get("threadId"))).findFirst().orElseThrow();
        assertThat(conversation).containsEntry("letters", 3).containsEntry("latestSentAt", second.get("sentAt"));
        assertThat((Map<String, Object>) conversation.get("counterpart"))
                .containsEntry("publicKey", Base64Url.encode(bob.publicKey));
        assertThat(threads(bob)).extracting(t -> t.get("threadId")).contains(firstHash);

        List<Map<String, Object>> letters = (List<Map<String, Object>>) thread(bob, firstHash).getBody();
        assertThat(letters).extracting(l -> l.get("letterHash"))
                .containsExactly(firstHash, replyHash, second.get("letterHash"));
        assertThat(letters).extracting(l -> l.get("inReplyTo")).containsExactly(null, firstHash, replyHash);
    }

    @Test
    void aReplyMustAnswerALetterBetweenTheSameTwoPeopleAndSignTheLink() throws Exception {
        enrol(bob, bobHand);
        TestWallet carol = TestWallet.create(rest);
        carol.registerEncryptionKey(rest);
        Map<String, Object> first = send(alice, aliceToBob()).getBody();
        String firstHash = (String) first.get("letterHash");

        // Alice can't attach Bob's letter to a conversation with Carol.
        assertThat(send(alice, signedReply(alice, carol, firstHash, aliceSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // An unknown letter looks the same as someone else's.
        assertThat(send(bob, signedReply(bob, alice, b64(32), bobSignsNow())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // The link is signed: claiming a reply on a letter signed as a fresh (v2) one fails.
        Map<String, Object> unsigned = signedLetter(bob, alice, now(), envelope(), bobSignsNow());
        unsigned.put("inReplyTo", firstHash);
        assertThat(send(bob, unsigned).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        Map<String, Object> malformed = signedReply(bob, alice, firstHash, bobSignsNow());
        malformed.put("inReplyTo", b64(16));
        assertThat(send(bob, malformed).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheTwoPartiesCanReadAThread() throws Exception {
        TestWallet carol = TestWallet.create(rest);
        String threadId = (String) send(alice, aliceToBob()).getBody().get("threadId");

        assertThat(thread(bob, threadId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(thread(carol, threadId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thread(carol, "not-a-hash").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(threads(carol)).isEmpty();
        assertThat(rest.getForEntity("/api/letters/threads", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> get(TestWallet as, String id) {
        return rest.exchange("/api/letters/" + id, HttpMethod.GET, new HttpEntity<>(as.headers()), Map.class);
    }
}
