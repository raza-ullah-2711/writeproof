package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BiometricCipherTest {

    private static BiometricCipher cipher(String keyMaterial) {
        String key = Base64.getEncoder().encodeToString(keyMaterial.getBytes(StandardCharsets.UTF_8));
        return new BiometricCipher(new HandwritingProperties(0.5, false, key));
    }

    private final BiometricCipher cipher = cipher("0123456789abcdef0123456789abcdef");
    private final UUID alice = UUID.randomUUID();
    private final String samples = "[{\"format\":\"writeproof.handwriting\",\"strokes\":[]}]";

    @Test
    void roundTripsAndHidesThePlaintext() {
        byte[] stored = cipher.encrypt(samples, BiometricCipher.enrolmentContext(alice));

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("writeproof.handwriting");
        assertThat(cipher.decrypt(stored, BiometricCipher.enrolmentContext(alice))).isEqualTo(samples);
    }

    @Test
    void usesAFreshNonceEachTime() {
        assertThat(cipher.encrypt(samples, "x")).isNotEqualTo(cipher.encrypt(samples, "x"));
    }

    @Test
    void ciphertextMovedToAnotherAccountOrTableDoesNotDecrypt() {
        byte[] stored = cipher.encrypt(samples, BiometricCipher.enrolmentContext(alice));

        assertThatThrownBy(() -> cipher.decrypt(stored, BiometricCipher.enrolmentContext(UUID.randomUUID())))
                .hasMessageContaining("could not be decrypted");
        assertThatThrownBy(() -> cipher.decrypt(stored, BiometricCipher.historyContext(alice)))
                .hasMessageContaining("could not be decrypted");
    }

    @Test
    void tamperingOrAnotherKeyIsDetected() {
        byte[] stored = cipher.encrypt(samples, "ctx");
        byte[] tampered = stored.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(tampered, "ctx")).hasMessageContaining("could not be decrypted");
        assertThatThrownBy(() -> cipher("ffffffffffffffffffffffffffffffff").decrypt(stored, "ctx"))
                .hasMessageContaining("could not be decrypted");
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {9, 9, 9}, "ctx")).hasMessageContaining("unknown format");
    }

    @Test
    void requiresA256BitKey() {
        assertThatThrownBy(() -> cipher("too short")).hasMessageContaining("32 bytes");
    }
}
