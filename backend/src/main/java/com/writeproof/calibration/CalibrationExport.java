package com.writeproof.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Operator-only export of the calibration data, decrypted, as a dataset file:
 * <pre>
 * java -jar writeproof-backend.jar --spring.main.web-application-type=none \
 *      --writeproof.calibration.export-to=/secure/dataset.json
 * </pre>
 * Writers are pseudonymous contributor ids; no account ids or practice names are written. The
 * file contains biometric data: keep it on encrypted storage and delete it after evaluation.
 */
@Component
@ConditionalOnProperty("writeproof.calibration.export-to")
class CalibrationExport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalibrationExport.class);

    private final CalibrationRepository calibration;
    private final ObjectMapper json;
    private final String target;
    private final ConfigurableApplicationContext context;

    CalibrationExport(CalibrationRepository calibration, ObjectMapper json,
                      @Value("${writeproof.calibration.export-to}") String target,
                      ConfigurableApplicationContext context) {
        this.calibration = calibration;
        this.json = json;
        this.target = target;
        this.context = context;
    }

    /** A one-shot command: export, then shut the application down. */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        exportTo(Path.of(target));
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    int exportTo(Path file) throws Exception {
        List<CalibrationDataset.Entry> entries = calibration.all().stream()
                .map(s -> new CalibrationDataset.Entry(
                        s.contributorId().toString(),
                        s.kind(),
                        s.targetContributorId() == null ? null : s.targetContributorId().toString(),
                        s.createdAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        s.sample()))
                .toList();
        CalibrationDataset dataset = new CalibrationDataset(CalibrationDataset.FORMAT, 1,
                "writeproof calibration export", entries);
        Files.writeString(file, json.writeValueAsString(dataset));
        log.info("Exported {} calibration samples to {}", entries.size(), file);
        return entries.size();
    }
}
