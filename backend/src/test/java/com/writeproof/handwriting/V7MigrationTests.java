package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Runs the real V7 migration against plaintext rows written in the V6 schema. */
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V7MigrationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private BiometricCipher cipher;

    @Autowired
    private ObjectMapper json;

    @Test
    void encryptsExistingEnrolmentsAndHistoryInPlace() throws Exception {
        String schema = "v7_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).locations("classpath:db/migration")
                .target("6").load().migrate();
        UUID account = UUID.randomUUID();
        String samples = "[{\"format\":\"writeproof.handwriting\",\"strokes\":[[1]]}]";
        String sample = "{\"format\":\"writeproof.handwriting\",\"strokes\":[[2]]}";

        try (Connection db = dataSource.getConnection(); Statement sql = db.createStatement()) {
            // This connection returns to the shared pool afterwards: always restore its search_path.
            try {
                sql.execute("SET search_path TO " + schema);
                sql.execute("INSERT INTO accounts (id, public_key, created_at) VALUES ('" + account
                        + "', decode(repeat('ab', 32), 'hex'), now())");
                sql.execute("INSERT INTO handwriting_enrolments (account_id, samples, created_at) VALUES ('"
                        + account + "', '" + samples + "'::jsonb, now())");
                sql.execute("INSERT INTO handwriting_history (account_id, sample, created_at) VALUES ('"
                        + account + "', '" + sample + "'::jsonb, now())");

                new V7__EncryptBiometricData(cipher).migrate(context(db));
            } finally {
                sql.execute("RESET search_path");
            }
        }

        JdbcClient jdbc = JdbcClient.create(dataSource);
        try {
            byte[] enrolment = jdbc.sql("SELECT samples_encrypted FROM " + schema + ".handwriting_enrolments")
                    .query(byte[].class).single();
            byte[] history = jdbc.sql("SELECT sample_encrypted FROM " + schema + ".handwriting_history")
                    .query(byte[].class).single();
            // JSONB normalizes whitespace, so compare as JSON.
            assertThat(json.readTree(cipher.decrypt(enrolment, BiometricCipher.enrolmentContext(account))))
                    .isEqualTo(json.readTree(samples));
            assertThat(json.readTree(cipher.decrypt(history, BiometricCipher.historyContext(account))))
                    .isEqualTo(json.readTree(sample));
            int plaintextColumns = jdbc.sql("""
                    SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = :schema AND column_name IN ('samples', 'sample')
                    """).param("schema", schema).query(Integer.class).single();
            assertThat(plaintextColumns).isZero();
        } finally {
            jdbc.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    private static Context context(Connection connection) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        };
    }
}
