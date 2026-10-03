package com.writeproof.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.common.Base64Url;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** The permanent record of admin actions. Every admin write goes through {@link #record}. */
@Service
public class AuditLog {

    public record Entry(long id, Instant at, String actor, String actorRole, String action, String target,
                        Map<String, Object> detail) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    AuditLog(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void record(UUID actorAccount, AdminRole role, String action, String target, Map<String, ?> detail) {
        jdbc.sql("""
                INSERT INTO admin_audit_log (at, actor_key, actor_role, action, target, detail)
                SELECT :at, a.public_key, :role, :action, :target, CAST(:detail AS jsonb) FROM accounts a WHERE a.id = :actor
                """)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("role", role.name())
                .param("action", action)
                .param("target", target)
                .param("detail", write(detail))
                .param("actor", actorAccount)
                .update();
    }

    /** Newest first; pass the last id seen as {@code beforeId} for the next page. */
    @SuppressWarnings("unchecked")
    public List<Entry> list(Long beforeId, int limit) {
        return jdbc.sql("""
                SELECT id, at, actor_key, actor_role, action, target, detail::text AS detail
                  FROM admin_audit_log WHERE id < :before ORDER BY id DESC LIMIT :limit
                """)
                .param("before", beforeId == null ? Long.MAX_VALUE : beforeId)
                .param("limit", limit)
                .query((rs, row) -> new Entry(rs.getLong("id"),
                        rs.getObject("at", OffsetDateTime.class).toInstant(),
                        Base64Url.encode(rs.getBytes("actor_key")), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("target"), read(rs.getString("detail"))))
                .list();
    }

    /** Entries about one target (e.g. an account's address), newest first. */
    public List<Entry> forTarget(String target, int limit) {
        return jdbc.sql("""
                SELECT id, at, actor_key, actor_role, action, target, detail::text AS detail
                  FROM admin_audit_log WHERE target = :target ORDER BY id DESC LIMIT :limit
                """)
                .param("target", target)
                .param("limit", limit)
                .query((rs, row) -> new Entry(rs.getLong("id"),
                        rs.getObject("at", OffsetDateTime.class).toInstant(),
                        Base64Url.encode(rs.getBytes("actor_key")), rs.getString("actor_role"),
                        rs.getString("action"), rs.getString("target"), read(rs.getString("detail"))))
                .list();
    }

    private String write(Map<String, ?> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
