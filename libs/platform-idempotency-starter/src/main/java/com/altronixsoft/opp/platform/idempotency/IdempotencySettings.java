package com.altronixsoft.opp.platform.idempotency;

import java.time.DateTimeException;
import java.time.Duration;
import org.springframework.core.env.Environment;

/**
 * The resolved settings of one {@link Idempotent} annotation.
 *
 * @param required whether the header is mandatory
 * @param ttl how long the record is kept; at least one second
 */
record IdempotencySettings(boolean required, Duration ttl) {

    static IdempotencySettings of(Idempotent annotation, Environment environment) {
        String raw = environment.resolvePlaceholders(annotation.ttl());
        Duration ttl;
        try {
            ttl = Duration.parse(raw);
        } catch (DateTimeException e) {
            throw new IllegalStateException(
                    "@Idempotent ttl '" + annotation.ttl() + "' is not an ISO-8601 duration", e);
        }
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalStateException("@Idempotent ttl must be at least one second, got " + ttl);
        }
        return new IdempotencySettings(annotation.required(), ttl);
    }
}
