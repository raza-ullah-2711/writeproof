package com.writeproof.calibration;

import com.writeproof.handwriting.HandwritingSample;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Opt-in calibration contributions. Nothing here touches the contributor's real signature or
 * enrolment: they write an assigned practice name, and imitate other contributors' practice names.
 */
@RestController
@RequestMapping("/api/calibration")
class CalibrationController {

    /** A target needs this many genuine samples before others are asked to imitate it. */
    static final int MIN_TARGET_SAMPLES = 3;

    enum Kind { genuine, forgery }

    record Status(boolean contributing, String practiceName, int genuineSamples, int forgerySamples) {}

    record SampleRequest(@NotNull Kind kind, UUID targetId, @NotNull @Valid HandwritingSample sample) {}

    /** {@code targetId} is the target's pseudonymous contributor id; no account is revealed. */
    record ForgeryTarget(UUID targetId, String practiceName, HandwritingSample sample) {}

    private final CalibrationRepository calibration;
    private final Clock clock;

    CalibrationController(CalibrationRepository calibration, Clock clock) {
        this.calibration = calibration;
        this.clock = clock;
    }

    @GetMapping
    Status status(@AuthenticationPrincipal Jwt jwt) {
        return calibration.findByAccount(account(jwt))
                .map(c -> {
                    CalibrationRepository.Counts counts = calibration.counts(c.contributorId());
                    return new Status(true, c.practiceName(), counts.genuine(), counts.forgeries());
                })
                .orElse(new Status(false, null, 0, 0));
    }

    /** Opting in assigns a pseudonymous contributor id and a practice name. Idempotent. */
    @PostMapping("/consent")
    @Transactional
    Status consent(@AuthenticationPrincipal Jwt jwt) {
        UUID account = account(jwt);
        if (calibration.findByAccount(account).isEmpty()) {
            calibration.insertContributor(new CalibrationRepository.Contributor(
                    UUID.randomUUID(), account, PracticeNames.next(), now()));
        }
        return status(jwt);
    }

    /** Withdrawing deletes every sample this contributor gave, and every imitation of them. */
    @DeleteMapping
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt) {
        calibration.findByAccount(account(jwt)).ifPresent(c -> calibration.deleteContributor(c.contributorId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/samples")
    Status contribute(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SampleRequest request) {
        CalibrationRepository.Contributor me = contributor(jwt);
        HandwritingSample sample = request.sample().validate();
        UUID target = null;
        if (request.kind() == Kind.forgery) {
            if (request.targetId() == null || request.targetId().equals(me.contributorId())
                    || calibration.practiceName(request.targetId()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown imitation target");
            }
            target = request.targetId();
        } else if (request.targetId() != null) {
            throw new IllegalArgumentException("Genuine samples have no target");
        }
        calibration.insertSample(me.contributorId(), request.kind().name(), target, sample, now());
        return status(jwt);
    }

    @GetMapping("/forgery-target")
    ForgeryTarget forgeryTarget(@AuthenticationPrincipal Jwt jwt) {
        CalibrationRepository.Contributor me = contributor(jwt);
        UUID target = calibration.randomTarget(me.contributorId(), MIN_TARGET_SAMPLES)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No practice signatures to imitate yet"));
        return new ForgeryTarget(target, calibration.practiceName(target).orElseThrow(),
                calibration.randomGenuine(target).orElseThrow());
    }

    private CalibrationRepository.Contributor contributor(Jwt jwt) {
        return calibration.findByAccount(account(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Opt in to calibration first"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static UUID account(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
