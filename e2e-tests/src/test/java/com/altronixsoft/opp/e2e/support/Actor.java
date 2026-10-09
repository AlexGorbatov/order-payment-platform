package com.altronixsoft.opp.e2e.support;

/** The people of the realm {@code opp} (infra/keycloak/realm-opp.json) who call the APIs. */
public enum Actor {

    /** A customer: orders, own payments. */
    CUSTOMER("customer1", "00000000-0000-4000-8000-000000000001"),
    /** Another customer, to prove that foreign orders stay invisible. */
    OTHER_CUSTOMER("customer2", "00000000-0000-4000-8000-000000000002"),
    /** Back office: refunds, sees every order. */
    ADMIN("admin1", "00000000-0000-4000-8000-0000000000a1"),
    /** Operations: reconciliation and dead letters, no business endpoints. */
    OPS("ops1", "00000000-0000-4000-8000-0000000000f1");

    private final String username;
    private final String subject;

    Actor(String username, String subject) {
        this.username = username;
        this.subject = subject;
    }

    public String username() {
        return username;
    }

    /** The {@code sub} of the actor's tokens (the {@code customerId} of their orders). */
    public String subject() {
        return subject;
    }
}
