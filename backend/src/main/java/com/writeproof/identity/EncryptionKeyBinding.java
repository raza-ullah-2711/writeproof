package com.writeproof.identity;

import com.writeproof.common.Base64Url;
import java.nio.charset.StandardCharsets;

/**
 * The bytes an identity key signs to vouch for its encryption key. Must match the frontend
 * {@code encryptionKeyBinding()}; pinned by a shared test vector.
 */
public final class EncryptionKeyBinding {

    public static final String DOMAIN = "writeproof/encryption-key/v1";

    private EncryptionKeyBinding() {}

    public static byte[] of(byte[] identityKey, byte[] encryptionKey) {
        return (DOMAIN + "\n" + Base64Url.encode(identityKey) + "\n" + Base64Url.encode(encryptionKey))
                .getBytes(StandardCharsets.UTF_8);
    }
}
