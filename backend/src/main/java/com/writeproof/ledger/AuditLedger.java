package com.writeproof.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Audits a Writeproof ledger from outside, with nothing but its URL:
 * <pre>
 * java -Dloader.main=com.writeproof.ledger.AuditLedger \
 *      -cp writeproof-backend.jar org.springframework.boot.loader.launch.PropertiesLauncher \
 *      https://writeproof.example [--state witness.json] [--key BASE64URL]
 *      [--rekor URL | --no-rekor] [--anchor-grace PT6H]
 * </pre>
 * With {@code --state}, the pinned key and last verified checkpoint are read from and saved to that
 * file, so each run proves the ledger only grew since the last one. Exits 0 when verified, 1 when
 * the ledger fails a check (or cannot be reached), 2 on bad usage. It also checks the ledger's anchors
 * in the public log ({@code --rekor}, Sigstore's by default; pinned like the ledger key): each must
 * verify there, no checkpoint may stay unanchored past {@code --anchor-grace}, and the log must hold no
 * checkpoint under the ledger key that the server doesn't publish. {@code --no-rekor} skips this.
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
        String rekorUrl = Rekor.PUBLIC_URL;
        Duration grace = Duration.ofHours(6);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--state" -> statePath = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--key" -> key = i + 1 < args.length ? args[++i] : null;
                case "--rekor" -> rekorUrl = i + 1 < args.length ? args[++i] : null;
                case "--no-rekor" -> rekorUrl = "";
                case "--anchor-grace" -> grace = i + 1 < args.length ? Duration.parse(args[++i]) : null;
                default -> url = args[i].startsWith("--") ? null : args[i];
            }
        }
        if (url == null || rekorUrl == null || grace == null) {
            System.err.println("usage: AuditLedger <base-url> [--state witness.json] [--key BASE64URL]"
                    + " [--rekor URL | --no-rekor] [--anchor-grace PT6H]");
            return 2;
        }
        ObjectMapper json = new ObjectMapper();
        try {
            Optional<LedgerAuditor.WitnessState> previous = statePath != null && Files.exists(statePath)
                    ? Optional.of(json.readValue(statePath.toFile(), LedgerAuditor.WitnessState.class))
                    : Optional.empty();
            HttpClient http = HttpClient.newHttpClient();
            Optional<Rekor> log = rekorUrl.isEmpty() ? Optional.empty()
                    : Optional.of(new Rekor(http, URI.create(rekorUrl), json));
            LedgerAuditor auditor = new LedgerAuditor(http, URI.create(url), json, log, grace);
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
