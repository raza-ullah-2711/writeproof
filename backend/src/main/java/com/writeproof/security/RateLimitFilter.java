package com.writeproof.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RateLimitRule}s. Runs after bearer-token authentication so per-account limits
 * see the account. Per-IP limits use the connection's remote address; behind a reverse proxy,
 * configure {@code server.forward-headers-strategy} so that is the real client.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final AntPathMatcher paths = new AntPathMatcher();
    private final TokenBucketRateLimiter limiter;
    private final RateLimitProperties properties;
    private final List<RateLimitRule> rules;

    public RateLimitFilter(TokenBucketRateLimiter limiter, RateLimitProperties properties, List<RateLimitRule> rules) {
        this.limiter = limiter;
        this.properties = properties;
        this.rules = rules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (properties.enabled()) {
            for (RateLimitRule rule : rules) {
                if (!matches(rule, request)) {
                    continue;
                }
                String subject = rule.perAccount() ? account() : request.getRemoteAddr();
                if (subject == null) {
                    break; // not logged in: the security chain answers 401 instead
                }
                TokenBucketRateLimiter.Decision decision = limiter.tryConsume(
                        rule.name() + ":" + subject, rule.capacity() * properties.scale(), rule.window());
                if (!decision.allowed()) {
                    reject(response, decision);
                    return;
                }
                break;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean matches(RateLimitRule rule, HttpServletRequest request) {
        return rule.method().matches(request.getMethod()) && paths.match(rule.pathPattern(), request.getRequestURI());
    }

    private static String account() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    private static void reject(HttpServletResponse response, TokenBucketRateLimiter.Decision decision)
            throws IOException {
        long seconds = Math.max(1, (long) Math.ceil(decision.retryAfter().toMillis() / 1000.0));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"Too many attempts. Try again in " + seconds + " seconds.\",\"retryAfterSeconds\":"
                + seconds + "}");
    }
}
