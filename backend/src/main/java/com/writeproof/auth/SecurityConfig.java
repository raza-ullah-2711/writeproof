package com.writeproof.auth;

import static org.springframework.security.config.Customizer.withDefaults;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
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
                                            RateLimitProperties rateLimits) throws Exception {
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
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(withDefaults()))
                // A JSON API: nothing it returns should ever render, frame, or leak a referrer.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .crossOriginResourcePolicy(c -> c.policy(
                                CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .addFilterBefore(new RequestSizeLimitFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, rateLimits, RateLimitRule.DEFAULTS),
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
