package com.writeproof.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginMessageTest {

    /** Must match the vector in frontend/src/app/auth/login-message.spec.ts. */
    @Test
    void encodesTheSharedTestVector() {
        UUID challengeId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        byte[] nonce = new byte[32];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) i;
        }

        String message = new String(LoginMessage.of(challengeId, nonce), StandardCharsets.UTF_8);

        assertThat(message).isEqualTo(
                "writeproof/login/v1\n00000000-0000-4000-8000-000000000001\nAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8");
    }
}
