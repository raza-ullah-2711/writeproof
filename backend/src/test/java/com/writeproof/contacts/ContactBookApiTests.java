package com.writeproof.contacts;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class ContactBookApiTests {

    @Autowired
    private TestRestTemplate rest;

    private final SecureRandom random = new SecureRandom();
    private TestWallet alice;

    @BeforeEach
    void wallet() throws Exception {
        alice = TestWallet.create(rest);
    }

    private String ciphertext(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return Base64Url.encode(b);
    }

    private ResponseEntity<Map> put(TestWallet as, long baseVersion, String ciphertext) {
        return rest.exchange("/api/me/contacts", HttpMethod.PUT,
                new HttpEntity<>(Map.of("baseVersion", baseVersion, "ciphertext", ciphertext), as.headers()), Map.class);
    }

    private ResponseEntity<Map> get(TestWallet as) {
        return rest.exchange("/api/me/contacts", HttpMethod.GET, new HttpEntity<>(as.headers()), Map.class);
    }

    @Test
    void storesAndReturnsTheCiphertextWithAVersion() {
        assertThat(get(alice).getBody()).containsEntry("version", 0).containsEntry("ciphertext", null);
        String first = ciphertext(100);
        String second = ciphertext(140);

        assertThat(put(alice, 0, first).getBody()).containsEntry("version", 1);
        assertThat(put(alice, 1, second).getBody()).containsEntry("version", 2);

        Map<String, Object> book = get(alice).getBody();
        assertThat(book).containsEntry("version", 2).containsEntry("ciphertext", second).containsKey("updatedAt");
    }

    @Test
    void aWriteBasedOnAnOldVersionIsRejected() {
        put(alice, 0, ciphertext(100));
        put(alice, 1, ciphertext(100));

        assertThat(put(alice, 1, ciphertext(100)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(alice, 0, ciphertext(100)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(put(alice, 7, ciphertext(100)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(get(alice).getBody()).containsEntry("version", 2);
    }

    @Test
    void concurrentWritesFromTheSameVersionLetExactlyOneWin() throws Exception {
        put(alice, 0, ciphertext(100));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<HttpStatus>> writes = java.util.stream.IntStream.range(0, 6)
                    .<Callable<HttpStatus>>mapToObj(i -> () -> (HttpStatus) put(alice, 1, ciphertext(100)).getStatusCode())
                    .toList();
            List<HttpStatus> results = new java.util.ArrayList<>();
            for (Future<HttpStatus> f : pool.invokeAll(writes)) {
                results.add(f.get());
            }
            assertThat(results).containsOnlyOnce(HttpStatus.OK).filteredOn(s -> s != HttpStatus.OK)
                    .containsOnly(HttpStatus.CONFLICT);
        } finally {
            pool.shutdown();
        }
        assertThat(get(alice).getBody()).containsEntry("version", 2);
    }

    @Test
    void eachAccountSeesOnlyItsOwnBook() throws Exception {
        TestWallet bob = TestWallet.create(rest);
        put(alice, 0, ciphertext(100));

        assertThat(get(bob).getBody()).containsEntry("version", 0);
        assertThat(put(bob, 0, ciphertext(100)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(alice).getBody()).containsEntry("version", 1);
    }

    @Test
    void rejectsMalformedOrOversizedBooks() {
        assertThat(put(alice, 0, ciphertext(27)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(alice, 0, ciphertext(ContactBookController.MAX_BYTES + 1)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(alice, 0, "not base64!").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(alice, -1, ciphertext(100)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(alice, 0, ciphertext(ContactBookController.MAX_BYTES))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void requiresAuthentication() {
        assertThat(rest.getForEntity("/api/me/contacts", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
