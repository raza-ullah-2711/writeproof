package com.writeproof.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerHashing;
import com.writeproof.letters.LetterHashing;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** The private family archive (Task 14c-1, docs/family-archive.md). */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class ArchiveApiTests {

    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ArchiveService archive;

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

    private byte[] random(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    /** The server can't decrypt, so random bytes of the right sizes stand in for real ciphertext. */
    private ResponseEntity<Map> upload(TestWallet as, byte[] blob) {
        HttpHeaders headers = as.headers();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange("/api/me/archive/photos", HttpMethod.POST, new HttpEntity<>(blob, headers), Map.class);
    }

    private String uploaded(TestWallet as) {
        ResponseEntity<Map> r = upload(as, random(5000));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("hash");
    }

    private Map<String, String> wrapFor(TestWallet reader) {
        return Map.of("publicKey", Base64Url.encode(reader.publicKey),
                "ephemeralPublicKey", Base64Url.encode(random(32)),
                "iv", Base64Url.encode(random(12)), "wrappedKey", Base64Url.encode(random(48)));
    }

    /** An entry request signed by {@code as} over exactly these photos and readers. */
    private Map<String, Object> signed(TestWallet as, List<String> photos, List<Map<String, String>> readers)
            throws Exception {
        String addedAt = AT.format(Instant.now());
        byte[] iv = random(12);
        byte[] ciphertext = random(300);
        List<ArchiveHashing.Wrap> wraps = new ArrayList<>();
        for (Map<String, String> r : readers) {
            wraps.add(new ArchiveHashing.Wrap(Base64Url.decode(r.get("publicKey")),
                    Base64Url.decode(r.get("ephemeralPublicKey")), Base64Url.decode(r.get("iv")),
                    Base64Url.decode(r.get("wrappedKey"))));
        }
        String header = ArchiveHashing.header(as.publicKey, addedAt, photos.stream().map(Base64Url::decode).toList());
        byte[] hash = ArchiveHashing.entryHash(header, iv, ciphertext, wraps);
        Map<String, Object> body = new HashMap<>();
        body.put("addedAt", addedAt);
        body.put("iv", Base64Url.encode(iv));
        body.put("ciphertext", Base64Url.encode(ciphertext));
        body.put("photos", photos);
        body.put("readers", readers);
        body.put("signature", Base64Url.encode(TestWallet.sign(as.identity.getPrivate(),
                LetterHashing.signedMessage(hash))));
        return body;
    }

    private ResponseEntity<Map> add(TestWallet as, Map<String, Object> request) {
        return rest.exchange("/api/me/archive", HttpMethod.POST, new HttpEntity<>(request, as.headers()), Map.class);
    }

    private ResponseEntity<List> list(TestWallet as) {
        return rest.exchange("/api/me/archive", HttpMethod.GET, new HttpEntity<>(as.headers()), List.class);
    }

    private ResponseEntity<byte[]> photo(TestWallet as, String hash) {
        return rest.exchange("/api/me/archive/photos/" + hash, HttpMethod.GET, new HttpEntity<>(as.headers()),
                byte[].class);
    }

    @Test
    void anEntryIsSignedRecordedAndReadableOnlyByItsReaders() throws Exception {
        byte[] scan = random(4000);
        String first = (String) upload(alice, scan).getBody().get("hash");
        assertThat(first).isEqualTo(Base64Url.encode(LedgerHashing.sha256(scan)));
        String second = uploaded(alice);

        ResponseEntity<Map> added = add(alice, signed(alice, List.of(first, second),
                List.of(wrapFor(alice), wrapFor(bob))));
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String hash = (String) added.getBody().get("entryHash");
        assertThat(((Map<String, Object>) added.getBody().get("ledger")).get("payloadHash")).isEqualTo(hash);
        assertThat((List<String>) added.getBody().get("photos")).containsExactly(first, second);

        assertThat(list(alice).getBody()).extracting(e -> ((Map) e).get("entryHash")).containsExactly(hash);
        assertThat(list(bob).getBody()).extracting(e -> ((Map) e).get("entryHash")).containsExactly(hash);
        assertThat(photo(bob, first).getBody()).isEqualTo(scan);
        assertThat(photo(bob, first).getHeaders().getCacheControl()).contains("private").contains("immutable");

        TestWallet stranger = TestWallet.create(rest);
        assertThat(list(stranger).getBody()).isEmpty();
        assertThat(photo(stranger, first).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/me/archive/" + hash, HttpMethod.GET, new HttpEntity<>(stranger.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/me/archive/" + hash, HttpMethod.GET, new HttpEntity<>(bob.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsForgedEntriesStrangePhotosAndReadersWhoCantOpenIt() throws Exception {
        String photo = uploaded(alice);
        Map<String, Object> forged = signed(alice, List.of(photo), List.of(wrapFor(alice)));
        forged.put("readers", List.of(wrapFor(alice))); // a different wrap than the one signed
        assertThat(add(alice, forged).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(add(alice, signed(alice, List.of(photo), List.of(wrapFor(bob), wrapFor(alice))))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // the owner comes first
        TestWallet noKey = TestWallet.create(rest);
        assertThat(add(alice, signed(alice, List.of(photo), List.of(wrapFor(alice), wrapFor(noKey))))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        String bobsPhoto = uploaded(bob);
        assertThat(add(alice, signed(alice, List.of(bobsPhoto), List.of(wrapFor(alice)))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(add(alice, signed(alice, List.of(photo), List.of(wrapFor(alice)))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // A photo belongs to one entry only.
        assertThat(add(alice, signed(alice, List.of(photo), List.of(wrapFor(alice)))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(Base64Url.encode(random(32)));
        }
        assertThat(add(alice, signed(alice, eleven, List.of(wrapFor(alice)))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(alice, random(ArchiveService.MAX_BLOB + 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        byte[] same = random(1000);
        assertThat(upload(alice, same).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upload(alice, same).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void entriesCantChangeAndOnlyAbandonedUploadsAreCleanedUp() throws Exception {
        String attached = uploaded(alice);
        String hash = (String) add(alice, signed(alice, List.of(attached), List.of(wrapFor(alice)))).getBody()
                .get("entryHash");
        String abandoned = uploaded(alice);
        jdbc.sql("ALTER TABLE archive_blobs DISABLE TRIGGER archive_blobs_rules").update();
        try {
            jdbc.sql("UPDATE archive_blobs SET created_at = now() - interval '2 days' WHERE blob_hash IN (:a, :b)")
                    .param("a", Base64Url.decode(attached)).param("b", Base64Url.decode(abandoned)).update();
        } finally {
            jdbc.sql("ALTER TABLE archive_blobs ENABLE TRIGGER archive_blobs_rules").update();
        }

        archive.deleteAbandonedUploads();

        assertThat(photo(alice, attached).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(photo(alice, abandoned).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        byte[] h = Base64Url.decode(hash);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM archive_entries WHERE entry_hash = :h").param("h", h)
                .update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE archive_readers SET iv = iv WHERE entry_hash = :h").param("h", h)
                .update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM archive_blobs WHERE blob_hash = :b")
                .param("b", Base64Url.decode(attached)).update()).hasMessageContaining("never be deleted");
    }

    /** Shared with frontend archive-format.spec.ts. */
    @Test
    void formatVector() {
        String header = ArchiveHashing.header(bytes(1, 32), "2026-10-14T10:00:00.000Z",
                List.of(bytes(7, 32), bytes(40, 32)));
        assertThat(header).isEqualTo("writeproof/archive/v1\n"
                + "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA\n"
                + "2026-10-14T10:00:00.000Z\n"
                + "BwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSY,KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_QEFCQ0RFRkc");
        byte[] hash = ArchiveHashing.entryHash(header, bytes(100, 12), bytes(120, 40), List.of(
                new ArchiveHashing.Wrap(bytes(1, 32), bytes(150, 32), bytes(200, 12), bytes(210, 48)),
                new ArchiveHashing.Wrap(bytes(101, 32), bytes(60, 32), bytes(90, 12), bytes(10, 48))));
        assertThat(Base64Url.encode(hash)).isEqualTo("mTtvY-Sz7oFlLICBO-fgNXULY7hjudpBbZzU-F2-Kso");
    }

    private static byte[] bytes(int from, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (from + i);
        }
        return b;
    }
}
