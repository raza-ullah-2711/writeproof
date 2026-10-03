package com.writeproof.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The exact bytes a wallet signs to delete its account (frontend {@code deletion-message.ts}). The
 * domain prefix keeps the signature from being valid as any other kind of signature, and the
 * timestamp keeps an old request from being replayed later.
 */
public final class DeletionMessage {

    public static final String DOMAIN = "writeproof/delete-account/v1";

    private DeletionMessage() {}

    /** @param requestedAt ISO-8601 UTC, exactly as the client sent it */
    public static byte[] of(UUID accountId, String requestedAt) {
        return (DOMAIN + "\n" + accountId + "\n" + requestedAt).getBytes(StandardCharsets.UTF_8);
    }
}
