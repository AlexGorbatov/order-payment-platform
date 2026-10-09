package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.altronixsoft.opp.contracts.OrderCreated;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@code GET /api/v1/payments/by-order/{orderId}} (architecture §11) with real tokens from Keycloak, and the promise
 * about the client secret: given only to the paying customer, fetched from Stripe on demand, never stored, never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentApiIT extends AbstractPaymentIT {

    private static final String CUSTOMER = TestKeycloak.CUSTOMER1_ID;
    private static final String PI = "pi_it_api";

    private final List<Logger> raised = new java.util.ArrayList<>();
    private final List<Level> before = new java.util.ArrayList<>();

    @BeforeEach
    void logEverything() {
        for (String name : List.of("com.altronixsoft", "com.stripe", "org.springframework.web", "org.apache.hc")) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            raised.add(logger);
            before.add(logger.getLevel());
            logger.setLevel(Level.TRACE);
        }
    }

    @AfterEach
    void restoreLogging() {
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(before.get(i));
        }
    }

    /** An order of customer1 whose PaymentIntent exists and waits for a payment method. */
    private UUID payableOrder() {
        TestStripe.stubCreate(PI, 3097, "EUR");
        TestStripe.stubRetrieve(PI, "requires_payment_method", 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        awaitPayment(orderId);
        job.run();
        return orderId;
    }

    private static String path(UUID orderId) {
        return "/api/v1/payments/by-order/" + orderId;
    }

    // ------------------------------------------------------------------------------------------ who may see what

    @Test
    @DisplayName("the owner gets status, amount and the client secret while the payment is payable")
    void theOwnerSeesTheClientSecret() {
        UUID orderId = payableOrder();

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.header("Cache-Control")).contains("no-store");
        assertThat(reply.json().path("status").stringValue()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(reply.json().path("orderId").stringValue()).isEqualTo(orderId.toString());
        assertThat(reply.json().path("amount").path("amountMinor").longValue()).isEqualTo(3097);
        assertThat(reply.json().path("amount").path("currency").stringValue()).isEqualTo("EUR");
        assertThat(reply.json().path("clientSecret").stringValue()).isEqualTo(TestStripe.CLIENT_SECRET);
    }

    @Test
    @DisplayName("an administrator sees the payment but never the client secret, and Stripe is not asked for it")
    void anAdministratorNeverGetsTheClientSecret() {
        UUID orderId = payableOrder();
        int stripeCalls = TestStripe.requests().size();

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("admin1"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().path("status").stringValue()).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(reply.json().has("clientSecret")).isFalse();
        assertThat(reply.body()).doesNotContain(TestStripe.CLIENT_SECRET);
        assertThat(TestStripe.requests()).hasSize(stripeCalls);
    }

    @Test
    @DisplayName("someone else's payment is 404, exactly like a payment that does not exist")
    void aForeignPaymentIsNotFound() {
        UUID orderId = payableOrder();

        Reply foreign = get(path(orderId), TestKeycloak.tokenOf("customer2"));
        Reply missing = get(path(UUID.randomUUID()), TestKeycloak.tokenOf("customer2"));

        assertThat(foreign.status()).isEqualTo(404);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(foreign.problemType())
                .isEqualTo(missing.problemType())
                .isEqualTo("urn:problem-type:payment-not-found");
        assertThat(foreign.body()).doesNotContain(TestStripe.CLIENT_SECRET);
    }

    @Test
    @DisplayName(
            "an ops user (neither customer nor admin) is 403; no token, a wrong audience and a forged token are 401")
    void securityMatrix() {
        UUID orderId = payableOrder();

        assertThat(get(path(orderId), TestKeycloak.tokenOf("ops1")).status()).isEqualTo(403);
        assertThat(get(path(orderId), null).status()).isEqualTo(401);
        assertThat(get(path(orderId), TestKeycloak.tokenForAnotherAudience()).status())
                .isEqualTo(401);
        assertThat(get(path(orderId), TestKeycloak.signedByAnotherKey(CUSTOMER)).status())
                .isEqualTo(401);
        assertThat(get(path(orderId), TestKeycloak.unsigned(CUSTOMER)).status()).isEqualTo(401);
        assertThat(get(path(orderId), TestKeycloak.tamperedToClaimAdmin(TestKeycloak.tokenOf("customer2")))
                        .status())
                .isEqualTo(401);
        Reply unauthorized = get(path(orderId), null);
        assertThat(unauthorized.problemType()).isEqualTo("urn:problem-type:unauthorized");
        assertThat(unauthorized.header("WWW-Authenticate")).startsWith("Bearer");
    }

    @Test
    @DisplayName("a payment that is no longer payable shows its status and no client secret, without asking Stripe")
    void noClientSecretOnceThePaymentMovedOn() {
        UUID orderId = payableOrder();
        jdbc.sql("UPDATE payment SET status = 'PROCESSING' WHERE order_id = :orderId")
                .param("orderId", orderId)
                .update();
        int stripeCalls = TestStripe.requests().size();

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().path("status").stringValue()).isEqualTo("PROCESSING");
        assertThat(reply.json().has("clientSecret")).isFalse();
        assertThat(TestStripe.requests()).hasSize(stripeCalls);
    }

    @Test
    @DisplayName("a payment still CREATED shows its status and no client secret")
    void aCreatedPaymentHasNoSecretYet() {
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        awaitPayment(orderId);

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().path("status").stringValue()).isEqualTo("CREATED");
        assertThat(reply.json().has("clientSecret")).isFalse();
    }

    @Test
    @DisplayName("when Stripe cannot be asked for the secret the answer is 503 with Retry-After, and no secret")
    void aStripeOutageIsA503() {
        UUID orderId = payableOrder();
        TestStripe.reset();
        TestStripe.stubRetrieveFailing(500);

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.problemType()).isEqualTo("urn:problem-type:payment-provider-unavailable");
        assertThat(reply.header("Retry-After")).isNotNull();
        assertThat(reply.body()).doesNotContain("api_error").doesNotContain(TestStripe.CLIENT_SECRET);
    }

    @Test
    @DisplayName("a malformed order id is a 400 problem")
    void aMalformedOrderId() {
        Reply reply = get("/api/v1/payments/by-order/not-a-uuid", TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------------------------------ the secret

    @Test
    @DisplayName("the client secret is nowhere in the database and nowhere in the logs, even at TRACE")
    void theClientSecretIsNeverStoredOrLogged(CapturedOutput output) {
        UUID orderId = payableOrder();

        Reply reply = get(path(orderId), TestKeycloak.tokenOf("customer1"));
        assertThat(reply.json().path("clientSecret").stringValue()).isEqualTo(TestStripe.CLIENT_SECRET);
        // let the outbox relay and the rest of the service log whatever they want
        kafka.awaitPaymentEvent(orderId, "PaymentInitiated");

        assertThat(wholeDatabase()).doesNotContain(TestStripe.CLIENT_SECRET).doesNotContain("_secret_");
        assertThat(output.getAll())
                .as("the service log")
                .doesNotContain(TestStripe.CLIENT_SECRET)
                .doesNotContain("_secret_");
        assertThat(output.getAll())
                .as("the log is not empty: the test did capture the service")
                .contains(orderId.toString());
    }
}
