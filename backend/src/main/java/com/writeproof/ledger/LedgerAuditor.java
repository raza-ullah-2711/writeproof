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
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An outside witness's check of a Writeproof ledger, over its public API only. It checks that the
 * ledger key is the one the witness pinned, or that a chain of {@link KeyRotation}s signed by the
 * pinned key leads to it; that every checkpoint is signed by the key in charge at its size; and that each
 * checkpoint extends the one before it, starting from the last checkpoint this witness verified.
 * A rewritten or forked ledger fails that last check. Run it from {@link AuditLedger}.
 */
public class LedgerAuditor {

    /**
     * What a witness remembers between runs: the key it pinned and the last checkpoint it verified;
     * with a public log, that log's key (pinned like the ledger key) and the last anchor it verified.
     */
    public record WitnessState(String publicKey, long size, String root, String logKey, long anchoredSize) {
        public WitnessState(String publicKey, long size, String root) {
            this(publicKey, size, root, null, 0);
        }
    }

    public record Report(boolean ok, List<String> lines, WitnessState state) {}

    private static final int PAGE = 1000;

    private final HttpClient http;
    private final URI base;
    private final ObjectMapper json;
    private final Optional<Rekor> log;
    private final Duration anchorGrace;

    /** A witness that checks the ledger only, not its anchors in a public log. */
    public LedgerAuditor(HttpClient http, URI base, ObjectMapper json) {
        this(http, base, json, Optional.empty(), Duration.ZERO);
    }

    /**
     * @param log         the public log the ledger's checkpoints must be anchored in, if checked
     * @param anchorGrace how long after it is signed a published checkpoint may still be unanchored
     */
    public LedgerAuditor(HttpClient http, URI base, ObjectMapper json, Optional<Rekor> log, Duration anchorGrace) {
        this.http = http;
        this.base = base;
        this.json = json;
        this.log = log;
        this.anchorGrace = anchorGrace;
    }

