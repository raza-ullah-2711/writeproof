package com.writeproof.handwriting;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ordered first: Spring's problem-details handler also handles {@code ResponseStatusException}
 * (our parent type) and would otherwise answer without the liveness flags.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class HandwritingExceptionHandler {

    private final HandwritingProperties properties;

    HandwritingExceptionHandler(HandwritingProperties properties) {
        this.properties = properties;
    }

    /** Liveness flags help a genuine writer; the score would help a forger, so it's opt-in. */
    @ExceptionHandler(HandwritingRejectedException.class)
    ProblemDetail rejected(HandwritingRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getReason());
        problem.setProperty("match", e.verification.match());
        problem.setProperty("livenessFlags", e.verification.livenessFlags());
        if (properties.exposeScores()) {
            problem.setProperty("score", Math.round(e.verification.score() * 1000) / 1000.0);
            problem.setProperty("threshold", e.verification.threshold());
        }
        return problem;
    }
}
