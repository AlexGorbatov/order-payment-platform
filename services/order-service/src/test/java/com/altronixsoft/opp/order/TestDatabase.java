package com.altronixsoft.opp.order;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL shared by the integration tests of the JVM. */
final class TestDatabase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }

    private TestDatabase() {}
}
