package com.altronixsoft.opp.payment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real Keycloak with the realm the local environment uses ({@code infra/keycloak/realm-opp.json}), shared by the
 * integration tests of the JVM, and the tokens the tests need: genuine ones from Keycloak, and deliberately bad ones.
 */
final class TestKeycloak {

    static final String CLIENT_SECRET = "opp-ops-cli-test-secret";

    static final KeycloakContainer KEYCLOAK = new KeycloakContainer("keycloak/keycloak:26.7.5")
            .withRealmImportFile("realm-opp.json")
            .withEnv("KEYCLOAK_OPS_CLIENT_SECRET", CLIENT_SECRET);

    static final String CUSTOMER1_ID = "00000000-0000-4000-8000-000000000001";
    static final String CUSTOMER2_ID = "00000000-0000-4000-8000-000000000002";
    static final String ADMIN1_ID = "00000000-0000-4000-8000-0000000000a1";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    static {
        KEYCLOAK.start();
    }

    private TestKeycloak() {}

    /** The {@code iss} of the tokens: Keycloak names itself after the address the token was requested from. */
    static String issuer() {
        return KEYCLOAK.getAuthServerUrl() + "/realms/opp";
    }

    /** A genuine access token of a realm user (password grant on the dev client {@code opp-web}), cached for the JVM. */
    static String tokenOf(String username) {
        return TOKENS.computeIfAbsent(
                username,
                user -> requestToken(
                        "opp-web", Map.of("grant_type", "password", "username", user, "password", "password")));
    }

    /** A genuine token of the service account of {@code opp-ops-cli} (client credentials). */
    static String opsClientToken() {
        return TOKENS.computeIfAbsent(
                "opp-ops-cli",
                client -> requestToken(
                        "opp-ops-cli", Map.of("grant_type", "client_credentials", "client_secret", CLIENT_SECRET)));
    }

    /**
     * A genuine token of {@code customer1} whose audience is not {@code payment-service}: it comes from a client without
     * the audience mapper. Keycloak signs it, so only the audience check can reject it.
     */
    static String tokenForAnotherAudience() {
        return TOKENS.computeIfAbsent("customer1@no-audience", key -> {
            createClientWithoutAudienceMapper();
            return requestToken(
                    "opp-no-audience",
                    Map.of("grant_type", "password", "username", "customer1", "password", "password"));
        });
    }

    /** Claims that would make a naive service trust the token: right issuer and audience, admin among the roles. */
    private static JWTClaimsSet.Builder impostorClaims(String subject) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(subject)
                .audience(List.of("order-service", "payment-service"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("realm_access", Map.of("roles", List.of("customer", "admin", "ops")));
    }

    /** Perfect claims, signed with a key Keycloak has never heard of. */
    static String signedByAnotherKey(String subject) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(UUID.randomUUID().toString())
                            .build(),
                    impostorClaims(subject).build());
            jwt.sign(new RSASSASigner(keys.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Perfect claims and no signature at all ({@code "alg":"none"}). */
    static String unsigned(String subject) {
        return new PlainJWT(impostorClaims(subject).build()).serialize();
    }

    /** A genuine token whose payload was rewritten to claim admin rights, keeping Keycloak's signature. */
    static String tamperedToClaimAdmin(String genuineToken) {
        String[] parts = genuineToken.split("\\.");
        JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        tools.jackson.databind.node.ObjectNode edited = (tools.jackson.databind.node.ObjectNode) payload.deepCopy();
        edited.putObject("realm_access")
                .putArray("roles")
                .add("customer")
                .add("admin")
                .add("ops");
        String newPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(edited));
        return parts[0] + "." + newPayload + "." + parts[2];
    }

    // ---- plumbing ----

    private static String requestToken(String clientId, Map<String, String> form) {
        Map<String, String> all = new java.util.LinkedHashMap<>(form);
        all.put("client_id", clientId);
        HttpResponse<String> response =
                post(KEYCLOAK.getAuthServerUrl() + "/realms/opp/protocol/openid-connect/token", form(all), null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("No token for " + clientId + " " + form.get("username") + ": "
                    + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body()).get("access_token").stringValue();
    }

    private static void createClientWithoutAudienceMapper() {
        String admin = adminToken();
        String client = """
                {"clientId":"opp-no-audience","enabled":true,"publicClient":true,
                 "directAccessGrantsEnabled":true,"standardFlowEnabled":false,"fullScopeAllowed":true}""";
        HttpResponse<String> response = post(
                KEYCLOAK.getAuthServerUrl() + "/admin/realms/opp/clients",
                client,
                "Bearer " + admin,
                "application/json");
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new IllegalStateException("Cannot create client: " + response.statusCode() + " " + response.body());
        }
    }

    private static String adminToken() {
        HttpResponse<String> response = post(
                KEYCLOAK.getAuthServerUrl() + "/realms/master/protocol/openid-connect/token",
                form(Map.of(
                        "grant_type",
                        "password",
                        "client_id",
                        "admin-cli",
                        "username",
                        KEYCLOAK.getAdminUsername(),
                        "password",
                        KEYCLOAK.getAdminPassword())),
                null);
        return JSON.readTree(response.body()).get("access_token").stringValue();
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static HttpResponse<String> post(String url, String body, String authorization) {
        return post(url, body, authorization, "application/x-www-form-urlencoded");
    }

    private static HttpResponse<String> post(String url, String body, String authorization, String contentType) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
