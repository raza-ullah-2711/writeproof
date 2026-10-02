package com.writeproof.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.common.Base64Url;
import com.writeproof.identity.Ed25519;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * An outside witness's check of a Writeproof ledger, over its public API only. It checks that the
 * ledger key is the one the witness pinned, that every checkpoint is signed by it, and that each
 * checkpoint extends the one before it, starting from the last checkpoint this witness verified.
 * A rewritten or forked ledger fails that last check. Run it from {@link AuditLedger}.
 */
public class LedgerAuditor {

    /** What a witness remembers between runs: the key it pinned and the last checkpoint it verified. */
    public record WitnessState(String publicKey, long size, String root) {}

    public record Report(boolean ok, List<String> lines, WitnessState state) {}

    private static final int PAGE = 1000;

    private final HttpClient http;
    private final URI base;
    private final ObjectMapper json;

    public LedgerAuditor(HttpClient http, URI base, ObjectMapper json) {
        this.http = http;
        this.base = base;
        this.json = json;
    }

    /**
     * @param pinnedKey a key given on the command line, if any; must match the server's
     * @param previous  the state from this witness's last successful run, if any
     */
    public Report audit(Optional<String> pinnedKey, Optional<WitnessState> previous) throws IOException {
        List<String> lines = new ArrayList<>();
        String key = get("/api/ledger/key").path("publicKey").asText();
        Optional<String> expectedKey = pinnedKey.or(() -> previous.map(WitnessState::publicKey));
        if (expectedKey.isPresent() && !expectedKey.get().equals(key)) {
            lines.add("FAIL ledger key changed: pinned " + expectedKey.get() + ", server has " + key);
            return new Report(false, lines, null);
        }
        lines.add((expectedKey.isPresent() ? "ok   ledger key " : "ok   ledger key (pinned now) ") + key);
        byte[] publicKey = Base64Url.decode(key);

        // The chain to check: where we left off, every checkpoint published since, and the live one.
        long after = previous.map(WitnessState::size).orElse(0L);
        List<Checkpoint> chain = new ArrayList<>();
        while (true) {
            List<Checkpoint> page = new ArrayList<>();
            for (JsonNode node : get("/api/ledger/checkpoints?after=" + after + "&limit=" + PAGE)) {
                page.add(checkpoint(node));
            }
            chain.addAll(page);
            if (page.size() < PAGE) {
                break;
            }
            after = page.getLast().size();
        }
        chain.add(checkpoint(get("/api/ledger/checkpoint")));

        boolean ok = true;
        for (Checkpoint c : chain) {
            if (!Ed25519.verify(publicKey, c.signedMessage(), c.signature())) {
                lines.add("FAIL checkpoint at size " + c.size() + " is not signed by the ledger key");
                ok = false;
            }
        }
        long prevSize = previous.map(WitnessState::size).orElse(0L);
        byte[] prevRoot = previous.map(s -> Base64Url.decode(s.root())).orElse(null);
        for (Checkpoint c : chain) {
            if (!ok) {
                break;
            }
            if (prevRoot == null || prevSize == 0) {
                // Nothing verified before this one: it is the witness's starting point.
                lines.add("ok   checkpoint at size " + c.size() + " signed (starting point)");
            } else if (c.size() < prevSize) {
                lines.add("FAIL ledger shrank from " + prevSize + " to " + c.size() + " entries");
                ok = false;
            } else if (c.size() == prevSize) {
                if (!Arrays.equals(c.root(), prevRoot)) {
                    lines.add("FAIL ledger at " + c.size() + " entries has a different root than before: "
                            + "history was rewritten or forked");
                    ok = false;
                }
            } else if (!consistent(prevSize, c.size(), prevRoot, c.root())) {
                lines.add("FAIL ledger at " + c.size() + " entries does not extend the ledger at " + prevSize
                        + ": history was rewritten or forked");
                ok = false;
            } else {
                lines.add("ok   checkpoint at size " + c.size() + " signed and consistent");
            }
            prevSize = c.size();
            prevRoot = c.root();
        }
        Checkpoint latest = chain.getLast();
        if (ok) {
            lines.add("ok   ledger verified up to " + latest.size() + " entries");
            return new Report(true, lines, new WitnessState(key, latest.size(), Base64Url.encode(latest.root())));
        }
        return new Report(false, lines, null);
    }

    private boolean consistent(long from, long to, byte[] oldRoot, byte[] newRoot) throws IOException {
        List<byte[]> proof = new ArrayList<>();
        for (JsonNode hash : get("/api/ledger/proof/consistency?from=" + from + "&to=" + to).path("proof")) {
            proof.add(Base64Url.decode(hash.asText()));
        }
        return MerkleTree.verifyConsistency(from, to, oldRoot, newRoot, proof);
    }

    private static Checkpoint checkpoint(JsonNode node) {
        return new Checkpoint(node.path("size").asLong(), Base64Url.decode(node.path("root").asText()),
                node.path("timestampMillis").asLong(), Base64Url.decode(node.path("signature").asText()));
    }

    private JsonNode get(String path) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
