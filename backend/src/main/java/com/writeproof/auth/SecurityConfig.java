package com.writeproof.auth;


import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.writeproof.security.RateLimitFilter;
import com.writeproof.security.RateLimitProperties;
import com.writeproof.security.RateLimitRule;
import com.writeproof.security.RequestSizeLimitFilter;
import com.writeproof.security.TokenBucketRateLimiter;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SecurityConfig {

    private static final int MIN_SECRET_BYTES = 32;

    /** Only when serving HTTP: one-shot commands (e.g. the calibration export) run without a web server. */
    @Bean
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenBucketRateLimiter rateLimiter,
                                            RateLimitProperties rateLimits, MeterRegistry meters,
                                            AccountAuthenticationConverter accounts) throws Exception {
        return http
                // Stateless bearer-token API: no cookies, so no CSRF surface.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/accounts", "/api/auth/challenge", "/api/auth/verify")
                        .permitAll()
                        // Restoring a wallet on a new device happens before any login.
                        .requestMatchers(HttpMethod.GET, "/api/backups/*").permitAll()
                        // Anyone may verify the ledger: key, checkpoints and proofs are public.
                        .requestMatchers(HttpMethod.GET, "/api/ledger/key", "/api/ledger/checkpoint",
                                "/api/ledger/checkpoints", "/api/ledger/proof/**").permitAll()
                        // Open letters are public to anyone with the link.
                        .requestMatchers(HttpMethod.GET, "/api/open-letters/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/open-letters/*/reports").permitAll()
                        // Admin area: any signed-in account may ask whether it is an admin;
                        // moderation is open to moderators; everything else needs ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/admin/me").authenticated()
                        .requestMatchers("/api/admin/moderation/**").hasAnyRole("ADMIN", "MODERATOR")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(accounts)))
                // A JSON API: nothing it returns should ever render, frame, or leak a referrer.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .crossOriginResourcePolicy(c -> c.policy(
                                CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .addFilterBefore(new RequestSizeLimitFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimits, RateLimitRule.DEFAULTS, meters),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        byte[] secret = Base64.getDecoder().decode(properties.jwtSecret());
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_SECRET_BYTES + " bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(TokenService.ISSUER)));
        return decoder;
    }
}
