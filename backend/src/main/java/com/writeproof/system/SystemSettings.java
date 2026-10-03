package com.writeproof.system;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runtime switches set by admins (Task 13d) and enforced where the guarded action happens.
 * Read from the database on every check: they change rarely and must take effect at once.
 */
@Service
public class SystemSettings {

    /** What everyone (signed in or not) may know about the service's state. */
    public record Status(boolean registrationOpen, boolean sendingEnabled, boolean openLettersEnabled,
                         String announcement) {}

    public static final int MAX_ANNOUNCEMENT = 280;

    private final JdbcClient jdbc;
    private final Clock clock;

    SystemSettings(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Status status() {
        Map<String, String> v = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value::text AS value FROM system_settings")
                .query((rs, row) -> v.put(rs.getString("key"), rs.getString("value")))
                .list();
        return new Status(!"false".equals(v.get("registration_open")), !"false".equals(v.get("sending_enabled")),
                !"false".equals(v.get("open_letters_enabled")), unquote(v.getOrDefault("announcement", "\"\"")));
    }

    public void requireRegistrationOpen() {
        if (!status().registrationOpen()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Writeproof isn't accepting new accounts right now");
        }
    }

    public void requireSendingEnabled() {
        if (!status().sendingEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sending letters is paused by Writeproof; try again later");
        }
    }

    public void requireOpenLettersEnabled() {
        if (!status().openLettersEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Publishing open letters is paused by Writeproof; try again later");
        }
    }

    /** Sets one switch; returns its previous value. Callers audit the change. */
    public boolean setFlag(String key, boolean on, UUID actor) {
        if (!key.equals("registration_open") && !key.equals("sending_enabled")
                && !key.equals("open_letters_enabled")) {
            throw new IllegalArgumentException("Unknown setting " + key);
        }
        boolean previous = switch (key) {
            case "registration_open" -> status().registrationOpen();
            case "sending_enabled" -> status().sendingEnabled();
            default -> status().openLettersEnabled();
        };
        write(key, on ? "true" : "false", actor);
        return previous;
    }

    /** Sets the site-wide announcement (empty clears it); returns the previous one. */
    public String setAnnouncement(String text, UUID actor) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() > MAX_ANNOUNCEMENT) {
            throw new IllegalArgumentException("An announcement can be at most " + MAX_ANNOUNCEMENT + " characters");
        }
        if (trimmed.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("An announcement is a single line of text");
        }
        String previous = status().announcement();
        write("announcement", quote(trimmed), actor);
        return previous;
    }

    private void write(String key, String json, UUID actor) {
        jdbc.sql("""
                UPDATE system_settings SET value = CAST(:value AS jsonb), updated_at = :at,
                       updated_by = (SELECT public_key FROM accounts WHERE id = :actor)
                 WHERE key = :key
                """)
                .param("value", json).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("actor", actor).param("key", key)
                .update();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String unquote(String json) {
        try {
            return JSON.readValue(json, String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }
}
