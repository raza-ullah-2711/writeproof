package com.writeproof.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps request bodies. The largest legitimate body is an enrolment of 5 samples × 5,000 points,
 * comfortably under 4 MiB. Declared lengths are refused up front; undeclared (chunked) bodies are
 * cut off while being read.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 4L * 1024 * 1024;

    /** Thrown while reading past the limit; mapped to 413. */
    public static class TooLargeException extends IOException {
        TooLargeException() {
            super("Request body exceeds " + MAX_BYTES + " bytes");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Request body is too large");
            return;
        }
        try {
            chain.doFilter(new Limited(request), response);
        } catch (Exception e) {
            if (causedByTooLarge(e) && !response.isCommitted()) {
                response.reset();
                response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(), "Request body is too large");
                return;
            }
            throw e;
        }
    }

    private static boolean causedByTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TooLargeException) {
                return true;
            }
        }
        return false;
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0) {
                            count(1);
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int off, int len) throws IOException {
                        int n = in.read(buffer, off, len);
                        if (n > 0) {
                            count(n);
                        }
                        return n;
                    }

                    private void count(long n) throws TooLargeException {
                        read += n;
                        if (read > MAX_BYTES) {
                            throw new TooLargeException();
                        }
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
