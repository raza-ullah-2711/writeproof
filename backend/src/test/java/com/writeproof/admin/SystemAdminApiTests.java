package com.writeproof.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.ledger.LedgerService;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class SystemAdminApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private SystemAdminService system;

    private TestWallet admin;

    @BeforeEach
    void admin() throws Exception {
        admin = TestWallet.bootstrapAdmin(rest);
    }

    /** Other test classes share this database: always leave every switch on. */
    @AfterEach
    void restore() {
        patch(Map.of("registrationOpen", true, "sendingEnabled", true, "openLettersEnabled", true,
                "announcement", ""));
    }

    private ResponseEntity<Map> patch(Map<String, Object> changes) {
        return rest.exchange("/api/admin/system/settings", HttpMethod.PATCH,
                new HttpEntity<>(changes, admin.headers()), Map.class);
    }

    private ResponseEntity<Map> post(TestWallet as, String path) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(null, as.headers()), Map.class);
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return Base64Url.encode(b);
    }

    private ResponseEntity<Map> register() throws Exception {
        byte[] raw = TestWallet.raw(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        return rest.postForEntity("/api/accounts", Map.of("publicKey", Base64Url.encode(raw)), Map.class);
    }

    private List<Map<String, Object>> auditFor(String target) {
        return jdbc.sql("SELECT action, target, detail::text AS d FROM admin_audit_log WHERE target = :t "
                        + "ORDER BY id DESC").param("t", target)
                .query((rs, row) -> Map.<String, Object>of("action", rs.getString(1), "detail", rs.getString(3)))
                .list();
    }

    @Test
    void anyoneCanReadTheStatusAndTheOverviewIsForAdmins() throws Exception {
        Map<String, Object> status = rest.getForEntity("/api/system/status", Map.class).getBody();
        assertThat(status).containsEntry("registrationOpen", true).containsEntry("sendingEnabled", true)
                .containsEntry("openLettersEnabled", true).containsEntry("announcement", "");

        Map<String, Object> overview = rest.exchange("/api/admin/system", HttpMethod.GET,
                new HttpEntity<>(admin.headers()), Map.class).getBody();
        assertThat(overview).containsKeys("settings", "changes", "rateLimits", "handwritingThreshold",
                "calibrationContributors", "ledgerSize");
        assertThat((List<Map<String, Object>>) overview.get("rateLimits")).extracting(r -> r.get("name"))
                .contains("send-letter", "report-open-letter");
        TestWallet user = TestWallet.create(rest);
        assertThat(rest.exchange("/api/admin/system", HttpMethod.GET, new HttpEntity<>(user.headers()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/admin/system/settings", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("registrationOpen", false), user.headers()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void closingRegistrationStopsNewAccountsButNotSignIns() throws Exception {
        TestWallet existing = TestWallet.create(rest);
        assertThat(patch(Map.of("registrationOpen", false)).getBody()).containsEntry("registrationOpen", false);

        ResponseEntity<Map> refused = register();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat((String) refused.getBody().get("detail")).contains("isn't accepting new accounts");
        assertThat(TestWallet.create(rest, existing.identity).token).isNotNull();

        patch(Map.of("registrationOpen", true));
        assertThat(register().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(auditFor("registration_open")).extracting(e -> e.get("action"))
                .containsOnly("system.setting-changed");
        assertThat((String) auditFor("registration_open").get(1).get("detail")).contains("\"to\": false");
    }

    @Test
    void pausesStopSendingAndPublishingWithA503() throws Exception {
        TestWallet user = TestWallet.create(rest);
        patch(Map.of("sendingEnabled", false, "openLettersEnabled", false));

        Map<String, Object> wrapped = Map.of("ephemeralPublicKey", b64(32), "iv", b64(12), "wrappedKey", b64(48));
        ResponseEntity<Map> letter = rest.exchange("/api/letters", HttpMethod.POST, new HttpEntity<>(Map.of(
                "recipientPublicKey", Base64Url.encode(user.publicKey), "sentAt", "2026-10-03T00:00:00.000Z",
                "envelope", Map.of("version", 1, "iv", b64(12), "ciphertext", b64(64), "recipientKey", wrapped,
                        "senderKey", wrapped),
                "signature", b64(64), "handwriting", "{}"), user.headers()), Map.class);
        ResponseEntity<Map> open = rest.exchange("/api/me/open-letters", HttpMethod.POST,
                new HttpEntity<>(Map.of("sentAt", "x", "body", "x", "signature", b64(64), "handwriting", "{}"),
                        user.headers()), Map.class);

        assertThat(letter.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) letter.getBody().get("detail")).contains("Sending letters is paused");
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rest.exchange("/api/letters/inbox", HttpMethod.GET, new HttpEntity<>(user.headers()), List.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theAnnouncementIsPublicAndValidated() {
        assertThat(patch(Map.of("announcement", "  Maintenance at 22:00 UTC \"tonight\"  ")).getBody())
                .containsEntry("announcement", "Maintenance at 22:00 UTC \"tonight\"");
        assertThat(rest.getForEntity("/api/system/status", Map.class).getBody())
                .containsEntry("announcement", "Maintenance at 22:00 UTC \"tonight\"");
        assertThat(patch(Map.of("announcement", "x".repeat(281))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(Map.of("announcement", "two\nlines")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> unchanged = new HashMap<>();
        unchanged.put("registrationOpen", null);
        assertThat(patch(unchanged).getBody()).containsEntry("registrationOpen", true);
    }

    @Test
    void publishesACheckpointOnDemandAndAuditsTheLedger() {
        byte[] payload = new byte[32];
        new SecureRandom().nextBytes(payload);
        tx.execute(status -> ledger.append(payload));

        Map<String, Object> first = post(admin, "/api/admin/system/ledger/checkpoint").getBody();
        Map<String, Object> second = post(admin, "/api/admin/system/ledger/checkpoint").getBody();

        assertThat(first).containsEntry("published", true);
        assertThat(((Number) first.get("size")).longValue()).isEqualTo(ledger.size());
        assertThat(second).containsEntry("published", false);
        Map<String, Object> audit = post(admin, "/api/admin/system/ledger/audit").getBody();
        assertThat(audit).containsEntry("ok", true).containsEntry("chainIntact", true)
                .containsEntry("rootMismatches", List.of())
                .containsEntry("badSignatures", List.of());
        assertThat(((Number) audit.get("checkpointsChecked")).intValue()).isPositive();
    }

    @Test
    void theAuditCatchesAnEntryRewrittenAfterACheckpoint() {
        byte[] payload = new byte[32];
        new SecureRandom().nextBytes(payload);
        var entry = tx.execute(status -> ledger.append(payload));
        post(admin, "/api/admin/system/ledger/checkpoint");
        byte[] original = entry.entryHash();
        byte[] forged = new byte[32];
        new SecureRandom().nextBytes(forged);

        jdbc.sql("ALTER TABLE ledger_entries DISABLE TRIGGER ledger_entries_append_only").update();
        try {
            jdbc.sql("UPDATE ledger_entries SET entry_hash = :h WHERE seq = :s").param("h", forged)
                    .param("s", entry.seq()).update();
            Map<String, Object> audit = post(admin, "/api/admin/system/ledger/audit").getBody();
            assertThat(audit).containsEntry("ok", false).containsEntry("chainIntact", false);
            assertThat((List<Integer>) audit.get("rootMismatches")).isNotEmpty();
        } finally {
            jdbc.sql("UPDATE ledger_entries SET entry_hash = :h WHERE seq = :s").param("h", original)
                    .param("s", entry.seq()).update();
            jdbc.sql("ALTER TABLE ledger_entries ENABLE TRIGGER ledger_entries_append_only").update();
        }
        assertThat(post(admin, "/api/admin/system/ledger/audit").getBody()).containsEntry("chainIntact", true);
    }

    /** A fresh server has no published checkpoint yet; the overview must still load. */
    @Test
    void theOverviewLoadsBeforeAnyCheckpointIsPublished() {
        long before = jdbc.sql("SELECT count(*) FROM ledger_checkpoints").query(Long.class).single();
        tx.executeWithoutResult(status -> {
            jdbc.sql("ALTER TABLE ledger_checkpoints DISABLE TRIGGER ledger_checkpoints_append_only").update();
            jdbc.sql("DELETE FROM ledger_checkpoints").update();

            SystemAdminService.Overview overview = system.overview();

            assertThat(overview.lastCheckpointSize()).isNull();
            assertThat(overview.lastCheckpointAt()).isNull();
            status.setRollbackOnly(); // the deletion and the trigger change are undone
        });
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_checkpoints").query(Long.class).single()).isEqualTo(before);
    }
}
