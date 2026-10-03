package com.writeproof.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Adds ROLE_ADMIN / ROLE_MODERATOR to a signed-in account's authentication, if it has one. */
@Component
public class AdminAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AdminRoles roles;

    AdminAuthenticationConverter(AdminRoles roles) {
        this.roles = roles;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        List<SimpleGrantedAuthority> authorities = roles.roleOf(UUID.fromString(jwt.getSubject()))
                .map(r -> List.of(new SimpleGrantedAuthority(r.authority())))
                .orElse(List.of());
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
