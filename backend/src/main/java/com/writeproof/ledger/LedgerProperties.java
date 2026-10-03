package com.writeproof.ledger;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param signingKey         base64 32-byte Ed25519 seed for the ledger key that signs checkpoints
 *                           ({@code LEDGER_SIGNING_KEY}). Its public half is what clients pin.
 * @param checkpointInterval how often a new checkpoint is stored and published (if the ledger grew)
 * @param checkpointLog      optional file that published checkpoints are appended to, one JSON per
 *                           line, for shipping to outside witnesses
 * @param previousSigningKey optional seed of the key being rotated away from
 *                           ({@code LEDGER_PREVIOUS_SIGNING_KEY}); see {@link KeyRotationService}
 */
@Validated
@ConfigurationProperties("writeproof.ledger")
public record LedgerProperties(@NotBlank String signingKey, Duration checkpointInterval, String checkpointLog,
                               String previousSigningKey) {}
