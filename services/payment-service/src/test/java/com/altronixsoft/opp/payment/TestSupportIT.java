package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.OrderCreated;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code POST /api/v1/test-support/payments/by-order/{orderId}/confirm} (architecture §8.5): confirms the PaymentIntent
 * with a Stripe test payment method, in place of the browser, and changes nothing in the database.
 */
class TestSupportIT extends AbstractPaymentIT {

    private static final String CUSTOMER = TestKeycloak.CUSTOMER1_ID;
    private static final String PI = "pi_it_ts";

    private UUID payableOrder() {
        TestStripe.stubCreate(PI, 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        awaitPayment(orderId);
        job.run();
        return orderId;
    }

    private static String confirm(UUID orderId, String scenario) {
        return "/api/v1/test-support/payments/by-order/" + orderId + "/confirm?scenario=" + scenario;
    }

    private List<LoggedRequest> confirmations() {
        return TestStripe.requests().stream()
                .filter(r -> r.getUrl().endsWith("/confirm"))
                .toList();
    }

    @ParameterizedTest(name = "{0} confirms with {1}")
    @CsvSource({
        "success,pm_card_visa",
        "decline,pm_card_chargeDeclined",
        "insufficient_funds,pm_card_chargeDeclinedInsufficientFunds",
        "requires_3ds,pm_card_authenticationRequired",
        "dispute,pm_card_createDispute",
        "refund_fail,pm_card_refundFail"
    })
    @DisplayName("§8.5: every scenario confirms the PaymentIntent with its Stripe test payment method")
    void everyScenarioUsesItsTestPaymentMethod(String scenario, String paymentMethod) {
        UUID orderId = payableOrder();
        TestStripe.stubConfirm(PI, "processing", 3097, "EUR");

        Reply reply = post(confirm(orderId, scenario), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.json().path("scenario").stringValue()).isEqualTo(scenario);
        assertThat(reply.json().path("accepted").booleanValue()).isTrue();
        assertThat(confirmations()).hasSize(1);
        LoggedRequest sent = confirmations().getFirst();
        assertThat(sent.getUrl()).isEqualTo("/v1/payment_intents/" + PI + "/confirm");
        assertThat(TestStripe.form(sent)).containsEntry("payment_method", paymentMethod);
        assertThat(sent.getHeader("Idempotency-Key")).startsWith("pi-confirm:");
    }

    @Test
    @DisplayName("§8.5: the endpoint changes nothing in the database; the result arrives as a webhook")
    void theDatabaseIsUntouched() {
        UUID orderId = payableOrder();
        TestStripe.stubConfirm(PI, "succeeded", 3097, "EUR");
        String before = paymentSnapshot();

        Reply reply = post(confirm(orderId, "success"), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(paymentSnapshot()).isEqualTo(before);
        assertThat(statusOf(paymentIdOf(orderId).orElseThrow())).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    @DisplayName("a refused card is reported as accepted=false with the decline code, not as an error")
    void aDeclinedCardIsAResult() {
        UUID orderId = payableOrder();
        TestStripe.stubConfirmDeclined(PI);

        Reply reply = post(confirm(orderId, "decline"), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(202);
        assertThat(reply.json().path("accepted").booleanValue()).isFalse();
        assertThat(reply.json().path("errorCode").stringValue()).isEqualTo("card_declined");
        assertThat(reply.json().path("declineCode").stringValue()).isEqualTo("generic_decline");
    }

    @Test
    @DisplayName("an unknown scenario is 400 and Stripe is not called")
    void anUnknownScenario() {
        UUID orderId = payableOrder();

        Reply reply = post(confirm(orderId, "chargeback"), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.problemType()).isEqualTo("urn:problem-type:validation-failed");
        assertThat(confirmations()).isEmpty();
    }

    @Test
    @DisplayName("a missing scenario is 400")
    void aMissingScenario() {
        UUID orderId = payableOrder();

        Reply reply = post(
                "/api/v1/test-support/payments/by-order/" + orderId + "/confirm", TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("someone else's payment is 404, and an administrator cannot pay for the customer either")
    void onlyTheOwnerMayConfirm() {
        UUID orderId = payableOrder();

        assertThat(post(confirm(orderId, "success"), TestKeycloak.tokenOf("customer2"))
                        .status())
                .isEqualTo(404);
        assertThat(post(confirm(orderId, "success"), TestKeycloak.tokenOf("admin1"))
                        .status())
                .isEqualTo(403);
        assertThat(post(confirm(orderId, "success"), null).status()).isEqualTo(401);
        assertThat(confirmations()).isEmpty();
    }

    @Test
    @DisplayName("a payment without a PaymentIntent yet is 409 payment-not-confirmable")
    void aCreatedPaymentCannotBeConfirmed() {
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        awaitPayment(orderId);

        Reply reply = post(confirm(orderId, "success"), TestKeycloak.tokenOf("customer1"));

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.problemType()).isEqualTo("urn:problem-type:payment-not-confirmable");
        assertThat(confirmations()).isEmpty();
    }

    @Test
    @DisplayName("an order without a payment is 404")
    void aMissingPayment() {
        assertThat(post(confirm(UUID.randomUUID(), "success"), TestKeycloak.tokenOf("customer1"))
                        .status())
                .isEqualTo(404);
    }
}
