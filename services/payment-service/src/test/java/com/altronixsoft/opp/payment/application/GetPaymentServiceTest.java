package com.altronixsoft.opp.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentFixtures;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetPaymentServiceTest {

    private final InMemoryPayments payments = new InMemoryPayments();
    private final List<String> retrieved = new ArrayList<>();
    private String stripeStatus = "requires_payment_method";
    private PaymentGatewayException stripeFailure;
    private GetPaymentService service;

    private final PaymentGateway gateway = new FakeGateway(new FakeTransactions()) {
        @Override
        public GatewayPaymentIntent retrievePaymentIntent(String paymentIntentId) {
            retrieved.add(paymentIntentId);
            if (stripeFailure != null) {
                throw stripeFailure;
            }
            return new GatewayPaymentIntent(
                    paymentIntentId,
                    stripeStatus,
                    Money.of(3097, "EUR"),
                    Instant.parse("2026-10-09T12:00:00Z"),
                    "pi_secret_for_owner",
                    null,
                    null,
                    null);
        }
    };

    @BeforeEach
    void setUp() {
        service = new GetPaymentService(payments, gateway);
    }

    @Test
    void theOwnerGetsTheClientSecretWhileThePaymentIsPayable() {
        payments.add(PaymentFixtures.withPaymentIntent());

        PaymentView view = service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"));

        assertThat(view.clientSecret()).isEqualTo("pi_secret_for_owner");
        assertThat(view.payment().status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(retrieved).containsExactly(PaymentFixtures.PI);
    }

    @Test
    void theClientSecretIsAlsoGivenWhileAnActionIsRequired() {
        payments.add(PaymentFixtures.inStatus(PaymentStatus.REQUIRES_ACTION));
        stripeStatus = "requires_action";

        assertThat(service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"))
                        .clientSecret())
                .isEqualTo("pi_secret_for_owner");
    }

    @Test
    void noClientSecretOnceStripeSaysThePaymentMovedOn() {
        payments.add(PaymentFixtures.withPaymentIntent());
        stripeStatus = "processing";

        assertThat(service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"))
                        .clientSecret())
                .isNull();
    }

    @Test
    void anAdministratorSeesThePaymentButNeverTheClientSecret() {
        payments.add(PaymentFixtures.withPaymentIntent());

        PaymentView view = service.getByOrder(PaymentFixtures.ORDER_ID, Caller.admin("ops"));

        assertThat(view.clientSecret()).isNull();
        assertThat(view.payment().orderId()).isEqualTo(PaymentFixtures.ORDER_ID);
        assertThat(retrieved).isEmpty();
    }

    @Test
    void stripeIsNotAskedForPaymentsThatCannotBePaid() {
        for (PaymentStatus status : List.of(
                PaymentStatus.CREATED,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.PROCESSING,
                PaymentStatus.INITIATION_FAILED,
                PaymentStatus.CANCELED)) {
            InMemoryPayments only = new InMemoryPayments();
            only.add(PaymentFixtures.inStatus(status));
            GetPaymentService other = new GetPaymentService(only, gateway);

            assertThat(other.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1"))
                            .clientSecret())
                    .as(status.name())
                    .isNull();
        }
        assertThat(retrieved).isEmpty();
    }

    @Test
    void someoneElsesPaymentLooksLikeAMissingOne() {
        payments.add(PaymentFixtures.withPaymentIntent());

        assertThatThrownBy(() -> service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("somebody-else")))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThat(retrieved).isEmpty();
    }

    @Test
    void aMissingPaymentIsNotFound() {
        assertThatThrownBy(() -> service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1")))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void aStripeFailureWhileFetchingTheSecretIsPassedOn() {
        payments.add(PaymentFixtures.withPaymentIntent());
        stripeFailure = FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error");

        assertThatThrownBy(() -> service.getByOrder(PaymentFixtures.ORDER_ID, Caller.customer("customer-1")))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void theViewNeverPrintsTheClientSecret() {
        Payment payment = PaymentFixtures.withPaymentIntent();

        assertThat(new PaymentView(payment, "pi_secret_xyz").toString()).doesNotContain("pi_secret_xyz");
    }
}
