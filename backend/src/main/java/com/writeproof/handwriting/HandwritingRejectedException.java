package com.writeproof.handwriting;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A signature that had to verify (to seal a letter, or to delete an enrolment) didn't. */
public class HandwritingRejectedException extends ResponseStatusException {

    public final transient HandwritingService.Verification verification;

    public HandwritingRejectedException(HandwritingService.Verification verification) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, verification.match()
                ? "Your signature didn't pass the liveness checks"
                : "Your signature didn't match your enrolled handwriting");
        this.verification = verification;
    }
}
