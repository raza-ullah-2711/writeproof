package com.writeproof.handwriting;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

/**
 * Encrypts existing enrolments and signature history in place (plaintext JSONB to AES-GCM
 * BYTEA). A Java migration because the key is only available to the application. Spring Boot
 * registers JavaMigration beans with Flyway, so it runs in order between V6 and later scripts.
 */
@Component
class V7__EncryptBiometricData extends BaseJavaMigration {

    private final BiometricCipher cipher;

    V7__EncryptBiometricData(BiometricCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (Statement ddl = db.createStatement()) {
            ddl.execute("ALTER TABLE handwriting_enrolments ADD COLUMN samples_encrypted BYTEA");
            ddl.execute("ALTER TABLE handwriting_history ADD COLUMN sample_encrypted BYTEA");
        }
        try (Statement read = db.createStatement();
             ResultSet rows = read.executeQuery("SELECT account_id, samples::text FROM handwriting_enrolments");
             PreparedStatement write = db.prepareStatement(
                     "UPDATE handwriting_enrolments SET samples_encrypted = ? WHERE account_id = ?")) {
            while (rows.next()) {
                UUID account = rows.getObject(1, UUID.class);
                write.setBytes(1, cipher.encrypt(rows.getString(2), BiometricCipher.enrolmentContext(account)));
                write.setObject(2, account);
                write.executeUpdate();
            }
        }
        try (Statement read = db.createStatement();
             ResultSet rows = read.executeQuery("SELECT id, account_id, sample::text FROM handwriting_history");
             PreparedStatement write = db.prepareStatement(
                     "UPDATE handwriting_history SET sample_encrypted = ? WHERE id = ?")) {
            while (rows.next()) {
                UUID account = rows.getObject(2, UUID.class);
                write.setBytes(1, cipher.encrypt(rows.getString(3), BiometricCipher.historyContext(account)));
                write.setLong(2, rows.getLong(1));
                write.executeUpdate();
            }
        }
        try (Statement ddl = db.createStatement()) {
            ddl.execute("ALTER TABLE handwriting_enrolments DROP COLUMN samples");
            ddl.execute("ALTER TABLE handwriting_enrolments ALTER COLUMN samples_encrypted SET NOT NULL");
            ddl.execute("ALTER TABLE handwriting_history DROP COLUMN sample");
            ddl.execute("ALTER TABLE handwriting_history ALTER COLUMN sample_encrypted SET NOT NULL");
        }
    }
}
