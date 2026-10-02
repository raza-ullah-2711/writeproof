package com.writeproof.calibration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.handwriting.BiometricCipher;
import com.writeproof.handwriting.HandwritingSample;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class CalibrationRepository {

    record Contributor(UUID contributorId, UUID accountId, String practiceName, Instant consentedAt) {}

    record StoredSample(long id, UUID contributorId, String kind, UUID targetContributorId, String device,
                        HandwritingSample sample, Instant createdAt) {}

    record Counts(int genuine, int forgeries) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final BiometricCipher cipher;

    CalibrationRepository(JdbcClient jdbc, ObjectMapper json, BiometricCipher cipher) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
    }

    Optional<Contributor> findByAccount(UUID accountId) {
        return jdbc.sql("SELECT * FROM calibration_contributors WHERE account_id = :id")
                .param("id", accountId)
                .query((rs, row) -> new Contributor(rs.getObject("contributor_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("practice_name"),
                        rs.getObject("consented_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    Optional<String> practiceName(UUID contributorId) {
        return jdbc.sql("SELECT practice_name FROM calibration_contributors WHERE contributor_id = :id")
                .param("id", contributorId).query(String.class).optional();
    }

    void insertContributor(Contributor c) {
        jdbc.sql("""
                INSERT INTO calibration_contributors (contributor_id, account_id, practice_name, consented_at)
                VALUES (:id, :account, :name, :at)
                """)
                .param("id", c.contributorId())
                .param("account", c.accountId())
                .param("name", c.practiceName())
                .param("at", OffsetDateTime.ofInstant(c.consentedAt(), ZoneOffset.UTC))
                .update();
    }

    /** Withdraws consent: the contributor and all their samples (and imitations of them) go. */
    void deleteContributor(UUID contributorId) {
        jdbc.sql("DELETE FROM calibration_contributors WHERE contributor_id = :id").param("id", contributorId).update();
    }

    void insertSample(UUID contributorId, String kind, UUID target, HandwritingSample sample, Instant at) {
        jdbc.sql("""
                INSERT INTO calibration_samples (contributor_id, kind, target_contributor_id, device, sample_encrypted, created_at)
                VALUES (:id, :kind, :target, :device, :sample, :at)
                """)
                .param("id", contributorId)
                .param("kind", kind)
                .param("target", target)
                .param("device", sample.device())
                .param("sample", cipher.encrypt(write(sample), context(contributorId)))
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    Counts counts(UUID contributorId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE kind = 'genuine'), count(*) FILTER (WHERE kind = 'forgery')
                  FROM calibration_samples WHERE contributor_id = :id
                """)
                .param("id", contributorId)
                .query((rs, row) -> new Counts(rs.getInt(1), rs.getInt(2)))
                .single();
    }

    /** Someone else with enough genuine practice samples to imitate, chosen at random. */
    Optional<UUID> randomTarget(UUID excluding, int minGenuine) {
        return jdbc.sql("""
                SELECT contributor_id FROM calibration_samples
                 WHERE kind = 'genuine' AND contributor_id <> :me
                 GROUP BY contributor_id HAVING count(*) >= :min
                 ORDER BY random() LIMIT 1
                """)
                .param("me", excluding)
                .param("min", minGenuine)
                .query(UUID.class)
                .optional();
    }

    Optional<HandwritingSample> randomGenuine(UUID contributorId) {
        return jdbc.sql("""
                SELECT sample_encrypted FROM calibration_samples
                 WHERE contributor_id = :id AND kind = 'genuine' ORDER BY random() LIMIT 1
                """)
                .param("id", contributorId)
                .query((rs, row) -> read(cipher.decrypt(rs.getBytes(1), context(contributorId))))
                .optional();
    }

    List<StoredSample> all() {
        return jdbc.sql("SELECT * FROM calibration_samples ORDER BY id")
                .query((rs, row) -> {
                    UUID contributor = rs.getObject("contributor_id", UUID.class);
                    return new StoredSample(rs.getLong("id"), contributor, rs.getString("kind"),
                            rs.getObject("target_contributor_id", UUID.class), rs.getString("device"),
                            read(cipher.decrypt(rs.getBytes("sample_encrypted"), context(contributor))),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .list();
    }

    private static String context(UUID contributorId) {
        return "calibration:" + contributorId;
    }

    private String write(HandwritingSample sample) {
        try {
            return json.writeValueAsString(sample);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private HandwritingSample read(String value) {
        try {
            return json.readValue(value, HandwritingSample.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored calibration sample is unreadable", e);
        }
    }
}
