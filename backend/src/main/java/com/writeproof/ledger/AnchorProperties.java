package com.writeproof.ledger;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param rekorUrl the public transparency log checkpoints are anchored in ({@code LEDGER_REKOR_URL},
 *                 e.g. {@link Rekor#PUBLIC_URL}); blank turns anchoring off
 * @param interval how often unanchored published checkpoints are anchored (and failures retried)
 */
@ConfigurationProperties("writeproof.ledger.anchor")
public record AnchorProperties(String rekorUrl, Duration interval) {

    public boolean enabled() {
        return rekorUrl != null && !rekorUrl.isBlank();
    }
}
