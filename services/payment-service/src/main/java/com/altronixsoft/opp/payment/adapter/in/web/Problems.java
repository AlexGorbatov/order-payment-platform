package com.altronixsoft.opp.payment.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * The error vocabulary of the API. Every failure is an RFC 9457 problem whose {@code type} is
 * {@code urn:problem-type:<code>}; the codes are part of the contract, the {@code detail} texts are for humans.
 */
public final class Problems {

    public static final String TYPE_PREFIX = "urn:problem-type:";

    public static final String UNAUTHORIZED = "unauthorized";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not-found";
    public static final String PAYMENT_NOT_FOUND = "payment-not-found";
    public static final String VALIDATION_FAILED = "validation-failed";
    public static final String MALFORMED_REQUEST = "malformed-request";
    public static final String PAYMENT_NOT_CONFIRMABLE = "payment-not-confirmable";
    public static final String CONCURRENT_MODIFICATION = "concurrent-modification";
    public static final String PROVIDER_UNAVAILABLE = "payment-provider-unavailable";
    public static final String PROVIDER_ERROR = "payment-provider-error";
    public static final String METHOD_NOT_ALLOWED = "method-not-allowed";
    public static final String NOT_ACCEPTABLE = "not-acceptable";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported-media-type";
    public static final String INTERNAL_ERROR = "internal-error";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Problems() {}

    public static URI type(String code) {
        return URI.create(TYPE_PREFIX + code);
    }

    public static ProblemDetail of(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    /**
     * Writes a problem straight to the servlet response, for failures that happen before Spring MVC is involved (the
     * security filter chain).
     */
    public static void write(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", TYPE_PREFIX + code);
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        byte[] body = JSON.writeValueAsBytes(problem);
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
