package com.altronixsoft.opp.order.config;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts only tokens issued for this service: the {@code aud} claim must contain the expected audience. Without it a
 * token minted for another service of the same realm would be good here (ADR-0013).
 */
final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error WRONG_AUDIENCE =
            new OAuth2Error("invalid_token", "The token was not issued for this service", null);

    private final String audience;

    AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        List<String> audiences = token.getAudience();
        return audiences != null && audiences.contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(WRONG_AUDIENCE);
    }
}
