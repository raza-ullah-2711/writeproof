package com.writeproof.auth;

import com.writeproof.identity.Account;
import com.writeproof.identity.AccountRepository;
import com.writeproof.identity.Ed25519;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Challenge-response login: the server issues a nonce, the wallet signs it, the server verifies. */
@Service
class ChallengeService {

    static final int NONCE_LENGTH = 32;

    record IssuedChallenge(UUID challengeId, byte[] nonce, Instant expiresAt) {}

    private final AccountRepository accounts;
    private final ChallengeRepository challenges;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ChallengeService(
            AccountRepository accounts,
            ChallengeRepository challenges,
            TokenService tokens,
            AuthProperties properties,
            Clock clock) {
        this.accounts = accounts;
        this.challenges = challenges;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    IssuedChallenge issue(byte[] publicKey) {
        Account account = accounts.findByPublicKey(publicKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account for this public key"));
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(properties.challengeTtl());
        UUID id = UUID.randomUUID();
        challenges.insert(id, account.id(), nonce, now, expiresAt);
        return new IssuedChallenge(id, nonce, expiresAt);
    }

    TokenService.IssuedToken verify(UUID challengeId, byte[] signature) {
        ChallengeRepository.ConsumedChallenge challenge = challenges.consume(challengeId, clock.instant())
                .orElseThrow(() -> unauthorized("Challenge is unknown, expired or already used"));
        byte[] message = LoginMessage.of(challengeId, challenge.nonce());
        if (!Ed25519.verify(challenge.publicKey(), message, signature)) {
            throw unauthorized("Signature does not verify");
        }
        return tokens.issue(challenge.accountId());
    }

    @Scheduled(fixedDelayString = "PT10M")
    void purgeExpired() {
        challenges.deleteExpiredBefore(clock.instant().minus(Duration.ofMinutes(10)));
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
