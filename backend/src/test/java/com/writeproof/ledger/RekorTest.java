package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the Rekor client's verification against a real entry from the public log
 * (rekor.sigstore.dev): a DSSE entry logged on 2026-10-03 under a throwaway Ed25519 key, in exactly
 * the form AnchorService submits, and the log's public key at that time.
 */
class RekorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PAYLOAD = ("writeproof/checkpoint/v1\n0\n47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU\n"
            + "1791036000000").getBytes(StandardCharsets.UTF_8);

    private static Rekor.Entry entry;
    private static PublicKey logKey;
    private static byte[] ledgerKey;

    private static String resource(String name) throws Exception {
        try (InputStream in = RekorTest.class.getResourceAsStream("/rekor/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeAll
    static void load() throws Exception {
        entry = Rekor.parse(JSON.readTree(resource("public-entry.json")));
        logKey = Rekor.ecPublicKey(resource("public-key.pem"));
        String verifier = JSON.readTree(entry.body()).path("spec").path("signatures").get(0).path("verifier").asText();
        String pem = new String(Base64.getDecoder().decode(verifier), StandardCharsets.US_ASCII);
        byte[] spki = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
        ledgerKey = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
    }

    @Test
    void verifiesARealEntryFromThePublicLog() throws Exception {
        Rekor.verify(entry, logKey, PAYLOAD, ledgerKey, JSON);

        assertThat(entry.logIndex()).isEqualTo(3_074_954_845L);
        assertThat(Rekor.payloadHashUnder(entry, ledgerKey, JSON)).isEqualTo(Rekor.sha256(PAYLOAD));
    }

    /** The public log indexed the entry under SHA-256 of the key's PEM exactly as we write it. */
    @Test
    void writesTheKeyExactlyAsThePublicLogIndexedIt() {
        assertThat(HexFormat.of().formatHex(Rekor.keyIndexHash(ledgerKey)))
                .isEqualTo("a0e91bed5ffc5af048b3b25efc83004f014038416cec2bf33c489da07e5841f0");
        assertThat(Rekor.pem(ledgerKey)).startsWith("-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA")
                .endsWith("\n-----END PUBLIC KEY-----\n").hasLineCount(3);
    }

    @Test
    void rejectsAnEntryThatDoesntProveItsClaim() throws Exception {
        byte[] other = Arrays.copyOf(PAYLOAD, PAYLOAD.length);
        other[other.length - 1] ^= 1;
        assertThatThrownBy(() -> Rekor.verify(entry, logKey, other, ledgerKey, JSON))
                .hasMessageContaining("doesn't log this checkpoint");

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThatThrownBy(() -> Rekor.verify(entry, logKey, PAYLOAD, Rekor.sha256(otherKey), JSON))
                .isInstanceOf(Exception.class);

        List<byte[]> hashes = new ArrayList<>(entry.proof().hashes());
        hashes.set(0, new byte[32]);
        Rekor.Entry badProof = new Rekor.Entry(entry.uuid(), entry.logIndex(), entry.integratedTime(), entry.body(),
                new Rekor.InclusionProof(entry.proof().logIndex(), entry.proof().treeSize(), entry.proof().rootHash(),
                        hashes, entry.proof().checkpoint()));
        assertThatThrownBy(() -> Rekor.verify(badProof, logKey, PAYLOAD, ledgerKey, JSON))
                .hasMessageContaining("no valid inclusion proof");

        PublicKey someoneElse = Rekor.ecPublicKey(resource("public-key.pem").replace("E2G2Y", "E3G2Y"));
        assertThatThrownBy(() -> Rekor.verify(entry, someoneElse, PAYLOAD, ledgerKey, JSON))
                .hasMessageContaining("not signed by the log's key");
    }
}
