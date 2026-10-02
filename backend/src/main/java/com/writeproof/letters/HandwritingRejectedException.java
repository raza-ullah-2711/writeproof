package com.writeproof.letters;

import com.writeproof.handwriting.HandwritingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The handwriting that was meant to seal a letter didn't verify; carries the measurements. */
public class HandwritingRejectedException extends ResponseStatusException {

    public final transient HandwritingService.Verification verification;

    HandwritingRejectedException(HandwritingService.Verification verification) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, verification.match()
                ? "Your signature didn't pass the liveness checks"
                : "Your signature didn't match your enrolled handwriting");
        this.verification = verification;
    }
}