    /**
     * @param pinnedKey a key given on the command line, if any; must match the server's
     * @param previous  the state from this witness's last successful run, if any
     */
    public Report audit(Optional<String> pinnedKey, Optional<WitnessState> previous) throws IOException {
        List<String> lines = new ArrayList<>();
        JsonNode keyInfo = get("/api/ledger/key");
        String key = keyInfo.path("publicKey").asText();
        List<KeyRotation> rotations = new ArrayList<>();
        for (JsonNode node : keyInfo.path("rotations")) {
            rotations.add(rotation(node));
        }
        // Every key the ledger had, each vouched for by the one before: the first is trusted on first use.
        List<byte[]> keys = new ArrayList<>();
        keys.add(rotations.isEmpty() ? Base64Url.decode(key) : rotations.getFirst().oldKey());
        try {
            KeyRotation.follow(keys.getFirst(), Base64Url.decode(key), rotations).forEach(r -> keys.add(r.newKey()));
        } catch (KeyRotation.BrokenChainException e) {
            lines.add("FAIL " + e.getMessage());
            return new Report(false, lines, null);
        }
        Optional<String> expectedKey = pinnedKey.or(() -> previous.map(WitnessState::publicKey));
        int pinned = expectedKey.map(k -> indexOf(keys, Base64Url.decode(k))).orElse(0);
        if (pinned < 0) {
            lines.add("FAIL ledger key changed: pinned " + expectedKey.get() + ", server has " + key
                    + ", and no rotation signed by the pinned key leads to it");
            return new Report(false, lines, null);
        }
        List<KeyRotation> followed = rotations.subList(pinned, rotations.size());
        for (KeyRotation r : followed) {
            lines.add("ok   ledger key rotated from " + Base64Url.encode(r.oldKey()) + " to "
                    + Base64Url.encode(r.newKey()) + " at " + r.size() + " entries");
        }
        lines.add((expectedKey.isPresent() ? "ok   ledger key " : "ok   ledger key (pinned now) ") + key);

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
            if (!signedByKeyInCharge(c, keys, rotations)) {
                lines.add("FAIL checkpoint at size " + c.size() + " is not signed by the ledger key");
                ok = false;
            }
        }
        // History must also pass through each checkpoint an old key handed over at.
        for (KeyRotation r : followed) {
            chain.add(new Checkpoint(r.size(), r.root(), r.timestampMillis(), r.signature()));
        }
        chain.sort(Comparator.comparingLong(Checkpoint::size));
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
        if (!ok) {
            return new Report(false, lines, null);
        }
        lines.add("ok   ledger verified up to " + latest.size() + " entries");
        WitnessState state = new WitnessState(key, latest.size(), Base64Url.encode(latest.root()));
        if (log.isPresent()) {
            state = checkAnchors(log.get(), keys, rotations, previous, state, lines);
            if (state == null) {
                return new Report(false, lines, null);
            }
        }
        return new Report(true, lines, state);
    }

    /**
     * Holds the ledger to its anchors in the public log: each one is checked in the log itself, no
     * published checkpoint stays unanchored past the grace period, and the log holds nothing under
     * the ledger key that the server doesn't publish. A server showing someone a different history
     * must anchor it too (their browser insists), so it would surface here. Null on failure.
     */
    private WitnessState checkAnchors(Rekor rekor, List<byte[]> keys, List<KeyRotation> rotations,
                                      Optional<WitnessState> previous, WitnessState state, List<String> lines)
            throws IOException {
        PublicKey logKey = rekor.publicKey();
        String logKeyText = Base64.getEncoder().encodeToString(logKey.getEncoded());
        String pinnedLogKey = previous.map(WitnessState::logKey).orElse(null);
        if (pinnedLogKey != null && !pinnedLogKey.equals(logKeyText)) {
            lines.add("FAIL the public log's key changed since this witness last checked: " + rekor.url());
            return null;
        }
        // Every checkpoint the server ever published, and the hash Rekor files each one under.
        Map<Long, Checkpoint> published = new HashMap<>();
        Set<String> publishedHashes = new HashSet<>();
        for (JsonNode node : pages("/api/ledger/checkpoints")) {
            Checkpoint c = checkpoint(node);
            published.put(c.size(), c);
            publishedHashes.add(HexFormat.of().formatHex(Rekor.sha256(c.signedMessage())));
        }
        Set<String> anchoredUuids = new HashSet<>();
        Set<Long> anchoredSizes = new HashSet<>();
        long verifiedBefore = previous.map(WitnessState::anchoredSize).orElse(0L);
        long anchoredSize = verifiedBefore;
        int checked = 0;
        for (JsonNode a : pages("/api/ledger/anchors")) {
            long size = a.path("size").asLong();
            String uuid = a.path("uuid").asText();
            anchoredUuids.add(uuid);
            anchoredSizes.add(size);
            if (!rekor.url().equals(a.path("logUrl").asText())) {
                lines.add("FAIL the checkpoint at size " + size + " is anchored in " + a.path("logUrl").asText()
                        + ", not in " + rekor.url());
                return null;
            }
            if (size <= verifiedBefore) {
                continue; // checked on an earlier run
            }
            Checkpoint c = published.get(size);
            byte[] signer = c == null ? null : keyInCharge(c, keys, rotations);
            if (signer == null) {
                lines.add("FAIL an anchor points at size " + size + ", which has no published, signed checkpoint");
                return null;
            }
            try {
                Rekor.verify(rekor.entry(uuid), logKey, c.signedMessage(), signer, json);
            } catch (Rekor.VerificationException e) {
                lines.add("FAIL the anchor of the checkpoint at size " + size + " doesn't hold: " + e.getMessage());
                return null;
            }
            anchoredSize = Math.max(anchoredSize, size);
            checked++;
        }
        long deadline = System.currentTimeMillis() - anchorGrace.toMillis();
        for (Checkpoint c : published.values()) {
            if (!anchoredSizes.contains(c.size()) && c.timestampMillis() < deadline) {
                lines.add("FAIL the checkpoint at size " + c.size() + " was never anchored in " + rekor.url());
                return null;
            }
        }
        lines.add("ok   " + checked + " new anchor(s) verified in " + rekor.url() + " (anchored up to " + anchoredSize
                + " entries)");
        // Everything the log holds under the ledger's keys must be a checkpoint the server publishes.
        for (byte[] key : keys) {
            for (String uuid : rekor.search(Rekor.keyIndexHash(key))) {
                if (anchoredUuids.contains(uuid)) {
                    continue;
                }
                byte[] hash = Rekor.payloadHashUnder(rekor.entry(uuid), key, json);
                if (hash != null && !publishedHashes.contains(HexFormat.of().formatHex(hash))) {
                    lines.add("FAIL the public log holds a checkpoint signed by the ledger key that the server doesn't "
                            + "publish (" + rekor.url() + " entry " + uuid + "): it may be showing someone a "
                            + "different history");
                    return null;
                }
            }
        }
        lines.add("ok   the public log holds no checkpoint under the ledger key that the server doesn't publish");
        return new WitnessState(state.publicKey(), state.size(), state.root(), logKeyText, anchoredSize);
    }

    /** Every item of a paged list endpoint ({@code ?after=size&limit=}), in order. */
    private List<JsonNode> pages(String path) throws IOException {
        List<JsonNode> all = new ArrayList<>();
        long after = 0;
        while (true) {
            JsonNode page = get(path + "?after=" + after + "&limit=" + PAGE);
            page.forEach(all::add);
            if (page.size() < PAGE) {
                return all;
            }
            after = page.get(page.size() - 1).path("size").asLong();
        }
    }

    private boolean consistent(long from, long to, byte[] oldRoot, byte[] newRoot) throws IOException {
        List<byte[]> proof = new ArrayList<>();
        for (JsonNode hash : get("/api/ledger/proof/consistency?from=" + from + "&to=" + to).path("proof")) {
            proof.add(Base64Url.decode(hash.asText()));
        }
        return MerkleTree.verifyConsistency(from, to, oldRoot, newRoot, proof);
    }

    /**
     * True if the key in charge at the checkpoint's size signed it. Key i (with {@code rotations[i-1]}
     * leading to it) vouches for sizes from that rotation's size up to the next rotation's size.
     */
    private static boolean signedByKeyInCharge(Checkpoint c, List<byte[]> keys, List<KeyRotation> rotations) {
        return keyInCharge(c, keys, rotations) != null;
    }

    /** The key in charge at the checkpoint's size that signed it, or null. */
    private static byte[] keyInCharge(Checkpoint c, List<byte[]> keys, List<KeyRotation> rotations) {
        for (int i = 0; i < keys.size(); i++) {
            long from = i == 0 ? 0 : rotations.get(i - 1).size();
            long until = i == rotations.size() ? Long.MAX_VALUE : rotations.get(i).size();
            if (from <= c.size() && c.size() <= until && Ed25519.verify(keys.get(i), c.signedMessage(), c.signature())) {
                return keys.get(i);
            }
        }
        return null;
    }

    private static int indexOf(List<byte[]> keys, byte[] key) {
        for (int i = 0; i < keys.size(); i++) {
            if (Arrays.equals(keys.get(i), key)) {
                return i;
            }
        }
        return -1;
    }

    private static KeyRotation rotation(JsonNode node) {
        return new KeyRotation(Base64Url.decode(node.path("oldKey").asText()),
                Base64Url.decode(node.path("newKey").asText()), node.path("size").asLong(),
                Base64Url.decode(node.path("root").asText()), node.path("timestampMillis").asLong(),
                Base64Url.decode(node.path("signature").asText()));
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
