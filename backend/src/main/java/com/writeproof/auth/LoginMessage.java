package com.writeproof.auth;

import com.writeproof.common.Base64Url;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The exact bytes a wallet signs to log in. The client builds the same bytes itself
 * (frontend {@code login-message.ts}); the server never hands the client opaque bytes to sign.
 * The domain prefix keeps a login signature from ever being valid as any other kind of
 * signature (e.g. a letter signature).
 */
public final class LoginMessage {

    public static final String DOMAIN = "writeproof/login/v1";

    private LoginMessage() {}

    public static byte[] of(UUID challengeId, byte[] nonce) {
        String message = DOMAIN + "\n" + challengeId + "\n" + Base64Url.encode(nonce);
        return message.getBytes(StandardCharsets.UTF_8);
    }
}
