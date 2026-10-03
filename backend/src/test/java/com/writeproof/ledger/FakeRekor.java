package com.writeproof.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.writeproof.identity.Ed25519;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Rekor v1 log for tests, following what the public instance does (see Rekor): it checks DSSE
 * signatures, keeps the canonical entry body, indexes entries by payload hash and by the hash of the
 * verifier key, and answers with real RFC 6962 inclusion proofs and a tree head it signs with its
 * own ECDSA P-256 key.
 */
final class FakeRekor implements AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();
    private static final String TREE_ID = "00000000000004d2";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final KeyPair key;
    private final List<byte[]> bodies = new ArrayList<>();
    private final List<byte[]> leaves = new ArrayList<>();
    private final Map<String, Integer> byUuid = new HashMap<>();
    private final Map<String, List<String>> index = new HashMap<>();

    FakeRekor() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        key = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (path.equals("/api/v1/log/publicKey")) {
                respond(exchange, 200, "-----BEGIN PUBLIC KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getPublic().getEncoded())
                        + "\n-----END PUBLIC KEY-----\n");
            } else if (path.equals("/api/v1/log/entries") && exchange.getRequestMethod().equals("POST")) {
                submit(exchange, json.readTree(request));
            } else if (path.startsWith("/api/v1/log/entries/")) {
                Integer i = byUuid.get(path.substring(path.lastIndexOf('/') + 1));
                if (i == null) {
                    respond(exchange, 404, "{}");
                } else {
                    respond(exchange, 200, entry(i));
                }
            } else if (path.equals("/api/v1/index/retrieve")) {
                String hash = json.readTree(request).path("hash").asText().replace("sha256:", "");
                respond(exchange, 200, json.writeValueAsString(index.getOrDefault(hash, List.of())));
            } else {
                respond(exchange, 404, "{}");
            }
        } catch (Exception e) {
            respond(exchange, 500, "{\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    private void submit(HttpExchange exchange, JsonNode request) throws Exception {
        JsonNode proposed = request.path("spec").path("proposedContent");
        String envelopeText = proposed.path("envelope").asText();
        JsonNode envelope = json.readTree(envelopeText);
        byte[] payload = Base64.getDecoder().decode(envelope.path("payload").asText());
        String signature = envelope.path("signatures").get(0).path("sig").asText();
        String verifier = proposed.path("verifiers").get(0).asText();
        String pem = new String(Base64.getDecoder().decode(verifier), StandardCharsets.US_ASCII);
        byte[] spki = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        byte[] rawKey = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
        if (!Ed25519.verify(rawKey, Rekor.pae(envelope.path("payloadType").asText(), payload),
                Base64.getDecoder().decode(signature))) {
            respond(exchange, 400, "{\"message\":\"signature does not verify\"}");
            return;
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("envelopeHash", Map.of("algorithm", "sha256",
                "value", HEX.formatHex(Rekor.sha256(envelopeText.getBytes(StandardCharsets.UTF_8)))));
        spec.put("payloadHash", Map.of("algorithm", "sha256", "value", HEX.formatHex(Rekor.sha256(payload))));
        spec.put("signatures", List.of(Map.of("signature", signature, "verifier", verifier)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", "0.0.1");
        body.put("kind", "dsse");
        body.put("spec", spec);
        byte[] bodyBytes = json.writeValueAsBytes(body);
        String uuid = TREE_ID + HEX.formatHex(MerkleTree.leafHash(bodyBytes));
        if (byUuid.containsKey(uuid)) {
            exchange.getResponseHeaders().add("Location", "/api/v1/log/entries/" + uuid);
            respond(exchange, 409, "{}");
            return;
        }
        byUuid.put(uuid, bodies.size());
        bodies.add(bodyBytes);
        leaves.add(MerkleTree.leafHash(bodyBytes));
        index.computeIfAbsent(HEX.formatHex(Rekor.sha256(payload)), h -> new ArrayList<>()).add(uuid);
        index.computeIfAbsent(HEX.formatHex(Rekor.sha256(pem.getBytes(StandardCharsets.US_ASCII))),
                h -> new ArrayList<>()).add(uuid);
        respond(exchange, 201, entry(bodies.size() - 1));
    }

    private String entry(int i) throws Exception {
        int size = leaves.size();
        byte[] root = MerkleTree.root(leaves);
        String text = "fake.rekor - " + Long.parseLong(TREE_ID, 16) + "\n" + size + "\n"
                + Base64.getEncoder().encodeToString(root) + "\n";
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(text.getBytes(StandardCharsets.UTF_8));
        byte[] sig = signer.sign();
        byte[] withHint = new byte[4 + sig.length];
        System.arraycopy(sig, 0, withHint, 4, sig.length);
        String note = text + "\n— fake.rekor " + Base64.getEncoder().encodeToString(withHint) + "\n";
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("logIndex", i);
        proof.put("treeSize", size);
        proof.put("rootHash", HEX.formatHex(root));
        proof.put("hashes", MerkleTree.inclusionProof(leaves, i, size).stream().map(HEX::formatHex).toList());
        proof.put("checkpoint", note);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("body", Base64.getEncoder().encodeToString(bodies.get(i)));
        entry.put("integratedTime", 1_767_225_600L + i);
        entry.put("logID", "fake");
        entry.put("logIndex", i);
        entry.put("verification", Map.of("inclusionProof", proof));
        String uuid = TREE_ID + HEX.formatHex(leaves.get(i));
        return json.writeValueAsString(Map.of(uuid, entry));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
