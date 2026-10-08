package com.altronixsoft.opp.platform.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/**
 * Idempotency for controller methods annotated with {@link Idempotent} (architecture §7.3, ADR-0006).
 *
 * <p>{@code preHandle} reserves the key, or answers the request itself; {@code afterCompletion} records the outcome:
 *
 * <pre>
 * no header, required ........................................ 400  idempotency-key-required
 * malformed key ............................................... 400  idempotency-key-invalid
 * claim succeeded ............................................. run the controller
 * existing COMPLETED, same request hash ....................... replay  + Idempotent-Replayed: true
 * existing COMPLETED, different request hash .................. 422  idempotency-key-reuse
 * existing IN_PROGRESS ........................................ 409  request-in-progress, Retry-After: 1
 *
 * after the controller: 2xx, or 4xx except 409/429 ............ store the response
 *                       5xx, 409, 429, unhandled exception .... remove the record (the client may retry)
 * </pre>
 *
 * It needs the {@link IdempotencyBufferingFilter} to have buffered the request body and the response.
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    /** Request header carrying the client's key. */
    public static final String HEADER_KEY = "Idempotency-Key";

    /** Response header on a replayed response. */
    public static final String HEADER_REPLAYED = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);

    private static final String CLAIM_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".claim";
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final int CLAIM_ATTEMPTS = 3;

    /**
     * Pseudo header in the stored headers: the response was produced with {@code sendError}. The colon makes a clash
     * with a real header name impossible.
     */
    static final String SEND_ERROR_MARKER = ":send-error";

    private final IdempotencyRepository repository;
    private final PrincipalResolver principals;
    private final IdempotencyProperties properties;
    private final Environment environment;
    private final MeterRegistry meters;
    private final Map<HandlerMethod, Optional<IdempotencySettings>> settingsCache = new ConcurrentHashMap<>();

    public IdempotencyInterceptor(
            IdempotencyRepository repository,
            PrincipalResolver principals,
            IdempotencyProperties properties,
            Environment environment,
            MeterRegistry meters) {
        this.repository = repository;
        this.principals = principals;
        this.properties = properties;
        this.environment = environment;
        this.meters = meters;
    }

    /** The annotation of a handler method (method level wins over class level), resolved once. */
    static Optional<Idempotent> annotationOf(HandlerMethod handler) {
        Idempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Idempotent.class);
        if (onMethod != null) {
            return Optional.of(onMethod);
        }
        return Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Idempotent.class));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Optional<IdempotencySettings> settings = settingsCache.computeIfAbsent(
                method, m -> annotationOf(m).map(annotation -> IdempotencySettings.of(annotation, environment)));
        if (settings.isEmpty()) {
            return true;
        }

        List<String> keys = Collections.list(request.getHeaders(HEADER_KEY));
        if (keys.isEmpty()) {
            if (!settings.get().required()) {
                return true;
            }
            ProblemResponses.write(
                    response,
                    400,
                    ProblemResponses.KEY_REQUIRED,
                    "Idempotency-Key is required",
                    "This operation requires an " + HEADER_KEY + " header (1 to 255 printable ASCII characters).");
            return false;
        }
        String key = keys.getFirst();
        if (keys.size() > 1 || !VALID_KEY.matcher(key).matches()) {
            ProblemResponses.write(
                    response,
                    400,
                    ProblemResponses.KEY_INVALID,
                    "Idempotency-Key is invalid",
                    HEADER_KEY + " must be a single value of 1 to 255 printable ASCII characters without spaces.");
            return false;
        }

        String principal = principals.resolve();
        String hash = fingerprint(request);
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            if (repository.claim(principal, key, hash, settings.get().ttl(), properties.inProgressTimeout())) {
                request.setAttribute(CLAIM_ATTRIBUTE, new Claim(principal, key));
                return true;
            }
            Optional<IdempotencyRecord> existing = repository.find(principal, key);
            if (existing.isEmpty()) {
                continue; // released between our claim attempt and the lookup: try to claim again
            }
            answerFromExisting(existing.get(), hash, response);
            return false;
        }
        conflictInProgress(response);
        return false;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        Claim claim = (Claim) request.getAttribute(CLAIM_ATTRIBUTE);
        if (claim == null) {
            return;
        }
        request.removeAttribute(CLAIM_ATTRIBUTE);
        try {
            ContentCachingResponseWrapper buffered =
                    WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
            if (request.isAsyncStarted() || buffered == null) {
                log.error(
                        "Idempotent request {} {} cannot be recorded ({}); releasing the key",
                        request.getMethod(),
                        request.getRequestURI(),
                        buffered == null ? "response was not buffered" : "asynchronous handling is not supported");
                repository.release(claim.principal(), claim.key());
                return;
            }
            int status = response.getStatus();
            if (exception == null && isStorable(status)) {
                Map<String, List<String>> headers = replayableHeaders(response);
                if (buffered instanceof BufferedResponse capturing && capturing.errorSent()) {
                    headers.put(SEND_ERROR_MARKER, List.of("true"));
                }
                repository.complete(claim.principal(), claim.key(), status, headers, buffered.getContentAsByteArray());
            } else {
                repository.release(claim.principal(), claim.key());
            }
        } catch (RuntimeException e) {
            // The response is already decided; a failure here only leaves the record IN_PROGRESS until it is abandoned.
            log.error(
                    "Cannot record the outcome of idempotent request {} {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    e);
        }
    }

    /** 2xx and 4xx are deterministic answers to this request; 409/429 invite a retry; 5xx are failures. */
    static boolean isStorable(int status) {
        return (status >= 200 && status < 300) || (status >= 400 && status < 500 && status != 409 && status != 429);
    }

    private String fingerprint(HttpServletRequest request) {
        if (IdempotencyBufferingFilter.isForm(request)) {
            return RequestFingerprint.ofForm(
                    request.getMethod(), request.getRequestURI(), request.getQueryString(), request.getParameterMap());
        }
        CachedBodyRequest cached = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
        if (cached == null) {
            throw new IllegalStateException("IdempotencyBufferingFilter did not run for " + request.getRequestURI()
                    + "; check platform.idempotency.filter-order and that the filter is registered");
        }
        return RequestFingerprint.of(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getContentType(),
                cached.body());
    }

    private void answerFromExisting(IdempotencyRecord existing, String hash, HttpServletResponse response)
            throws IOException {
        if (existing.status() == IdempotencyRecord.Status.IN_PROGRESS) {
            conflictInProgress(response);
            return;
        }
        if (!existing.requestHash().equals(hash)) {
            meters.counter("idempotency.conflicts", "reason", "key-reuse").increment();
            ProblemResponses.write(
                    response,
                    422,
                    ProblemResponses.KEY_REUSE,
                    "Idempotency-Key was already used for a different request",
                    "The key was used with a different method, path or body. Use a new key for a new request.");
            return;
        }
        meters.counter("idempotency.replays").increment();
        response.setHeader(HEADER_REPLAYED, "true");
        existing.responseHeaders().forEach((name, values) -> {
            if (name.equals(SEND_ERROR_MARKER)) {
                return;
            }
            response.setHeader(name, values.getFirst());
            values.stream().skip(1).forEach(value -> response.addHeader(name, value));
        });
        if (existing.responseHeaders().containsKey(SEND_ERROR_MARKER)) {
            // The original body was rendered by the container's error page; render it again the same way.
            response.sendError(existing.responseStatus());
            return;
        }
        response.setStatus(existing.responseStatus());
        byte[] body = existing.responseBody() == null ? new byte[0] : existing.responseBody();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void conflictInProgress(HttpServletResponse response) throws IOException {
        meters.counter("idempotency.conflicts", "reason", "in-progress").increment();
        response.setHeader("Retry-After", "1");
        ProblemResponses.write(
                response,
                409,
                ProblemResponses.IN_PROGRESS,
                "A request with this Idempotency-Key is in progress",
                "The first request with this key has not finished yet. Retry in a moment.");
    }

    private Map<String, List<String>> replayableHeaders(HttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : properties.replayHeaders()) {
            List<String> values = List.copyOf(response.getHeaders(name));
            if (!values.isEmpty()) {
                headers.put(name, values);
            }
        }
        return headers;
    }

    /** What the request reserved; stored as a request attribute between {@code preHandle} and {@code afterCompletion}. */
    private record Claim(String principal, String key) {}
}
