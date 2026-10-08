package com.altronixsoft.opp.platform.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a controller method idempotent with respect to the {@code Idempotency-Key} request header (architecture §7.3,
 * ADR-0006). Placed on a class it applies to every handler method of the class; a method-level annotation wins.
 *
 * <p>For a given caller and key:
 *
 * <ul>
 *   <li>the first request runs and its response (2xx, or 4xx other than 409 and 429) is stored;
 *   <li>a repeat with the same request is answered with the stored response and {@code Idempotent-Replayed: true};
 *   <li>a repeat with a different request is rejected with {@code 422};
 *   <li>a repeat while the first is still running is rejected with {@code 409} and {@code Retry-After: 1};
 *   <li>a 5xx or an unhandled exception removes the record, so the client may retry.
 * </ul>
 *
 * Handler methods must complete the response synchronously (no {@code DeferredResult}, {@code Callable} or reactive
 * return types).
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** Whether the header is mandatory. If {@code true} a request without it gets {@code 400}. */
    boolean required() default true;

    /**
     * How long the stored response is kept and the key stays reserved, as an ISO-8601 duration ({@code PT24H}). Property
     * placeholders such as {@code ${app.idempotency.ttl:PT24H}} are resolved.
     */
    String ttl() default "PT24H";
}
