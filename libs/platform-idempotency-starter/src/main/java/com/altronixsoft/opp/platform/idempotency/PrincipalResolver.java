package com.altronixsoft.opp.platform.idempotency;

/**
 * Names the caller whose {@code Idempotency-Key} namespace a request belongs to: keys are private to a caller, so two
 * customers using the same key never collide.
 */
@FunctionalInterface
public interface PrincipalResolver {

    /** Principal used when nobody is authenticated. */
    String ANONYMOUS = "anonymous";

    /** The caller of the current request. Never {@code null}, at most 255 characters. */
    String resolve();
}
