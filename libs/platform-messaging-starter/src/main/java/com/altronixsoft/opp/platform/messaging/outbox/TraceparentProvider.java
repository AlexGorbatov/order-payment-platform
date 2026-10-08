package com.altronixsoft.opp.platform.messaging.outbox;

import java.util.Optional;

/** Supplies the W3C {@code traceparent} of the caller's current span, if any. */
@FunctionalInterface
public interface TraceparentProvider {

    TraceparentProvider NONE = Optional::empty;

    Optional<String> current();
}
