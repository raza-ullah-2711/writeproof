package com.writeproof.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import java.security.KeyPairGenerator;
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
class AdminManagementApiTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    private TestWallet admin;

    @BeforeEach
    void admin() throws Exception {
        admin = TestWallet.bootstrapAdmin(rest);
    }

    private static String address(TestWallet w) {
        return Base64Url.encode(w.publicKey);
    }

    private ResponseEntity<Map> grant(TestWallet as, String address, String role) {
        return rest.exchange("/api/admin/admins/" + address, HttpMethod.PUT,
                new HttpEntity<>(Map.of("role", role), as.headers()), Map.class);
    }

    private ResponseEntity<Map> revoke(TestWallet as, String address) {
        return rest.exchange("/api/admin/admins/" + address, HttpMethod.DELETE, new HttpEntity<>(as.headers()),
                Map.class);
    }

    private Object roleOf(TestWallet w) {
        return rest.exchange("/api/admin/me", HttpMethod.GET, new HttpEntity<>(w.headers()), Map.class).getBody()
                .get("role");
    }

    private List<Map<String, Object>> members() {
        return rest.exchange("/api/admin/admins", HttpMethod.GET, new HttpEntity<>(admin.headers()), List.class)
                .getBody();
    }

    private List<String> auditActions(String target) {
        return jdbc.sql("SELECT action FROM admin_audit_log WHERE target = :t ORDER BY id").param("t", target)
                .query(String.class).list();
    }

    @Test
    void grantChangeAndRevokeTakeEffectAtOnceAndAreAudited() throws Exception {
        TestWallet person = TestWallet.create(rest);
        assertThat(roleOf(person)).isNull();

        assertThat(grant(admin, address(person), "MODERATOR").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(roleOf(person)).isEqualTo("MODERATOR");
        assertThat(rest.exchange("/api/admin/moderation/queue", HttpMethod.GET, new HttpEntity<>(person.headers()),
                List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        grant(admin, address(person), "MODERATOR"); // no change, no audit entry
        grant(admin, address(person), "ADMIN");
        assertThat(roleOf(person)).isEqualTo("ADMIN");
        Map<String, Object> member = members().stream().filter(m -> address(person).equals(m.get("publicKey")))
                .findFirst().orElseThrow();
        assertThat(member).containsEntry("role", "ADMIN").containsEntry("source", "granted")
                .containsEntry("grantedBy", address(admin)).containsEntry("hasAccount", true);

        assertThat(revoke(admin, address(person)).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(roleOf(person)).isNull();
        assertThat(rest.exchange("/api/admin/moderation/queue", HttpMethod.GET, new HttpEntity<>(person.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(revoke(admin, address(person)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(auditActions(address(person)))
                .containsExactly("admin.role-granted", "admin.role-changed", "admin.role-revoked");
    }

    @Test
    void aRoleCanBeGrantedBeforeTheAccountExists() throws Exception {
        byte[] raw = TestWallet.raw(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        String future = Base64Url.encode(raw);

        assertThat(grant(admin, future, "MODERATOR").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(members().stream().filter(m -> future.equals(m.get("publicKey"))).findFirst().orElseThrow())
                .containsEntry("hasAccount", false).containsEntry("accountId", null);
    }

    @Test
    void protectsBootstrapAdminsAndYourOwnRole() throws Exception {
        TestWallet other = TestWallet.create(rest);
        grant(admin, address(other), "ADMIN");

        assertThat(grant(admin, address(admin), "MODERATOR").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(revoke(admin, address(admin)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> self = revoke(other, address(other));
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) self.getBody().get("detail")).contains("your own role");
        assertThat(roleOf(admin)).isEqualTo("ADMIN");
        assertThat(members()).anySatisfy(m -> assertThat(m).containsEntry("publicKey", address(admin))
                .containsEntry("source", "configuration"));
        revoke(admin, address(other));
    }

    @Test
    void refusesSuspendedAccountsBadInputAndNonAdmins() throws Exception {
        TestWallet suspended = TestWallet.create(rest);
        UUID id = jdbc.sql("SELECT id FROM accounts WHERE public_key = :k").param("k", suspended.publicKey)
                .query(UUID.class).single();
        rest.exchange("/api/admin/accounts/" + id + "/suspension", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Spam"), admin.headers()), Map.class);

        assertThat(grant(admin, address(suspended), "MODERATOR").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(grant(admin, "not-an-address", "ADMIN").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(grant(admin, address(suspended), "SUPERUSER").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        TestWallet moderator = TestWallet.create(rest);
        grant(admin, address(moderator), "MODERATOR");
        TestWallet target = TestWallet.create(rest);
        assertThat(grant(moderator, address(target), "ADMIN").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange("/api/admin/admins", HttpMethod.GET, new HttpEntity<>(moderator.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        revoke(admin, address(moderator));
    }
}
