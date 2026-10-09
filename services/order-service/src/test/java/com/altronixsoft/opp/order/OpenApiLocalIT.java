package com.altronixsoft.opp.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** In the {@code local} profile the OpenAPI document and Swagger UI are served, without a token, and are complete. */
@ActiveProfiles("local")
class OpenApiLocalIT extends AbstractApiIT {

    private JsonNode document() {
        Reply reply = get("/v3/api-docs", null);
        assertThat(reply.status()).isEqualTo(200);
        return reply.json();
    }

    @Test
    void theDocumentIsGeneratedWithoutAToken() throws IOException {
        Reply reply = get("/v3/api-docs", null);

        assertThat(reply.status()).isEqualTo(200);
        JsonNode api = reply.json();
        assertThat(api.path("openapi").stringValue()).startsWith("3.");
        assertThat(api.path("info").path("title").stringValue()).isEqualTo("Order service API");
        // keep a copy next to the build output: `target/openapi/order-service.json`
        Path out = Path.of("target", "openapi", "order-service.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(api));
    }

    @Test
    void theYamlFlavourAndSwaggerUiAreServedToo() {
        assertThat(get("/v3/api-docs.yaml", null).status()).isEqualTo(200);
        assertThat(get("/swagger-ui/index.html", null).status()).isEqualTo(200);
        assertThat(get("/swagger-ui.html", null).status()).isIn(200, 302);
    }

    @Test
    void theBusinessApiStillNeedsAToken() {
        assertThat(get("/api/v1/products", null).status()).isEqualTo(401);
        assertThat(get("/actuator/metrics", null).status()).isEqualTo(401);
    }

    @Test
    void describesEveryEndpointOfArchitectureSection11() {
        JsonNode paths = document().path("paths");

        assertThat(paths.path("/api/v1/products").has("get")).isTrue();
        assertThat(paths.path("/api/v1/orders").has("post")).isTrue();
        assertThat(paths.path("/api/v1/orders").has("get")).isTrue();
        assertThat(paths.path("/api/v1/orders/{id}").has("get")).isTrue();
        assertThat(paths.path("/api/v1/orders/{id}/cancel").has("post")).isTrue();
        assertThat(paths.path("/api/v1/orders/{id}/refund").has("post")).isTrue();
        // "Both services" in §11: the dead-letter API of the messaging starter
        assertThat(paths.path("/admin/dead-letters").has("get")).isTrue();
        assertThat(paths.path("/admin/dead-letters/{id}").has("get")).isTrue();
        assertThat(paths.path("/admin/dead-letters/{id}/replay").has("post")).isTrue();
        assertThat(paths.path("/admin/dead-letters/{id}/resolve").has("post")).isTrue();
        assertThat(paths.size()).as("nothing else is documented").isEqualTo(9);
        assertThat(paths.propertyNames().stream().filter(p -> p.contains("actuator")))
                .isEmpty();
    }

    @Test
    void declaresTheBearerSchemeAndRequiresItEverywhere() {
        JsonNode api = document();

        JsonNode scheme = api.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").stringValue()).isEqualTo("http");
        assertThat(scheme.path("scheme").stringValue()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").stringValue()).isEqualTo("JWT");
        assertThat(api.path("security").get(0).has("bearerAuth")).isTrue();
    }

    @Test
    void theStateChangingOperationsDeclareTheIdempotencyKeyHeader() {
        JsonNode paths = document().path("paths");

        for (JsonNode operation : List.of(
                paths.path("/api/v1/orders").path("post"),
                paths.path("/api/v1/orders/{id}/cancel").path("post"),
                paths.path("/api/v1/orders/{id}/refund").path("post"))) {
            List<JsonNode> keys = new ArrayList<>();
            operation.path("parameters").forEach(p -> {
                if ("Idempotency-Key".equals(p.path("name").stringValue())) {
                    keys.add(p);
                }
            });
            assertThat(keys).as(operation.path("summary").stringValue()).hasSize(1);
            assertThat(keys.get(0).path("in").stringValue()).isEqualTo("header");
            assertThat(keys.get(0).path("required").booleanValue()).isTrue();
            assertThat(keys.get(0).has("example")).isTrue();
        }
    }

    @Test
    void documentsTheRequestWithExamplesAndLimits() {
        JsonNode schemas = document().path("components").path("schemas");

        JsonNode item = schemas.path("Item");
        if (item.isMissingNode()) {
            item = schemas.path("CreateOrderRequest.Item");
        }
        assertThat(schemas.path("CreateOrderRequest")
                        .path("properties")
                        .path("items")
                        .path("maxItems")
                        .intValue())
                .isEqualTo(20);
        assertThat(schemas.path("CreateOrderRequest")
                        .path("properties")
                        .path("items")
                        .path("minItems")
                        .intValue())
                .isEqualTo(1);
        assertThat(item.path("properties").path("quantity").path("maximum").intValue())
                .isEqualTo(10);
        assertThat(item.path("properties").path("quantity").path("minimum").intValue())
                .isEqualTo(1);
        assertThat(item.path("properties").path("sku").path("example").stringValue())
                .isEqualTo("MUG-JAVA");
        assertThat(item.path("properties").path("quantity").path("example").asString())
                .isEqualTo("2");
    }

    @Test
    void documentsPagingAndTheProblemResponses() {
        JsonNode paths = document().path("paths");

        JsonNode list = paths.path("/api/v1/orders").path("get");
        JsonNode size = null;
        for (JsonNode parameter : list.path("parameters")) {
            if ("size".equals(parameter.path("name").stringValue())) {
                size = parameter;
            }
        }
        assertThat(size).isNotNull();
        assertThat(size.path("schema").path("maximum").intValue()).isEqualTo(100);
        assertThat(size.path("schema").path("default").intValue()).isEqualTo(20);

        JsonNode create = paths.path("/api/v1/orders").path("post").path("responses");
        assertThat(create.path("201").path("headers").has("Location")).isTrue();
        JsonNode badRequest = create.path("400").path("content").path("application/problem+json");
        assertThat(badRequest.path("example").toString()).contains("urn:problem-type:validation-failed");
        JsonNode unprocessable = create.path("422").path("content").path("application/problem+json");
        assertThat(unprocessable.path("example").toString()).contains("urn:problem-type:product-not-available");
        assertThat(paths.path("/api/v1/orders/{id}/cancel")
                        .path("post")
                        .path("responses")
                        .path("409")
                        .toString())
                .contains("urn:problem-type:order-state-conflict");
        assertThat(paths.path("/api/v1/orders/{id}/refund")
                        .path("post")
                        .path("responses")
                        .has("202"))
                .isTrue();
    }

    @Test
    void theOrderSchemaListsTheStatusValues() {
        JsonNode status = document()
                .path("components")
                .path("schemas")
                .path("OrderResponse")
                .path("properties")
                .path("status");

        assertThat(status.toString())
                .contains("PENDING_PAYMENT", "PAID", "CANCELLED", "REFUND_REQUESTED", "REFUNDED", "REFUND_FAILED");
    }
}
