package com.altronixsoft.opp.platform.idempotency.testapp;

import static org.springframework.security.config.Customizer.withDefaults;

import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Anonymous access is allowed; HTTP Basic users {@code alice} and {@code bob} and bearer tokens of the form
 * {@code sub:<subject>} (a stub JWT decoder) identify callers. The service owns the filter chain, not the starter.
 */
@Configuration
class TestSecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .httpBasic(withDefaults())
                .oauth2ResourceServer(oauth -> oauth.jwt(withDefaults()))
                .build();
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("alice")
                        .password("{noop}pw")
                        .roles("CUSTOMER")
                        .build(),
                User.withUsername("bob").password("{noop}pw").roles("CUSTOMER").build());
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            if (!token.startsWith("sub_")) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("not a test token");
            }
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(token.substring("sub_".length()))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }
}
