package com.altronixsoft.opp.platform.idempotency;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL shared by all integration tests of the JVM. */
final class TestDatabase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    private TestDatabase() {}
}
