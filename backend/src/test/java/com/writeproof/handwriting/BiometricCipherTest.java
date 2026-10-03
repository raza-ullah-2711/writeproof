package com.writeproof.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BiometricCipherTest {

    private static String key(String keyMaterial) {
        return Base64.getEncoder().encodeToString(keyMaterial.getBytes(StandardCharsets.UTF_8));
    }

    private static BiometricCipher cipher(String keyMaterial) {
        return new BiometricCipher(new HandwritingProperties(0.5, false, key(keyMaterial), null));
    }

    /** A cipher with a new key that still reads data under the given previous keys. */
    private static BiometricCipher rotated(String keyMaterial, String... previous) {
        String previousKeys = String.join(",", java.util.Arrays.stream(previous).map(BiometricCipherTest::key).toList());
        return new BiometricCipher(new HandwritingProperties(0.5, false, key(keyMaterial), previousKeys));
    }

    /** The format written before keys had ids: 0x01 || nonce || ciphertext+tag. */
    private static byte[] legacy(String keyMaterial, String plaintext, String context) throws Exception {
        byte[] nonce = new byte[12];
        new java.security.SecureRandom().nextBytes(nonce);
        javax.crypto.Cipher aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(javax.crypto.Cipher.ENCRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(keyMaterial.getBytes(StandardCharsets.UTF_8), "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, nonce));
        aes.updateAAD(("writeproof/biometric/v1\n" + context).getBytes(StandardCharsets.UTF_8));
        byte[] ct = aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return java.nio.ByteBuffer.allocate(1 + 12 + ct.length).put((byte) 1).put(nonce).put(ct).array();
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
                .hasMessageContaining("under a key that isn't configured");
        assertThatThrownBy(() -> cipher.decrypt(new byte[] {9, 9, 9}, "ctx")).hasMessageContaining("unknown format");
    }

    @Test
    void readsDataUnderAPreviousKeyAndWritesUnderTheNewOne() throws Exception {
        String old = "0123456789abcdef0123456789abcdef";
        byte[] underOld = cipher.encrypt(samples, "ctx");
        byte[] legacyUnderOld = legacy(old, samples, "ctx");
        BiometricCipher next = rotated("ffffffffffffffffffffffffffffffff", old);

        assertThat(next.decrypt(underOld, "ctx")).isEqualTo(samples);
        assertThat(next.decrypt(legacyUnderOld, "ctx")).isEqualTo(samples);
        assertThat(cipher.decrypt(legacyUnderOld, "ctx")).isEqualTo(samples);
        assertThat(next.isCurrent(underOld)).isFalse();
        assertThat(next.isCurrent(legacyUnderOld)).isFalse();
        byte[] rewritten = next.encrypt(next.decrypt(underOld, "ctx"), "ctx");
        assertThat(next.isCurrent(rewritten)).isTrue();
        // Once the old key is dropped, only the rewritten value still opens.
        BiometricCipher done = cipher("ffffffffffffffffffffffffffffffff");
        assertThat(done.decrypt(rewritten, "ctx")).isEqualTo(samples);
        assertThatThrownBy(() -> done.decrypt(legacyUnderOld, "ctx")).hasMessageContaining("any configured key");
    }

    @Test
    void requiresA256BitKey() {
        assertThatThrownBy(() -> cipher("too short")).hasMessageContaining("32 bytes");
    }
}
