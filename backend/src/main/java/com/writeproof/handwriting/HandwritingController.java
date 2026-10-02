package com.writeproof.handwriting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/handwriting")
class HandwritingController {

    record EnrolRequest(
            @NotNull @Size(min = SignatureMatcher.MIN_REFERENCES, max = SignatureMatcher.MAX_REFERENCES)
            List<@NotNull HandwritingSample> samples) {}

    record EnrolmentResponse(boolean enrolled, Integer sampleCount, Instant enrolledAt) {}

    record VerifyRequest(@NotNull HandwritingSample sample) {}

    /** Scores are null unless {@code writeproof.handwriting.expose-scores} is on. */
    record VerifyResponse(
            boolean verified,
            Double score,
            Double threshold,
            boolean match,
            boolean live,
            Set<LivenessFlag> livenessFlags,
            Double shapeScore,
            Double durationScore) {}

    record DeletionRequest(@NotNull HandwritingSample sample) {}

    private final HandwritingService handwriting;
    private final HandwritingProperties properties;

    HandwritingController(HandwritingService handwriting, HandwritingProperties properties) {
        this.handwriting = handwriting;
        this.properties = properties;
    }

    @PostMapping("/enrolment")
    ResponseEntity<EnrolmentResponse> enrol(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EnrolRequest request) {
        HandwritingService.Enrolled enrolled = handwriting.enrol(accountId(jwt), request.samples());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EnrolmentResponse(true, enrolled.sampleCount(), enrolled.enrolledAt()));
    }

    @GetMapping("/enrolment")
    EnrolmentResponse enrolment(@AuthenticationPrincipal Jwt jwt) {
        return handwriting.enrolment(accountId(jwt))
                .map(e -> new EnrolmentResponse(true, e.sampleCount(), e.enrolledAt()))
                .orElse(new EnrolmentResponse(false, null, null));
    }

    @PostMapping("/verify")
    VerifyResponse verify(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyRequest request) {
        HandwritingService.Verification v = handwriting.verify(accountId(jwt), request.sample());
        boolean expose = properties.exposeScores();
        return new VerifyResponse(v.verified(), expose ? round(v.score()) : null, expose ? v.threshold() : null,
                v.match(), v.live(), v.livenessFlags(), expose ? round(v.details().shapeScore()) : null,
                expose ? round(v.details().durationScore()) : null);
    }

    /** Deletes the enrolment (and recent signatures). Proving it's you takes a fresh signature. */
    @PostMapping("/enrolment/deletion")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeletionRequest request) {
        handwriting.deleteEnrolment(accountId(jwt), request.sample());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(HandwritingService.NotLiveException.class)
    ProblemDetail notLive(HandwritingService.NotLiveException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getReason());
        problem.setProperty("sampleIndex", e.sampleIndex);
        problem.setProperty("livenessFlags", e.flags);
        return problem;
    }

    private static UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
