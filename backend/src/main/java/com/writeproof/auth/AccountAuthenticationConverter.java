package com.writeproof.auth;

import com.writeproof.admin.AdminRoles;
import com.writeproof.identity.AccountStatus;
import java.util.ArrayList;
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
 * every request: a token from before a forced sign-out is rejected (401). Every token carries the
 * authority of its {@link Surface}; only an admin-app token gives an admin or moderator
 * ROLE_ADMIN / ROLE_MODERATOR. Roles are never baked into the token.
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
        // Tokens issued before surfaces existed have no audience: they belong to the public app.
        Surface surface = jwt.getAudience() != null && jwt.getAudience().contains(Surface.ADMIN.audience())
                ? Surface.ADMIN : Surface.APP;
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(surface.authority()));
        if (surface == Surface.ADMIN) {
            roles.roleOf(account).ifPresent(r -> authorities.add(new SimpleGrantedAuthority(r.authority())));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
