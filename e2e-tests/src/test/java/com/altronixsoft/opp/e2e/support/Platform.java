package com.altronixsoft.opp.e2e.support;

import com.altronixsoft.opp.contracts.Topics;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The whole platform of the scenarios, started once per JVM: PostgreSQL (the two databases of the compose setup),
 * Kafka, Keycloak with the realm of the local environment, the Stripe simulator, and the two services as processes of
 * the very jars the build produces. The services are processes, not Spring contexts of this JVM, so that the chaos
 * scenarios can really {@code kill -9} one.
 *
 * <p>The containers are removed by Testcontainers when the JVM ends; the service processes are stopped by a shutdown hook.
 */
public final class Platform {

    private static final int RETRY_TOPICS = 3;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);

    private static Platform instance;
    private static RuntimeException startFailure;

    public static synchronized Platform get() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (instance == null) {
            try {
                instance = new Platform();
            } catch (RuntimeException e) {
                startFailure = e;
                throw e;
            }
        }
        return instance;
    }

    private final PostgreSQLContainer postgres;
    private final KafkaContainer kafka;
    private final KeycloakContainer keycloak;
    private final StripeSimulator stripe;
    private final ServiceProcess orderService;
    // not final: the simulator, built first, delivers its webhooks to the service that is built after it
    private ServiceProcess paymentService;
    private final JdbcClient ordersDb;
    private final JdbcClient paymentsDb;
    private final Tokens tokens;

    private Platform() {
        postgres = new PostgreSQLContainer("postgres:17")
                .withEnv("ORDERS_DB_USER", "orders")
                .withEnv("ORDERS_DB_PASSWORD", "orders")
                .withEnv("PAYMENTS_DB_USER", "payments")
                .withEnv("PAYMENTS_DB_PASSWORD", "payments")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(
                                Path.of(requiredProperty("e2e.postgres-init"), "01-create-databases.sh"), 0755),
                        "/docker-entrypoint-initdb.d/01-create-databases.sh")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
        kafka = new KafkaContainer("apache/kafka:4.3.1");
        keycloak = new KeycloakContainer("keycloak/keycloak:26.7.5")
                .withRealmImportFile("realm-opp.json")
                .withEnv("KEYCLOAK_OPS_CLIENT_SECRET", "opp-ops-cli-e2e-secret");

        // The containers take the longest and do not depend on each other: start them side by side.
        CompletableFuture<Void> postgresUp = CompletableFuture.runAsync(postgres::start);
        CompletableFuture<Void> kafkaUp = CompletableFuture.runAsync(() -> {
            kafka.start();
            createTopics();
        });
        CompletableFuture<Void> keycloakUp = CompletableFuture.runAsync(keycloak::start);
        join(postgresUp, kafkaUp, keycloakUp);

        stripe = new StripeSimulator(() -> paymentService.baseUrl());
        tokens = new Tokens(keycloak.getAuthServerUrl() + "/realms/opp/protocol/openid-connect/token");
        ordersDb = database("orders_db", "orders");
        paymentsDb = database("payments_db", "payments");

        Path logs = Path.of(requiredProperty("e2e.logs"));
        orderService = new ServiceProcess(
                "order-service", Path.of(requiredProperty("e2e.order-service.jar")), logs, orderServiceProperties());
        paymentService = new ServiceProcess(
                "payment-service",
                Path.of(requiredProperty("e2e.payment-service.jar")),
                logs,
                paymentServiceProperties());
        join(
                CompletableFuture.runAsync(() -> orderService.start(START_TIMEOUT)),
                CompletableFuture.runAsync(() -> paymentService.start(START_TIMEOUT)));

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "e2e-platform-shutdown"));
    }

    // ---------------------------------------------------------------------------------------------- accessors

    public StripeSimulator stripe() {
        return stripe;
    }

    public ServiceProcess orderService() {
        return orderService;
    }

    public ServiceProcess paymentService() {
        return paymentService;
    }

    public JdbcClient ordersDb() {
        return ordersDb;
    }

    public JdbcClient paymentsDb() {
        return paymentsDb;
    }

    public Tokens tokens() {
        return tokens;
    }

    public String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    // ---------------------------------------------------------------------------------------------- chaos

    /** {@code docker pause}: the broker is still there, accepts connections and answers nothing. */
    public void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    public void unpauseKafka() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }

    public boolean isKafkaPaused() {
        return Boolean.TRUE.equals(kafka.getDockerClient()
                .inspectContainerCmd(kafka.getContainerId())
                .exec()
                .getState()
                .getPaused());
    }

    /** Puts the platform back in working order after a scenario that broke something. */
    public void heal() {
        if (isKafkaPaused()) {
            unpauseKafka();
        }
        List<CompletableFuture<Void>> restarts = new ArrayList<>();
        for (ServiceProcess service : List.of(orderService, paymentService)) {
            if (!service.isAlive()) {
                restarts.add(CompletableFuture.runAsync(() -> service.start(START_TIMEOUT)));
            }
        }
        join(restarts.toArray(CompletableFuture[]::new));
    }

    public static Duration startTimeout() {
        return START_TIMEOUT;
    }

    // ---------------------------------------------------------------------------------------------- configuration

    /** Properties both services share: the fast loops that make a scenario last seconds instead of minutes. */
    private Map<String, String> common(String database, String user) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put(
                "spring.datasource.url",
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database);
        p.put("spring.datasource.username", user);
        p.put("spring.datasource.password", user);
        p.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
        p.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", keycloak.getAuthServerUrl() + "/realms/opp");
        // outbox relay and retry topics: same logic as in production, shorter waits
        p.put("platform.outbox.relay.initial-delay", "0s");
        p.put("platform.outbox.relay.fixed-delay", "100ms");
        p.put("platform.outbox.relay.ack-timeout", "3s");
        p.put("platform.consumer.retry.blocking-interval", "100ms");
        p.put("platform.consumer.retry.topic-delays", "300ms,600ms,1s");
        p.put("platform.dead-letters.persister.metadata-refresh", "1s");
        return p;
    }

    private Map<String, String> orderServiceProperties() {
        Map<String, String> p = common("orders_db", "orders");
        // The timeout is the production default (30 minutes); scenarios make an order "old" in the database and let
        // this
        // job find it, instead of waiting.
        p.put("order.payment-timeout-job.initial-delay", "0s");
        p.put("order.payment-timeout-job.interval", "500ms");
        return p;
    }

    private Map<String, String> paymentServiceProperties() {
        Map<String, String> p = common("payments_db", "payments");
        p.put("stripe.api-key", "sk_test_e2e_not_a_real_key");
        p.put("stripe.api-base", stripe.baseUrl());
        p.put("stripe.webhook.signing-secrets", StripeSimulator.webhookSecret());
        p.put("stripe.read-timeout", "5s");
        p.put("platform.test-support.enabled", "true");
        for (String worker : List.of("initiation", "cancellation", "refund")) {
            p.put("payment." + worker + ".initial-delay", "0s");
            p.put("payment." + worker + ".interval", "300ms");
            // a claimed item becomes due again after this long when its worker died (the restart scenario)
            p.put("payment." + worker + ".lease", "6s");
            p.put("payment." + worker + ".retry-base-delay", "1s");
            p.put("payment." + worker + ".retry-jitter", "0");
        }
        p.put("payment.webhook-processor.initial-delay", "0s");
        p.put("payment.webhook-processor.interval", "200ms");
        p.put("payment.webhook-processor.retry-base-delay", "1s");
        p.put("payment.webhook-processor.retry-jitter", "0");
        // Reconciliation is driven by the scenario (POST /admin/reconciliation/run); a payment is "quiet" after 3 s.
        p.put("payment.reconciliation.enabled", "false");
        p.put("payment.reconciliation.stale-after", "3s");
        p.put("payment.reconciliation.rate-limit-per-second", "1000");
        return p;
    }

    // ---------------------------------------------------------------------------------------------- start / stop

    private void createTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : List.of(Topics.ORDER_EVENTS, Topics.PAYMENT_EVENTS)) {
            topics.add(new NewTopic(topic, 3, (short) 1));
            for (int i = 0; i < RETRY_TOPICS; i++) {
                topics.add(new NewTopic(topic + "-retry-" + i, 3, (short) 1));
            }
            topics.add(new NewTopic(topic + "-dlt", 3, (short) 1));
        }
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(topics).all().get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot create the topics", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private JdbcClient database(String name, String user) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name,
                user,
                user);
        return JdbcClient.create(dataSource);
    }

    private void shutdown() {
        try {
            orderService.stop();
            paymentService.stop();
            stripe.close();
        } catch (RuntimeException e) {
            // best effort on the way out
        }
    }

    private static void join(CompletableFuture<?>... futures) {
        try {
            CompletableFuture.allOf(futures).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof RuntimeException ? e.getCause() : e;
            throw cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "System property " + name + " is not set; run the scenarios through Maven: ./mvnw -Pe2e verify");
        }
        return value;
    }
}
