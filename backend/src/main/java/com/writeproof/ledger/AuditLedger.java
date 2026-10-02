package com.writeproof.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Audits a Writeproof ledger from outside, with nothing but its URL:
 * <pre>
 * java -Dloader.main=com.writeproof.ledger.AuditLedger \
 *      -cp writeproof-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *      https://writeproof.example [--state witness.json] [--key BASE64URL]
 * </pre>
 * With {@code --state}, the pinned key and last verified checkpoint are read from and saved to that
 * file, so each run proves the ledger only grew since the last one. Exits 0 when verified, 1 when
 * the ledger fails a check (or cannot be reached), 2 on bad usage.
 */
public final class AuditLedger {

    private AuditLedger() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        String url = null;
        Path statePath = null;
        String key = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--state" -> statePath = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--key" -> key = i + 1 < args.length ? args[++i] : null;
                default -> url = args[i].startsWith("--") ? null : args[i];
            }
        }
        if (url == null) {
            System.err.println("usage: AuditLedger <base-url> [--state witness.json] [--key BASE64URL]");
            return 2;
        }
        ObjectMapper json = new ObjectMapper();
        try {
            Optional<LedgerAuditor.WitnessState> previous = statePath != null && Files.exists(statePath)
                    ? Optional.of(json.readValue(statePath.toFile(), LedgerAuditor.WitnessState.class))
                    : Optional.empty();
            LedgerAuditor auditor = new LedgerAuditor(HttpClient.newHttpClient(), URI.create(url), json);
            LedgerAuditor.Report report = auditor.audit(Optional.ofNullable(key), previous);
            report.lines().forEach(System.out::println);
            if (report.ok() && statePath != null) {
                json.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), report.state());
            }
            return report.ok() ? 0 : 1;
        } catch (Exception e) {
            System.out.println("FAIL " + e.getMessage());
            return 1;
        }
    }
}
