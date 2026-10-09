package com.altronixsoft.opp.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access tokens of the realm users, requested from the real Keycloak (password grant on the dev client {@code opp-web},
 * as {@code scripts/token.sh} does) and kept for a few minutes: a run is longer than one token, and shorter than five
 * of them.
 */
public final class Tokens {

    private static final Duration KEEP = Duration.ofMinutes(4);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String tokenEndpoint;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<Actor, Token> cache = new ConcurrentHashMap<>();

    private record Token(String value, Instant obtained) {}

    Tokens(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String of(Actor actor) {
        Token token = cache.get(actor);
        if (token == null || token.obtained().plus(KEEP).isBefore(Instant.now())) {
            token = new Token(request(actor), Instant.now());
            cache.put(actor, token);
        }
        return token.value();
    }

    private String request(Actor actor) {
        String form = Map.of(
                        "grant_type", "password",
                        "client_id", "opp-web",
                        "username", actor.username(),
                        "password", "password")
                .entrySet()
                .stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "No token for " + actor.username() + ": " + response.statusCode() + " " + response.body());
            }
            return JSON.readTree(response.body()).get("access_token").stringValue();
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
