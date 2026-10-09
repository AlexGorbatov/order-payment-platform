package com.altronixsoft.opp.e2e;

import com.altronixsoft.opp.e2e.support.Invariants;
import com.altronixsoft.opp.e2e.support.Kafka;
import com.altronixsoft.opp.e2e.support.LogsOnFailure;
import com.altronixsoft.opp.e2e.support.Platform;
import com.altronixsoft.opp.e2e.support.StripeSimulator;
import com.altronixsoft.opp.e2e.support.TestClient;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Base of every scenario: the shared {@link Platform} (started by the first class that needs it), a client, and the
 * global invariants of architecture §14 asserted after each test.
 */
@ExtendWith(LogsOnFailure.class)
abstract class E2eTest {

    static Platform platform;

    final StripeSimulator stripe = platform().stripe();
    final TestClient client = new TestClient(platform());
    final Kafka kafka = new Kafka(platform());
    Instant started;

    @BeforeAll
    static void startPlatform() {
        platform = platform();
    }

    static Platform platform() {
        return Platform.get();
    }

    @BeforeEach
    void beginScenario() {
        platform.heal();
        stripe.reset();
        client.forgetOrders();
        started = Instant.now().minusSeconds(2);
    }

    @AfterEach
    void endScenario() {
        // A scenario may have broken something on purpose; the invariants are about the platform when it is whole
        // again.
        platform.heal();
        stripe.webhooks(StripeSimulator.WebhookMode.AUTO);
        Invariants.assertHold(platform, client.orders(), client.adopted(), started);
    }
}
