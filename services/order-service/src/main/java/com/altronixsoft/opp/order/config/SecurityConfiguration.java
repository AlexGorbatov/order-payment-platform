package com.altronixsoft.opp.order.config;

import com.altronixsoft.opp.order.adapter.in.web.Problems;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security of order-service (architecture §12, ADR-0013): an OAuth2 resource server for Keycloak's realm {@code opp}.
 *
 * <ul>
 *   <li>Access tokens are validated for signature (JWKS), {@code iss}, {@code exp}/{@code nbf} and
 *       {@code aud=order-service}; the realm roles become {@code ROLE_CUSTOMER|ADMIN|OPS}.
 *   <li>Stateless: no session, no CSRF (the API is called with bearer tokens, never with ambient cookies).
 *   <li>{@code 401} and {@code 403} are problem details like every other error; {@code 401} keeps the
 *       {@code WWW-Authenticate} challenge.
 *   <li>Which role may call what is decided here, per endpoint, so the whole matrix is in one place; which orders a
 *       caller may see is decided by the use cases.
 *   <li>Actuator: {@code health} and {@code info} are public, every other endpoint needs role {@code ops}.
 *   <li>OpenAPI documents are reachable without a token only when {@code springdoc.api-docs.enabled=true}, which only
 *       the {@code local} profile sets.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderSecurityProperties.class)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled)
            throws Exception {
        BearerTokenAuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
        BearerTokenAccessDeniedHandler denial = new BearerTokenAccessDeniedHandler();

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            challenge.commence(request, response, exception);
                            Problems.write(
                                    request,
                                    response,
                                    HttpStatus.UNAUTHORIZED,
                                    Problems.UNAUTHORIZED,
                                    "A valid access token for this service is required");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            denial.handle(request, response, exception);
                            Problems.write(
                                    request,
                                    response,
                                    HttpStatus.FORBIDDEN,
                                    Problems.FORBIDDEN,
                                    "You are not allowed to perform this operation");
                        }))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    requests.requestMatchers("/actuator/**").hasRole("OPS");
                    if (apiDocsEnabled) {
                        requests.requestMatchers(
                                        "/v3/api-docs",
                                        "/v3/api-docs.yaml",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .permitAll();
                    }
                    requests.requestMatchers(HttpMethod.GET, "/api/v1/products").authenticated();
                    requests.requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("CUSTOMER");
                    requests.requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*")
                            .hasAnyRole("CUSTOMER", "ADMIN");
                    requests.requestMatchers(HttpMethod.POST, "/api/v1/orders/*/cancel")
                            .hasRole("CUSTOMER");
                    requests.requestMatchers(HttpMethod.POST, "/api/v1/orders/*/refund")
                            .hasRole("ADMIN");
                    requests.anyRequest().authenticated();
                });
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRolesConverter());
        return converter;
    }

    /**
     * The JWKS location is separate from the issuer because inside a container network the service reaches Keycloak by
     * another host name than the one the tokens name as their issuer. Nothing is fetched until the first token arrives.
     */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri,
            OrderSecurityProperties properties) {
        String jwks = jwkSetUri.isBlank() ? issuer + "/protocol/openid-connect/certs" : jwkSetUri;
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(tokenValidator(issuer, properties.audience()));
        return decoder;
    }

    /** Timestamps (with the default 60 s clock skew), issuer and audience. The signature is checked by the decoder. */
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(audience));
    }
}
