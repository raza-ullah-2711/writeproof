package com.writeproof.auth;

import com.writeproof.common.Base64Url;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

    record ChallengeRequest(@NotBlank String publicKey) {}

    record ChallengeResponse(UUID challengeId, String nonce, Instant expiresAt) {}

    record VerifyRequest(@NotNull UUID challengeId, @NotBlank String signature) {}

    record TokenResponse(String token, Instant expiresAt) {}

    private final ChallengeService challenges;

    AuthController(ChallengeService challenges) {
        this.challenges = challenges;
    }

    @PostMapping("/challenge")
    ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
        ChallengeService.IssuedChallenge issued = challenges.issue(Base64Url.decode(request.publicKey()));
        return new ChallengeResponse(issued.challengeId(), Base64Url.encode(issued.nonce()), issued.expiresAt());
    }

    @PostMapping("/verify")
    TokenResponse verify(@Valid @RequestBody VerifyRequest request) {
        TokenService.IssuedToken token = challenges.verify(request.challengeId(), Base64Url.decode(request.signature()));
        return new TokenResponse(token.token(), token.expiresAt());
    }
}
