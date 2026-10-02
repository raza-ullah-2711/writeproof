package com.writeproof.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import java.security.SecureRandom;
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
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class WalletBackupApiTests {

    @Autowired
    private TestRestTemplate rest;

    private final SecureRandom random = new SecureRandom();
    private TestWallet alice;

    @BeforeEach
    void wallet() throws Exception {
        alice = TestWallet.create(rest);
    }

    private String b64(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    private WalletBackupBlob blobFor(TestWallet wallet) {
        return new WalletBackupBlob(WalletBackupBlob.FORMAT, 1, Base64Url.encode(wallet.publicKey), b64(32), b64(12),
                b64(600), "2026-10-02T12:00:00.000Z");
    }

    private ResponseEntity<Map> upload(TestWallet as, String lookupId, WalletBackupBlob blob) {
        return rest.exchange("/api/me/backup", HttpMethod.PUT,
                new HttpEntity<>(Map.of("lookupId", lookupId, "blob", blob), as.headers()), Map.class);
    }

    private ResponseEntity<Map> fetch(String lookupId) {
        return rest.getForEntity("/api/backups/" + lookupId, Map.class);
    }

    private Map<String, Object> status(TestWallet as) {
        return rest.exchange("/api/me/backup", HttpMethod.GET, new HttpEntity<>(as.headers()), Map.class).getBody();
    }

    @Test
    void anUploadedBackupCanBeFetchedWithoutLoggingIn() {
        assertThat(status(alice)).containsEntry("backedUp", false);
        String lookupId = b64(32);
        WalletBackupBlob blob = blobFor(alice);

        assertThat(upload(alice, lookupId, blob).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(alice)).containsEntry("backedUp", true).containsKey("backedUpAt");

        ResponseEntity<Map> fetched = fetch(lookupId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody())
                .containsEntry("ciphertext", blob.ciphertext())
                .containsEntry("publicKey", blob.publicKey())
                .containsEntry("salt", blob.salt());
    }

    @Test
    void aNewBackupReplacesTheOldOneAndItsCode() {
        String oldLookup = b64(32);
        String newLookup = b64(32);
        upload(alice, oldLookup, blobFor(alice));

        assertThat(upload(alice, newLookup, blobFor(alice)).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(fetch(oldLookup).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetch(newLookup).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownCodeFindsNothing() {
        assertThat(fetch(b64(32)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fetch(b64(8)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBackupMustBeOfTheUploadersOwnWallet() throws Exception {
        TestWallet bob = TestWallet.create(rest);

        assertThat(upload(alice, b64(32), blobFor(bob)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aLookupIdCannotBeTakenFromAnotherAccount() throws Exception {
        TestWallet bob = TestWallet.create(rest);
        String lookupId = b64(32);
        upload(alice, lookupId, blobFor(alice));

        assertThat(upload(bob, lookupId, blobFor(bob)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fetch(lookupId).getBody()).containsEntry("publicKey", Base64Url.encode(alice.publicKey));
    }

    @Test
    void malformedBackupsAreRejected() {
        WalletBackupBlob good = blobFor(alice);
        WalletBackupBlob wrongFormat = new WalletBackupBlob("zip", 1, good.publicKey(), good.salt(), good.iv(),
                good.ciphertext(), good.createdAt());
        WalletBackupBlob shortIv = new WalletBackupBlob(good.format(), 1, good.publicKey(), good.salt(), b64(8),
                good.ciphertext(), good.createdAt());
        WalletBackupBlob huge = new WalletBackupBlob(good.format(), 1, good.publicKey(), good.salt(), good.iv(),
                b64(5000), good.createdAt());

        assertThat(upload(alice, b64(32), wrongFormat).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(alice, b64(32), shortIv).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(alice, b64(32), huge).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(alice, b64(16), good).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadingAndStatusRequireALogin() {
        ResponseEntity<Map> anonymous = rest.exchange("/api/me/backup", HttpMethod.PUT,
                new HttpEntity<>(Map.of("lookupId", b64(32), "blob", blobFor(alice))), Map.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/me/backup", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
