package com.writeproof.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * @param publicKey              Ed25519 identity key: the account itself
 * @param encryptionKey          X25519 key for sealed letters, or null if not yet registered
 * @param encryptionKeySignature identity-key signature over {@link EncryptionKeyBinding}
 * @param deletedAt              when the account was deleted, or null (docs/launch-policies.md)
 */
public record Account(
        UUID id, byte[] publicKey, Instant createdAt, byte[] encryptionKey, byte[] encryptionKeySignature,
        Instant deletedAt) {

    public boolean canReceiveLetters() {
        return encryptionKey != null && !deleted();
    }

    public boolean deleted() {
        return deletedAt != null;
    }
}
