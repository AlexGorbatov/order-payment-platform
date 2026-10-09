package com.altronixsoft.opp.payment;

import com.altronixsoft.opp.payment.application.PaymentRepository;
import com.altronixsoft.opp.payment.application.RefundRepository;
import com.altronixsoft.opp.payment.application.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The persistence adapters against a real PostgreSQL, through the ports only. All subclasses share one context. */
@SpringBootTest
abstract class AbstractPersistenceIT {

    @Autowired
    PaymentRepository payments;

    @Autowired
    RefundRepository refunds;

    @Autowired
    WebhookEventRepository webhookEvents;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestDatabase.POSTGRES::getPassword);
        // the Stripe adapter refuses to start without a test-mode key; nothing here calls Stripe
        registry.add("stripe.api-key", () -> "sk_test_persistence_it");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE payment_status_history, refund, payment, stripe_webhook_event")
                .update();
    }

    <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
