package com.writeproof.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.writeproof.common.Base64Url;
import com.writeproof.identity.Ed25519;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class LedgerSignerTest {

    private static final HexFormat HEX = HexFormat.of();

    /** RFC 8032 §7.1, test 1. */
    private static final byte[] RFC_SEED = HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final String RFC_PUBLIC = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String RFC_EMPTY_SIGNATURE = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

    private static LedgerSigner signer(byte[] seed) {
        return new LedgerSigner(new LedgerProperties(Base64.getEncoder().encodeToString(seed), null, null, null));
    }

    @Test
    void derivesTheRfc8032KeyFromItsSeed() {
        LedgerSigner signer = signer(RFC_SEED);

        assertThat(HEX.formatHex(signer.publicKey())).isEqualTo(RFC_PUBLIC);
        assertThat(HEX.formatHex(signer.sign(new byte[0]))).isEqualTo(RFC_EMPTY_SIGNATURE);
    }

    /** Shared with frontend checkpoint.spec.ts: Ed25519 is deterministic, so this signature is fixed. */
    @Test
    void signsTheCheckpointVector() {
        LedgerSigner signer = signer(RFC_SEED);
        byte[] root = HEX.parseHex("3560191803028444b232018ac047fdb561c09c23a7a6876c85e08b5e4d48e9f3");

        byte[] message = Checkpoint.signedMessage(7, root, 1767225600000L);
        byte[] signature = signer.sign(message);

        assertThat(new String(message)).isEqualTo(
                "writeproof/checkpoint/v1\n7\nNWAZGAMChESyMgGKwEf9tWHAnCOnpodsheCLXk1I6fM\n1767225600000");
        assertThat(Ed25519.verify(signer.publicKey(), message, signature)).isTrue();
        assertThat(Base64Url.encode(signature)).isEqualTo(
                "nkGhr4HnxlqyXyEMStJQm7mgedynAioAhUduKiROM9RvD5frpThdsxJXPsIJbZDrwax4imdDwakhhYXpHT4XAw");
    }

    @Test
    void rejectsSeedsOfTheWrongLength() {
        assertThatThrownBy(() -> signer(new byte[31])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> signer(new byte[64])).isInstanceOf(IllegalStateException.class);
    }
}
