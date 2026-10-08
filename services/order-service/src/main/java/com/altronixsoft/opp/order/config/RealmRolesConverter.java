package com.altronixsoft.opp.order.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps the Keycloak realm roles in {@code realm_access.roles} to {@code ROLE_CUSTOMER}, {@code ROLE_ADMIN} and
 * {@code ROLE_OPS}. Other realm roles (Keycloak's {@code offline_access}, {@code default-roles-opp}, ...) grant nothing,
 * and so does a missing or malformed claim.
 */
final class RealmRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    static final Set<String> KNOWN_ROLES = Set.of("customer", "admin", "ops");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String name && KNOWN_ROLES.contains(name)) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + name.toUpperCase(Locale.ROOT)));
                }
            }
        }
        return authorities;
    }
}
