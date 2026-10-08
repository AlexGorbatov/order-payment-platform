package com.altronixsoft.opp.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The principal from the Spring Security context. For a JWT-authenticated request the authentication's name is the
 * token's {@code sub} claim, so keys are scoped per user (ADR-0013: {@code customerId = jwt.sub}). Unauthenticated and
 * anonymous requests share {@link #ANONYMOUS}. A name longer than the column is replaced by its SHA-256.
 */
public class SecurityPrincipalResolver implements PrincipalResolver {

    private static final int MAX_LENGTH = 255;

    @Override
    public String resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return ANONYMOUS;
        }
        String name = authentication.getName();
        return name.length() <= MAX_LENGTH ? name : "sha256:" + sha256(name);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
