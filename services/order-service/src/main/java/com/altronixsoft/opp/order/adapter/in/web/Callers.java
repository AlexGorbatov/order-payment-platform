package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.Caller;
import org.springframework.security.core.Authentication;

/** Translates the authenticated principal into the application layer's {@link Caller}. */
final class Callers {

    static final String ROLE_ADMIN = "ROLE_ADMIN";

    private Callers() {}

    /** The subject is the authentication's name, which for a JWT is the {@code sub} claim (ADR-0013). */
    static Caller from(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> ROLE_ADMIN.equals(a.getAuthority()));
        return new Caller(authentication.getName(), admin);
    }
}
