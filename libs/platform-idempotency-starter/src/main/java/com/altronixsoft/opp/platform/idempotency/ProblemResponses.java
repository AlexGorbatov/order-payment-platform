package com.altronixsoft.opp.platform.idempotency;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Writes RFC 9457 problem details directly, so the answer does not depend on the service's exception handling. */
final class ProblemResponses {

    /** Problem types are URNs: stable identifiers that need not resolve. */
    static final String KEY_REQUIRED = "urn:opp:problem:idempotency-key-required";

    static final String KEY_INVALID = "urn:opp:problem:idempotency-key-invalid";
    static final String KEY_REUSE = "urn:opp:problem:idempotency-key-reuse";
    static final String IN_PROGRESS = "urn:opp:problem:request-in-progress";
    static final String BODY_TOO_LARGE = "urn:opp:problem:request-body-too-large";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemResponses() {}

    static void write(HttpServletResponse response, int status, String type, String title, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type);
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        byte[] body = JSON.writeValueAsBytes(problem);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
