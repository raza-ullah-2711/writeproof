package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.common.Base64Url;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyRotationTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] ROOT = HEX.parseHex("3560191803028444b232018ac047fdb561c09c23a7a6876c85e08b5e4d48e9f3");

    /** RFC 8032 §7.1 test 1 seed, as in LedgerSignerTest. */
    private static final LedgerSigner RFC_1 = signer(HEX.parseHex(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));
    /** RFC 8032 §7.1 test 2 public key. */
    private static final byte[] RFC_2_PUBLIC = HEX.parseHex(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");

    private static final LedgerSigner A = signer(filled(1));
    private static final LedgerSigner B = signer(filled(2));
    private static final LedgerSigner C = signer(filled(3));

    private static byte[] filled(int b) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) b);
        return seed;
    }

    private static LedgerSigner signer(byte[] seed) {
        return new LedgerSigner(Base64.getEncoder().encodeToString(seed), "test key");
    }

    private static KeyRotation rotate(LedgerSigner from, byte[] to, long size) {
        byte[] message = KeyRotation.signedMessage(from.publicKey(), to, size, ROOT, 1767225600000L);
        return new KeyRotation(from.publicKey(), to, size, ROOT, 1767225600000L, from.sign(message));
    }

    /** Shared with frontend key-rotation.spec.ts: Ed25519 is deterministic, so this signature is fixed. */
    @Test
    void signsTheRotationVector() {
        KeyRotation rotation = rotate(RFC_1, RFC_2_PUBLIC, 7);

        assertThat(new String(rotation.signedMessage())).isEqualTo("""
                writeproof/key-rotation/v1
                11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo
                PUAXw-hDiVqStwqnTRt-vJyYLM8uxJaMwM1V8Sr0Zgw
                7
                NWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM
                1767225600000""");
        assertThat(rotation.verify()).isTrue();
        assertThat(Base64Url.encode(rotation.signature())).isEqualTo(
                "KmP5kUVS-0UV-Nf0O1SANHOhJlXW_DUxkD4WHxPAwZ02jN9KMoDCdn_DRc83uNPx5iNbiuzqbV_ppbGYBFeADA");
    }

    @Test
    void rejectsAnyChangeToWhatWasSigned() {
        KeyRotation r = rotate(A, B.publicKey(), 5);

        assertThat(new KeyRotation(r.oldKey(), C.publicKey(), 5, ROOT, r.timestampMillis(), r.signature()).verify())
                .isFalse();
        assertThat(new KeyRotation(r.oldKey(), r.newKey(), 6, ROOT, r.timestampMillis(), r.signature()).verify())
                .isFalse();
        assertThat(new KeyRotation(r.oldKey(), r.newKey(), 5, new byte[32], r.timestampMillis(), r.signature())
                .verify()).isFalse();
        // Signed by the new key instead of the old one.
        byte[] bySelf = B.sign(r.signedMessage());
        assertThat(new KeyRotation(r.oldKey(), r.newKey(), 5, ROOT, r.timestampMillis(), bySelf).verify()).isFalse();
    }

    @Test
    void followsAChainOfRotationsFromAnyKeyInIt() throws Exception {
        KeyRotation ab = rotate(A, B.publicKey(), 5);
        KeyRotation bc = rotate(B, C.publicKey(), 9);
        List<KeyRotation> all = List.of(ab, bc);

        assertThat(KeyRotation.follow(A.publicKey(), C.publicKey(), all)).containsExactly(ab, bc);
        assertThat(KeyRotation.follow(B.publicKey(), C.publicKey(), all)).containsExactly(bc);
        assertThat(KeyRotation.follow(C.publicKey(), C.publicKey(), all)).isEmpty();
        assertThat(KeyRotation.follow(A.publicKey(), A.publicKey(), List.of())).isEmpty();
    }

    @Test
    void refusesAKeyChangeNoValidRotationExplains() {
        KeyRotation ab = rotate(A, B.publicKey(), 5);
        KeyRotation bc = rotate(B, C.publicKey(), 9);

        // No rotations at all, or none starting from the pinned key.
        assertThatThrownBy(() -> KeyRotation.follow(A.publicKey(), B.publicKey(), List.of()))
                .isInstanceOf(KeyRotation.BrokenChainException.class).hasMessageContaining("ledger key changed");
        assertThatThrownBy(() -> KeyRotation.follow(C.publicKey(), B.publicKey(), List.of(ab)))
                .hasMessageContaining("no rotation signed by the old key");
        // A forged link: "B -> C" signed by C itself.
        KeyRotation forged = new KeyRotation(B.publicKey(), C.publicKey(), 9, ROOT, bc.timestampMillis(),
                C.sign(bc.signedMessage()));
        assertThatThrownBy(() -> KeyRotation.follow(A.publicKey(), C.publicKey(), List.of(ab, forged)))
                .hasMessageContaining("not signed by the key before it");
        // A gap in the chain, a chain that ends elsewhere, and one that goes back in size.
        assertThatThrownBy(() -> KeyRotation.follow(A.publicKey(), C.publicKey(), List.of(ab, rotate(A, C.publicKey(), 9))))
                .hasMessageContaining("not signed by the key before it");
        assertThatThrownBy(() -> KeyRotation.follow(A.publicKey(), C.publicKey(), List.of(ab)))
                .hasMessageContaining("rotations end at");
        assertThatThrownBy(() -> KeyRotation.follow(A.publicKey(), C.publicKey(), List.of(ab, rotate(B, C.publicKey(), 4))))
                .hasMessageContaining("starts before");
    }
}
