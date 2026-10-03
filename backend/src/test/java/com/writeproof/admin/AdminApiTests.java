package com.writeproof.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"rawtypes", "unchecked"})
class AdminApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private AuditLog audit;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private ResponseEntity<Map> get(TestWallet as, String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(as.headers()), Map.class);
    }

    private void grant(TestWallet wallet, AdminRole role) {
        jdbc.sql("INSERT INTO admin_roles (public_key, role, granted_at) VALUES (:k, :r, :at)")
                .param("k", wallet.publicKey).param("r", role.name())
                .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    @Test
    void ordinaryAccountsAreNotAdmins() throws Exception {
        TestWallet user = TestWallet.create(rest);

        // They can't sign in to the admin app, and their public-app token opens nothing there.
        assertThat(user.adminSession(rest).token).isNull();
        assertThat(get(user, "/api/admin/me").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(user, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(user, "/api/admin/audit").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.getForEntity("/api/admin/me", Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/admin/dashboard", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theBootstrapAdminFromConfigurationIsAnAdmin() throws Exception {
        TestWallet admin = TestWallet.bootstrapAdmin(rest);

        assertThat(Base64Url.encode(admin.publicKey)).isEqualTo("IVL40Zt5HSRFMkLhXy6rbLfP-ntqXtMAl5YOBpiB2xI");
        assertThat(get(admin, "/api/admin/me").getBody()).containsEntry("role", "ADMIN");
        assertThat(get(admin, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rolesComeFromTheTableAndRevokingOneTakesEffectWithoutSigningInAgain() throws Exception {
        TestWallet moderatorUser = TestWallet.create(rest);
        TestWallet adminUser = TestWallet.create(rest);
        grant(moderatorUser, AdminRole.MODERATOR);
        grant(adminUser, AdminRole.ADMIN);
        TestWallet moderator = moderatorUser.adminSession(rest);
        TestWallet admin = adminUser.adminSession(rest);

        assertThat(get(moderator, "/api/admin/me").getBody()).containsEntry("role", "MODERATOR");
        assertThat(get(moderator, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(admin, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.OK);
        // Their public-app sessions stay ordinary, roles or not.
        assertThat(get(adminUser, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.sql("DELETE FROM admin_roles WHERE public_key = :k").param("k", admin.publicKey).update();
        assertThat(get(admin, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminAppTokenWorksOnlyInTheAdminApp() throws Exception {
        TestWallet admin = TestWallet.bootstrapAdmin(rest);
        TestWallet asUser = TestWallet.create(rest, admin.identity);

        assertThat(get(admin, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(admin, "/api/me").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(asUser, "/api/me").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(asUser, "/api/admin/me").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(asUser, "/api/admin/dashboard").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theDashboardCountsWhatTheServerCanSee() throws Exception {
        TestWallet admin = TestWallet.bootstrapAdmin(rest);
        TestWallet.create(rest);

        Map<String, Object> d = get(admin, "/api/admin/dashboard").getBody();

        Map<String, Object> accounts = (Map<String, Object>) d.get("accounts");
        assertThat(((Number) accounts.get("total")).longValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) accounts.get("new7d")).longValue()).isGreaterThanOrEqualTo(2);
        Map<String, Object> letters = (Map<String, Object>) d.get("letters");
        List<Map<String, Object>> days = (List<Map<String, Object>>) letters.get("last30Days");
        assertThat(days).hasSize(30);
        assertThat(days.getLast().get("date")).isEqualTo(java.time.LocalDate.now(ZoneOffset.UTC).toString());
        Map<String, Object> ledger = (Map<String, Object>) d.get("ledger");
        assertThat(ledger).containsKeys("size", "lastCheckpointSize", "unpublished");
        assertThat(((Map<String, Object>) d.get("handwriting")).get("threshold")).isEqualTo(0.5);
        assertThat(d).containsKeys("rateLimits", "system", "generatedAt");
        assertThat(((Number) ((Map<String, Object>) d.get("system")).get("databaseBytes")).longValue()).isPositive();
        assertThat(d.toString()).doesNotContain("ciphertext").doesNotContain("strokes");
    }

    @Test
    void theDashboardReportsHandwritingOutcomesAndRateLimitRejections() throws Exception {
        TestWallet admin = TestWallet.bootstrapAdmin(rest);
        Map<String, Object> before = get(admin, "/api/admin/dashboard").getBody();
        meters.counter(com.writeproof.handwriting.HandwritingService.VERIFICATIONS_METRIC,
                "purpose", "letter", "result", "rejected").increment(3);
        meters.counter(com.writeproof.security.RateLimitFilter.REJECTIONS_METRIC, "rule", "login-verify").increment();

        Map<String, Object> after = get(admin, "/api/admin/dashboard").getBody();

        long rejected = ((Number) ((Map<String, Object>) before.get("handwriting")).get("lettersRejected")).longValue();
        assertThat(((Map<String, Object>) after.get("handwriting")).get("lettersRejected")).isEqualTo((int) rejected + 3);
        assertThat((Map<String, Object>) ((Map<String, Object>) after.get("rateLimits")).get("rejections"))
                .containsKey("login-verify");
    }

    @Test
    void theAuditLogRecordsActionsPermanentlyNewestFirst() throws Exception {
        TestWallet admin = TestWallet.bootstrapAdmin(rest);
        UUID adminId = jdbc.sql("SELECT id FROM accounts WHERE public_key = :k").param("k", admin.publicKey)
                .query(UUID.class).single();
        audit.record(adminId, AdminRole.ADMIN, "test.first", "target-1", Map.of("n", 1));
        audit.record(adminId, AdminRole.ADMIN, "test.second", null, Map.of());

        List<Map<String, Object>> entries = rest.exchange("/api/admin/audit?limit=2", HttpMethod.GET,
                new HttpEntity<>(admin.headers()), List.class).getBody();

        assertThat(entries).extracting(e -> e.get("action")).containsExactly("test.second", "test.first");
        assertThat(entries.get(1)).containsEntry("target", "target-1")
                .containsEntry("actor", Base64Url.encode(admin.publicKey)).containsEntry("actorRole", "ADMIN");
        assertThat((Map<String, Object>) entries.get(1).get("detail")).containsEntry("n", 1);
        long olderThan = ((Number) entries.get(1).get("id")).longValue();
        List<Map<String, Object>> next = rest.exchange("/api/admin/audit?before=" + olderThan, HttpMethod.GET,
                new HttpEntity<>(admin.headers()), List.class).getBody();
        assertThat(next).allMatch(e -> ((Number) e.get("id")).longValue() < olderThan);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin_audit_log").update()).hasMessageContaining("append-only");
        assertThat(Instant.parse((String) entries.get(0).get("at"))).isBeforeOrEqualTo(Instant.now());
    }
}
