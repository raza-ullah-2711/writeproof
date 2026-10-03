package com.writeproof.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writeproof.identity.Ed25519;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A Rekor v1 transparency log: Sigstore's public one ({@link #PUBLIC_URL}) unless configured
 * otherwise. Ledger checkpoints are anchored there as DSSE entries signed by the ledger key, so
 * every checkpoint the operator ever signed is in one public, append-only log that it doesn't run.
 *
 * <p>Rekor keeps only the payload's hash, and indexes the entry by it and by the hash of the
 * verifier key exactly as submitted. The key is therefore always sent in one canonical form
 * ({@link #pem}): clients accept nothing else, so every anchor under the ledger key is searchable.
 * See docs/ledger.md, "Anchoring in a public log".
 */
public final class Rekor {

    public static final String PUBLIC_URL = "https://rekor.sigstore.dev";
    /** The DSSE payload type of an anchored checkpoint; the payload is its signed message. */
    public static final String PAYLOAD_TYPE = "application/vnd.writeproof.checkpoint+text";

    private static final HexFormat HEX = HexFormat.of();

    public record InclusionProof(long logIndex, long treeSize, byte[] rootHash, List<byte[]> hashes,
                                 String checkpoint) {}

    /** A log entry as Rekor returns it; {@code body} is the canonical entry its leaf hashes. */
    public record Entry(String uuid, long logIndex, long integratedTime, byte[] body, InclusionProof proof) {}

    /** An entry that doesn't prove what it should. */
    public static class VerificationException extends Exception {
        VerificationException(String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final URI base;
    private final ObjectMapper json;

    public Rekor(HttpClient http, URI base, ObjectMapper json) {
        this.http = http;
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.json = json;
    }

    /** The log's base URL, without a trailing slash: how anchors name their log. */
    public String url() {
        return base.toString().replaceAll("/+$", "");
    }

    /** Logs a DSSE entry: {@code signature} is the ledger key's over {@link #pae} of the payload. */
    public Entry submit(byte[] payload, byte[] signature, byte[] ledgerKey) throws IOException {
        String envelope = json.writeValueAsString(Map.of(
                "payloadType", PAYLOAD_TYPE,
                "payload", b64(payload),
                "signatures", List.of(Map.of("sig", b64(signature)))));
        String request = json.writeValueAsString(Map.of(
                "apiVersion", "0.0.1",
                "kind", "dsse",
                "spec", Map.of("proposedContent", Map.of(
                        "envelope", envelope,
                        "verifiers", List.of(b64(pem(ledgerKey).getBytes(StandardCharsets.US_ASCII)))))));
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve("api/v1/log/entries"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)));
        if (response.statusCode() == 409) {
            // Already logged (a retry after a lost response): Rekor points at the existing entry.
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("Rekor said the entry exists but not where"));
            return entry(location.substring(location.lastIndexOf('/') + 1));
        }
        if (response.statusCode() != 201) {
            throw new IOException("Rekor refused the entry: HTTP " + response.statusCode() + " " + response.body());
        }
        return parse(json.readTree(response.body()));
    }

    public Entry entry(String uuid) throws IOException {
        if (!uuid.matches("[0-9a-f]{64,80}")) {
            throw new IOException("Not a Rekor entry UUID: " + uuid);
        }
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve("api/v1/log/entries/" + uuid)).GET());
        if (response.statusCode() != 200) {
            throw new IOException("Rekor entry " + uuid + ": HTTP " + response.statusCode());
        }
        return parse(json.readTree(response.body()));
    }

    /** UUIDs of the entries indexed under this SHA-256 (a payload hash, or {@link #keyIndexHash}). */
    public List<String> search(byte[] sha256) throws IOException {
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve("api/v1/index/retrieve"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("hash", "sha256:" + HEX.formatHex(sha256))))));
        if (response.statusCode() != 200) {
            throw new IOException("Rekor search: HTTP " + response.statusCode());
        }
        List<String> uuids = new ArrayList<>();
        json.readTree(response.body()).forEach(n -> uuids.add(n.asText()));
        return uuids;
    }

    /** The key the log signs its tree heads with. */
    public PublicKey publicKey() throws IOException {
        // As PEM: asked for JSON, Rekor answers with the PEM as a quoted JSON string.
        HttpResponse<String> response = send(HttpRequest.newBuilder(base.resolve("api/v1/log/publicKey")).GET(),
                "application/x-pem-file");
        if (response.statusCode() != 200) {
            throw new IOException("Rekor public key: HTTP " + response.statusCode());
        }
        return ecPublicKey(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException {
        return send(request, "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String accept) throws IOException {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).header("Accept", accept).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    static Entry parse(JsonNode response) throws IOException {
        var fields = response.fields();
        if (!fields.hasNext()) {
            throw new IOException("Empty Rekor response");
        }
        var first = fields.next();
        JsonNode e = first.getValue();
        JsonNode p = e.path("verification").path("inclusionProof");
        List<byte[]> hashes = new ArrayList<>();
        p.path("hashes").forEach(h -> hashes.add(HEX.parseHex(h.asText())));
        InclusionProof proof = p.isMissingNode() ? null : new InclusionProof(p.path("logIndex").asLong(),
                p.path("treeSize").asLong(), HEX.parseHex(p.path("rootHash").asText()), hashes,
                p.path("checkpoint").asText());
        return new Entry(first.getKey(), e.path("logIndex").asLong(), e.path("integratedTime").asLong(),
                Base64.getDecoder().decode(e.path("body").asText()), proof);
    }

    /** The one form the ledger key is submitted in: PEM of its SubjectPublicKeyInfo, one base64 line. */
    public static String pem(byte[] ed25519Key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(Ed25519.decodePublicKey(ed25519Key).getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** What Rekor indexes a key's entries under: the SHA-256 of {@link #pem}. */
    public static byte[] keyIndexHash(byte[] ed25519Key) {
        return sha256(pem(ed25519Key).getBytes(StandardCharsets.US_ASCII));
    }

    /** DSSE's pre-authentication encoding: what the ledger key signs. */
    public static byte[] pae(String type, byte[] payload) {
        byte[] header = ("DSSEv1 " + type.getBytes(StandardCharsets.UTF_8).length + " " + type + " " + payload.length
                + " ").getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, out, header.length, payload.length);
        return out;
    }

    /**
     * The payload hash an entry commits to, if it is a DSSE entry whose verifier is {@code ledgerKey}
     * in canonical form; null otherwise. Rekor checked the signature when it logged the entry.
     */
    public static byte[] payloadHashUnder(Entry e, byte[] ledgerKey, ObjectMapper json) throws IOException {
        JsonNode body = json.readTree(e.body());
        if (!"dsse".equals(body.path("kind").asText())) {
            return null;
        }
        String verifier = b64(pem(ledgerKey).getBytes(StandardCharsets.US_ASCII));
        for (JsonNode s : body.path("spec").path("signatures")) {
            if (verifier.equals(s.path("verifier").asText())) {
                return HEX.parseHex(body.path("spec").path("payloadHash").path("value").asText());
            }
        }
        return null;
    }

    /**
     * Checks, without trusting the log's answers, that {@code e} logs {@code payload} signed by
     * {@code ledgerKey}, and that it is in the log whose tree head {@code logKey} signed.
     */
    public static void verify(Entry e, PublicKey logKey, byte[] payload, byte[] ledgerKey, ObjectMapper json)
            throws VerificationException {
        byte[] leaf = MerkleTree.leafHash(e.body());
        if (!e.uuid().endsWith(HEX.formatHex(leaf))) {
            throw new VerificationException("entry " + e.uuid() + " doesn't hash to its UUID");
        }
        JsonNode body;
        try {
            body = json.readTree(e.body());
        } catch (IOException ex) {
            throw new VerificationException("entry " + e.uuid() + " is not JSON");
        }
        JsonNode spec = body.path("spec");
        if (!"dsse".equals(body.path("kind").asText()) || !"0.0.1".equals(body.path("apiVersion").asText())
                || !"sha256".equals(spec.path("payloadHash").path("algorithm").asText())
                || !HEX.formatHex(sha256(payload)).equals(spec.path("payloadHash").path("value").asText())) {
            throw new VerificationException("entry " + e.uuid() + " doesn't log this checkpoint");
        }
        String verifier = b64(pem(ledgerKey).getBytes(StandardCharsets.US_ASCII));
        byte[] pae = pae(PAYLOAD_TYPE, payload);
        boolean signed = false;
        for (JsonNode s : spec.path("signatures")) {
            signed |= verifier.equals(s.path("verifier").asText())
                    && Ed25519.verify(ledgerKey, pae, Base64.getDecoder().decode(s.path("signature").asText()));
        }
        if (!signed) {
            throw new VerificationException("entry " + e.uuid() + " is not signed by the ledger key");
        }
        InclusionProof p = e.proof();
        if (p == null || !MerkleTree.verifyInclusion(p.logIndex(), p.treeSize(), leaf, p.hashes(), p.rootHash())) {
            throw new VerificationException("entry " + e.uuid() + " has no valid inclusion proof");
        }
        verifyTreeHead(p.checkpoint(), logKey, p.treeSize(), p.rootHash());
    }

    /**
     * Checks a signed note (the log's tree head, "origin\nsize\nbase64(root)\n\n— name sig"): the
     * log key signed it, and it is the tree the proof leads to.
     */
    static void verifyTreeHead(String note, PublicKey logKey, long size, byte[] root) throws VerificationException {
        int split = note.indexOf("\n\n");
        if (split < 0) {
            throw new VerificationException("the log's tree head is malformed");
        }
        String text = note.substring(0, split + 1);
        String[] lines = text.split("\n");
        if (lines.length < 3 || !lines[1].equals(Long.toString(size))
                || !Arrays.equals(Base64.getDecoder().decode(lines[2]), root)) {
            throw new VerificationException("the log's tree head is not the tree the proof leads to");
        }
        for (String line : note.substring(split + 2).split("\n")) {
            if (!line.startsWith("— ")) {
                continue;
            }
            byte[] sig = Base64.getDecoder().decode(line.substring(line.lastIndexOf(' ') + 1));
            if (sig.length > 4 && ecdsaVerifies(logKey, text.getBytes(StandardCharsets.UTF_8),
                    Arrays.copyOfRange(sig, 4, sig.length))) {
                return;
            }
        }
        throw new VerificationException("the log's tree head is not signed by the log's key");
    }

    private static boolean ecdsaVerifies(PublicKey key, byte[] message, byte[] derSignature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(derSignature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static PublicKey ecPublicKey(String pem) throws IOException {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Not an EC public key", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
