package com.writeproof.auth;

import com.writeproof.admin.AdminRoles;
import com.writeproof.identity.AccountStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a valid token into an authentication, checked against the account's current state on
 * every request: a token from before a forced sign-out is rejected (401), and an admin or
 * moderator gets ROLE_ADMIN / ROLE_MODERATOR. Nothing about either is baked into the token.
 */
@Component
class AccountAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AdminRoles roles;
    private final AccountStatus status;

    AccountAuthenticationConverter(AdminRoles roles, AccountStatus status) {
        this.roles = roles;
        this.status = status;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID account = UUID.fromString(jwt.getSubject());
        if (status.revoked(account, jwt.getIssuedAt())) {
            throw new InvalidBearerTokenException("This session was signed out; sign in again");
        }
        List<SimpleGrantedAuthority> authorities = roles.roleOf(account)
                .map(r -> List.of(new SimpleGrantedAuthority(r.authority())))
                .orElse(List.of());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
