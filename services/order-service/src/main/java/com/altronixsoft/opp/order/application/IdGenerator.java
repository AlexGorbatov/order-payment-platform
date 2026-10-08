package com.altronixsoft.opp.order.application;

import java.util.UUID;

/** Port: identifiers for new aggregates (time-ordered UUIDs in production, fixed ones in tests). */
public interface IdGenerator {

    UUID newId();
}
