package com.altronixsoft.opp.platform.messaging.outbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL and Kafka shared by all integration tests of the JVM (started once, stopped by Testcontainers' reaper).
 *
 * <p>Spring caches test contexts and keeps them alive, so every context variant gets its <b>own database</b>: a relay
 * or listener of one cached context must never touch the rows of another.
 */
public final class TestInfrastructure {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    private static final Map<String, String> DATABASES = new ConcurrentHashMap<>();

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    private TestInfrastructure() {}

    /** JDBC URL of the database called {@code name}; it is created on first use. */
    public static String databaseUrl(String name) {
        return DATABASES.computeIfAbsent(name, TestInfrastructure::createDatabase);
    }

    private static String createDatabase(String name) {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create database " + name, e);
        }
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }
}
