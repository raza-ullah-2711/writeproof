package com.writeproof.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestWallet;
import com.writeproof.TestcontainersConfiguration;
import com.writeproof.common.Base64Url;
import com.writeproof.handwriting.HandwritingSample;
import com.writeproof.handwriting.SyntheticSignatures;
import com.writeproof.letters.LetterHashing;
import com.writeproof.openletters.OpenLetterHashing;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
class ModerationApiTests {

    private static final DateTimeFormatter SENT_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEEDS = new AtomicLong(7000);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private final SyntheticSignatures.Writer hand = new SyntheticSignatures.Writer(4004);
    private TestWallet author;
    private TestWallet moderator;
    private TestWallet admin;

    @BeforeEach
    void setUp() throws Exception {
        author = TestWallet.create(rest);
        rest.exchange("/api/handwriting/enrolment", HttpMethod.POST, new HttpEntity<>(
                Map.of("samples", List.of(hand.genuine(1, "pen"), hand.genuine(2, "pen"), hand.genuine(3, "pen"))),
                author.headers()), Map.class);
        moderator = TestWallet.create(rest);
        jdbc.sql("INSERT INTO admin_roles (public_key, role, granted_at) VALUES (:k, 'MODERATOR', :at)")
                .param("k", moderator.publicKey).param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        admin = TestWallet.bootstrapAdmin(rest);
    }

    /** Publishes an open letter as the author; returns its hash. */
    private String publish(String body) throws Exception {
        HandwritingSample s = hand.genuine(SEEDS.incrementAndGet(), "pen");
        String handwriting = json.writeValueAsString(new HandwritingSample(s.format(), s.version(),
                Instant.now().toString(), s.device(), s.width(), s.height(), s.strokes()));
        String sentAt = SENT_AT.format(Instant.now());
        byte[] hash = OpenLetterHashing.letterHash(author.publicKey, sentAt, LetterHashing.handwritingHash(handwriting),
                body);
        ResponseEntity<Map> published = rest.exchange("/api/me/open-letters", HttpMethod.POST, new HttpEntity<>(
                Map.of("sentAt", sentAt, "body", body, "handwriting", handwriting, "signature",
                        Base64Url.encode(TestWallet.sign(author.identity.getPrivate(),
                                LetterHashing.signedMessage(hash)))), author.headers()), Map.class);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Base64Url.encode(hash);
    }

    private ResponseEntity<Map> report(TestWallet as, String hash, String category, String note) {
        Map<String, Object> body = note == null ? Map.of("category", category) : Map.of("category", category, "note", note);
        return rest.exchange("/api/open-letters/" + hash + "/reports", HttpMethod.POST,
                new HttpEntity<>(body, as == null ? new org.springframework.http.HttpHeaders() : as.headers()), Map.class);
    }

    private ResponseEntity<Map> call(TestWallet as, HttpMethod method, String path, Object body) {
        return rest.exchange(path, method, new HttpEntity<>(body, as.headers()), Map.class);
    }

    private List<Map<String, Object>> queue(TestWallet as) {
        return rest.exchange("/api/admin/moderation/queue", HttpMethod.GET, new HttpEntity<>(as.headers()), List.class)
                .getBody();
    }

