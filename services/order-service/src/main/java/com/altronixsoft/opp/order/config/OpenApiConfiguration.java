package com.altronixsoft.opp.order.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Describes the API for OpenAPI: title, and the bearer-token scheme every operation requires. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI orderServiceOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Order service API").version("v1").description("""
                                Orders and catalog of the Order & Payment Integration Platform.

                                Authentication: a Keycloak access token of realm `opp` with audience `order-service`, \
                                sent as `Authorization: Bearer <token>`. Errors are RFC 9457 problem details whose \
                                `type` is `urn:problem-type:<code>`. Endpoints that change state require an \
                                `Idempotency-Key` header."""))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Keycloak access token (realm `opp`, audience `order-service`)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
