package com.altronixsoft.opp.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.PaymentFixtures;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmTestPaymentServiceTest {

    private static final UUID KEY_ID = UUID.fromString("0199e0a0-5555-7000-8000-000000000001");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final List<ConfirmPaymentIntentRequest> confirmed = new ArrayList<>();
    private PaymentGatewayException failure;

    private final PaymentGateway gateway = new FakeGateway(new FakeTransactions()) {
        @Override
        public GatewayPaymentIntent confirmPaymentIntentForTest(ConfirmPaymentIntentRequest request) {
            confirmed.add(request);
            if (failure != null) {
                throw failure;
            }
            return new GatewayPaymentIntent(
                    request.paymentIntentId(),
                    "succeeded",
                    Money.of(3097, "EUR"),
                    Instant.parse("2026-10-09T12:00:00Z"),
                    "secret",
                    null,
                    null,
                    null);
        }
    };

    private final ConfirmTestPaymentService service = new ConfirmTestPaymentService(payments, gateway, () -> KEY_ID);

    @Test
    void confirmsTheIntentWithTheTestPaymentMethodOfTheScenario() {
        payments.add(PaymentFixtures.withPaymentIntent());

        var result = service.confirm(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"), TestScenario.SUCCESS);

        assertThat(confirmed).hasSize(1);
        assertThat(confirmed.getFirst().paymentIntentId()).isEqualTo(PaymentFixtures.PI);
        assertThat(confirmed.getFirst().paymentMethodId()).isEqualTo("pm_card_visa");
        assertThat(result.declined()).isFalse();
        assertThat(result.stripeStatus()).isEqualTo("succeeded");
    }

    @Test
    void changesNothingInTheDatabase() {
        payments.add(PaymentFixtures.withPaymentIntent());

        service.confirm(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"), TestScenario.SUCCESS);

        assertThat(payments.saves).isZero();
        assertThat(payments.all().getFirst().status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
    }

    @Test
    void aRefusedCardIsAResultNotAnError() {
        payments.add(PaymentFixtures.withPaymentIntent());
        failure = new PaymentGatewayException(
                GatewayErrorClass.PERMANENT, "declined", "card_declined", "generic_decline", 402, "req_1", false, null);

        var result = service.confirm(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"), TestScenario.DECLINE);

        assertThat(result.declined()).isTrue();
        assertThat(result.errorCode()).isEqualTo("card_declined");
        assertThat(result.declineCode()).isEqualTo("generic_decline");
        assertThat(result.stripeStatus()).isNull();
    }

    @Test
    void otherStripeFailuresArePassedOn() {
        payments.add(PaymentFixtures.withPaymentIntent());
        failure = FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_error");

        assertThatThrownBy(() ->
                        service.confirm(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"), TestScenario.SUCCESS))
                .isSameAs(failure);
    }

    @Test
    void aPaymentWithoutAWaitingIntentCannotBeConfirmed() {
        for (PaymentStatus status : List.of(
                PaymentStatus.CREATED, PaymentStatus.PROCESSING, PaymentStatus.SUCCEEDED, PaymentStatus.CANCELED)) {
            InMemoryPayments only = new InMemoryPayments();
            only.add(PaymentFixtures.inStatus(status));
            var other = new ConfirmTestPaymentService(only, gateway, () -> KEY_ID);

            assertThatThrownBy(() -> other.confirm(
                            PaymentFixtures.ORDER_ID, Caller.customer("customer-1"), TestScenario.SUCCESS))
                    .as(status.name())
                    .isInstanceOf(PaymentNotConfirmableException.class);
        }
        assertThat(confirmed).isEmpty();
    }

    @Test
    void onlyTheOwnerMayConfirm() {
        payments.add(PaymentFixtures.withPaymentIntent());

        assertThatThrownBy(() -> service.confirm(
                        PaymentFixtures.ORDER_ID, Caller.customer("somebody-else"), TestScenario.SUCCESS))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(PaymentFixtures.ORDER_ID, Caller.admin("ops"), TestScenario.SUCCESS))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThat(confirmed).isEmpty();
    }

    @Test
    void everyScenarioHasItsOwnParameterAndPaymentMethod() {
        assertThat(TestScenario.values()).extracting(TestScenario::parameter).doesNotHaveDuplicates();
        assertThat(TestScenario.values())
                .extracting(TestScenario::paymentMethodId)
                .doesNotHaveDuplicates();
        assertThat(TestScenario.values())
                .extracting(TestScenario::parameter)
                .containsExactlyInAnyOrder(
                        "success", "decline", "insufficient_funds", "requires_3ds", "dispute", "refund_fail");
        for (TestScenario scenario : TestScenario.values()) {
            assertThat(TestScenario.fromParameter(scenario.parameter())).contains(scenario);
            assertThat(scenario.paymentMethodId()).startsWith("pm_card_");
        }
        assertThat(TestScenario.fromParameter("SUCCESS")).contains(TestScenario.SUCCESS);
        assertThat(TestScenario.fromParameter("nope")).isEmpty();
        assertThat(TestScenario.fromParameter(null)).isEmpty();
    }
}
