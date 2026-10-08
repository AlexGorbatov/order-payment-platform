package com.altronixsoft.opp.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The identity of a request for idempotency: hex SHA-256 of {@code method}, path (with the query string) and the
 * <i>canonical</i> body. A JSON body is canonicalised — object keys sorted, whitespace dropped — so a client that
 * retries with the same data in a different key order or formatting still sends "the same request"; any other body is
 * hashed as received. A body that claims to be JSON but is not is hashed as received (the controller will reject it).
 */
final class RequestFingerprint {

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private RequestFingerprint() {}

    static String of(String method, String path, String query, String contentType, byte[] body) {
        MessageDigest digest = sha256();
        update(digest, method);
        update(digest, query == null || query.isEmpty() ? path : path + "?" + query);
        digest.update(canonicalBody(contentType, body));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Form posts have no cached body; their identity is the sorted parameters. */
    static String ofForm(String method, String path, String query, Map<String, String[]> parameters) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(parameters).forEach((name, values) -> {
            String[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            canonical.append(name).append('=').append(String.join(",", sorted)).append('&');
        });
        return of(method, path, query, "text/plain", canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static byte[] canonicalBody(String contentType, byte[] body) {
        if (body.length == 0
                || contentType == null
                || !contentType.toLowerCase().contains("json")) {
            return body;
        }
        try {
            Object tree = CANONICAL.readValue(body, Object.class);
            return CANONICAL.writeValueAsBytes(tree);
        } catch (JacksonException e) {
            return body;
        }
    }

    /** Separator-terminated so that ("POST", "/a/b") and ("POST/a", "/b") cannot collide. */
    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
