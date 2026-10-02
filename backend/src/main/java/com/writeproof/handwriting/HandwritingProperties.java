package com.writeproof.handwriting;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param threshold    minimum similarity score (0..1) for a verification to match
 * @param exposeScores whether responses include similarity scores. Off by default: a score
 *                     tells a forger how close each attempt got. Liveness flags are always shown.
 * @param dataKey      base64 AES-256 key that encrypts stored handwriting (enrolments and recent
 *                     signatures) at rest, from {@code HANDWRITING_DATA_KEY}
 */
@Validated
@ConfigurationProperties("writeproof.handwriting")
public record HandwritingProperties(
        @DecimalMin("0") @DecimalMax("1") double threshold, boolean exposeScores, @NotBlank String dataKey) {}