    @Test
    void anyReaderCanReportOnceAndModeratorsSeeTheQueue() throws Exception {
        String hash = publish("Buy cheap followers now!");
        TestWallet reader = TestWallet.create(rest);

        assertThat(report(null, hash, "spam", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(report(reader, hash, "spam", "Same message posted everywhere").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(report(reader, hash, "harassment", null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(report(null, hash, "boring", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report(null, hash, "other", "x".repeat(501)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(report(null, Base64Url.encode(new byte[32]), "spam", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> c = queue(moderator).stream().filter(q -> hash.equals(q.get("letterHash"))).findFirst()
                .orElseThrow();
        assertThat(c).containsEntry("body", "Buy cheap followers now!").containsEntry("openReports", 2);
        assertThat((Map<String, Object>) c.get("openReportsByCategory")).containsEntry("spam", 2);
        assertThat((List<Map<String, Object>>) c.get("reports")).extracting(r -> r.get("fromAccount"))
                .containsExactly(false, true);
        assertThat(queue(admin)).extracting(q -> q.get("letterHash")).contains(hash);
        assertThat(rest.exchange("/api/admin/moderation/queue", HttpMethod.GET, new HttpEntity<>(reader.headers()),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> dashboard = call(admin, HttpMethod.GET, "/api/admin/dashboard", null).getBody();
        assertThat(((Number) ((Map<String, Object>) dashboard.get("moderation")).get("openReports")).longValue())
                .isGreaterThanOrEqualTo(2);
        assertThat(call(moderator, HttpMethod.GET, "/api/admin/dashboard", null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void dismissingResolvesTheReportsAndKeepsTheLetterUp() throws Exception {
        String hash = publish("A strongly worded but fair opinion.");
        report(null, hash, "harassment", null);

        ResponseEntity<Map> dismissed = call(moderator, HttpMethod.POST,
                "/api/admin/moderation/letters/" + hash + "/dismiss", Map.of("note", "Opinion, not harassment"));

        assertThat(dismissed.getBody()).containsEntry("reportsResolved", 1);
        assertThat(queue(moderator)).extracting(q -> q.get("letterHash")).doesNotContain(hash);
        assertThat(call(moderator, HttpMethod.POST, "/api/admin/moderation/letters/" + hash + "/dismiss", null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody())
                .containsEntry("body", "A strongly worded but fair opinion.").containsEntry("removed", null);
        Map<String, Object> entry = jdbc.sql("SELECT actor_role, action, detail::text AS d FROM admin_audit_log "
                        + "WHERE target = :t ORDER BY id DESC LIMIT 1").param("t", hash)
                .query((rs, row) -> Map.<String, Object>of("role", rs.getString(1), "action", rs.getString(2),
                        "detail", rs.getString(3))).single();
        assertThat(entry).containsEntry("role", "MODERATOR").containsEntry("action", "moderation.dismissed");
        assertThat((String) entry.get("detail")).contains("Opinion, not harassment");
    }

    @Test
    void removalDeletesTheTextButKeepsTheRecordAndTheLedgerEntry() throws Exception {
        String text = "Here is someone's home address: 1 Example Street";
        String hash = publish(text);
        report(null, hash, "harassment", null);
        long seq = ((Number) ((Map<String, Object>) rest.getForEntity("/api/open-letters/" + hash, Map.class)
                .getBody().get("ledger")).get("seq")).longValue();

        ResponseEntity<Map> removed = call(moderator, HttpMethod.POST,
                "/api/admin/moderation/letters/" + hash + "/remove", Map.of("category", "harassment"));
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody()).containsEntry("body", null).containsEntry("openReports", 0);

        Map<String, Object> publicView = rest.getForEntity("/api/open-letters/" + hash, Map.class).getBody();
        assertThat(publicView).containsEntry("body", null).containsEntry("letterHash", hash)
                .containsKeys("signature", "author", "ledger");
        assertThat((Map<String, Object>) publicView.get("removed")).containsEntry("category", "harassment");
        assertThat(jdbc.sql("SELECT payload_hash FROM ledger_entries WHERE seq = :s").param("s", seq)
                .query(byte[].class).single()).isEqualTo(Base64Url.decode(hash));
        assertThat(report(null, hash, "spam", null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(call(admin, HttpMethod.POST, "/api/admin/moderation/letters/" + hash + "/remove",
                Map.of("category", "spam")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String audit = jdbc.sql("SELECT detail::text FROM admin_audit_log WHERE target = :t AND action = "
                + "'moderation.removed'").param("t", hash).query(String.class).single();
        assertThat(audit).contains("harassment").doesNotContain("Example Street");
        List<Map<String, Object>> mine = rest.exchange("/api/me/open-letters", HttpMethod.GET,
                new HttpEntity<>(author.headers()), List.class).getBody();
        assertThat(mine.stream().filter(l -> hash.equals(l.get("letterHash"))).findFirst().orElseThrow())
                .containsEntry("body", null);
    }

    @Test
    void moderatorsCanRemoveUnreportedLettersButNotWithAnUnknownCategory() throws Exception {
        String hash = publish("Unreported");

        assertThat(call(moderator, HttpMethod.POST, "/api/admin/moderation/letters/" + hash + "/remove",
                Map.of("category", "dislike")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(moderator, HttpMethod.POST, "/api/admin/moderation/letters/" + hash + "/remove",
                Map.of("category", "illegal")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(moderator, HttpMethod.GET, "/api/admin/moderation/letters/" + Base64Url.encode(new byte[32]),
                null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDatabaseAllowsOnlyARecordedTakedown() throws Exception {
        String hash = publish("Protected by the trigger");
        byte[] raw = Base64Url.decode(hash);

        assertThatThrownBy(() -> jdbc.sql("UPDATE open_letters SET body = NULL WHERE letter_hash = :h")
                .param("h", raw).update()).hasMessageContaining("append-only");
        jdbc.sql("INSERT INTO open_letter_removals (letter_hash, category, removed_at, removed_by) "
                        + "VALUES (:h, 'other', now(), :k)").param("h", raw).param("k", admin.publicKey).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE open_letters SET body = 'rewritten' WHERE letter_hash = :h")
                .param("h", raw).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE open_letters SET body = NULL, sent_at = 'x' WHERE letter_hash = :h")
                .param("h", raw).update()).hasMessageContaining("append-only");
        assertThat(jdbc.sql("UPDATE open_letters SET body = NULL WHERE letter_hash = :h").param("h", raw).update())
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM open_letters WHERE letter_hash = :h").param("h", raw).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM open_letter_removals WHERE letter_hash = :h").param("h", raw)
                .update()).hasMessageContaining("append-only");
    }
}
