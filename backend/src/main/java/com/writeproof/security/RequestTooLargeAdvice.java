package com.writeproof.security;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A chunked body cut off by {@link RequestSizeLimitFilter} surfaces inside Spring MVC, wrapped in
 * {@link HttpMessageNotReadableException} (which would otherwise be a 400), so it never reaches
 * the filter's own 413 handling. This restores the right status.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class RequestTooLargeAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestSizeLimitFilter.TooLargeException) {
                return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large");
            }
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body could not be read");
    }
}
