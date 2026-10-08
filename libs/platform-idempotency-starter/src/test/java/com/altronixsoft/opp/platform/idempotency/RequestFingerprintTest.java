package com.altronixsoft.opp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static String hash(String method, String path, String query, String type, String body) {
        return RequestFingerprint.of(method, path, query, type, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isAHexSha256() {
        assertThat(hash("POST", "/orders", null, "application/json", "{}")).matches("[0-9a-f]{64}");
    }

    @Test
    void sameRequestSameHash() {
        assertThat(hash("POST", "/orders", null, "application/json", "{\"a\":1}"))
                .isEqualTo(hash("POST", "/orders", null, "application/json", "{\"a\":1}"));
    }

    @Test
    void jsonKeyOrderWhitespaceAndNestingOrderDoNotMatter() {
        String a = hash("POST", "/orders", null, "application/json", "{\"a\":1,\"b\":{\"x\":[1,2],\"y\":\"t\"}}");
        String b = hash(
                "POST",
                "/orders",
                null,
                "application/json; charset=UTF-8",
                " {\n \"b\": {\"y\": \"t\", \"x\": [1, 2]},\n \"a\": 1 } ");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void arrayOrderValuesAndTypesDo() {
        String base = hash("POST", "/p", null, "application/json", "{\"a\":[1,2]}");

        assertThat(hash("POST", "/p", null, "application/json", "{\"a\":[2,1]}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/p", null, "application/json", "{\"a\":[1,3]}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/p", null, "application/json", "{\"a\":[\"1\",\"2\"]}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/p", null, "application/json", "{\"a\":[1,2],\"b\":null}"))
                .isNotEqualTo(base);
    }

    @Test
    void decimalsKeepTheirValue() {
        assertThat(hash("POST", "/p", null, "application/json", "{\"amount\":0.1}"))
                .isNotEqualTo(hash("POST", "/p", null, "application/json", "{\"amount\":0.10000000000000001}"));
    }

    @Test
    void methodPathAndQueryAreCovered() {
        String base = hash("POST", "/orders/1/cancel", null, "application/json", "{}");

        assertThat(hash("PUT", "/orders/1/cancel", null, "application/json", "{}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/orders/2/cancel", null, "application/json", "{}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/orders/1/cancel", "force=true", "application/json", "{}"))
                .isNotEqualTo(base);
        assertThat(hash("POST", "/orders/1/cancel", "", "application/json", "{}"))
                .isEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeShiftedToForgeACollision() {
        assertThat(hash("POST", "/a", null, "text/plain", "b"))
                .isNotEqualTo(hash("POST", "/", null, "text/plain", "ab"));
        assertThat(hash("POST/a", "/b", null, "text/plain", ""))
                .isNotEqualTo(hash("POST", "/a/b", null, "text/plain", ""));
    }

    @Test
    void nonJsonBodiesAreHashedAsReceived() {
        assertThat(hash("POST", "/p", null, "text/plain", "a b"))
                .isNotEqualTo(hash("POST", "/p", null, "text/plain", "a  b"));
        assertThat(hash("POST", "/p", null, null, "{\"a\":1,\"b\":2}"))
                .isNotEqualTo(hash("POST", "/p", null, null, "{\"b\":2,\"a\":1}"));
    }

    @Test
    void aBrokenJsonBodyIsHashedAsReceivedInsteadOfFailing() {
        assertThat(hash("POST", "/p", null, "application/json", "{broken"))
                .isNotEqualTo(hash("POST", "/p", null, "application/json", "{broken "));
    }

    @Test
    void anEmptyBodyIsValid() {
        assertThat(hash("POST", "/p", null, "application/json", "")).isEqualTo(hash("POST", "/p", null, null, ""));
    }

    @Test
    void formParametersAreSortedSoTheirOrderDoesNotMatter() {
        String a = RequestFingerprint.ofForm(
                "POST", "/f", null, Map.of("sku", new String[] {"B"}, "qty", new String[] {"3"}));
        String b = RequestFingerprint.ofForm(
                "POST", "/f", null, Map.of("qty", new String[] {"3"}, "sku", new String[] {"B"}));
        String repeated = RequestFingerprint.ofForm("POST", "/f", null, Map.of("tag", new String[] {"y", "x"}));
        String repeatedReordered =
                RequestFingerprint.ofForm("POST", "/f", null, Map.of("tag", new String[] {"x", "y"}));

        assertThat(a).isEqualTo(b);
        assertThat(repeated).isEqualTo(repeatedReordered);
        assertThat(a).isNotEqualTo(repeated);
    }
}
