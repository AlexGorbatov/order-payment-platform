package com.altronixsoft.opp.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Security settings of payment-service. The issuer and the JWKS location are the standard
 * {@code spring.security.oauth2.resourceserver.jwt.*} properties.
 *
 * @param audience the {@code aud} value every access token must carry
 */
@ConfigurationProperties("opp.security")
record PaymentSecurityProperties(
        @DefaultValue("payment-service") String audience) {}
