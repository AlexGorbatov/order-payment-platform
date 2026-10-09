package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.util.UUID;

/**
 * Test support: confirms the PaymentIntent of a payment with a Stripe test payment method, in place of the browser
 * (architecture §8.5). Not a Spring bean on its own: {@code TestSupportConfiguration} creates it only when
 * {@code platform.test-support.enabled=true}.
 *
 * <p>It changes nothing in the database. Stripe sends the outcome as a webhook, and that path updates the payment exactly as
 * for a real customer, so the demo and the tests exercise the real pipeline.
 */
public class ConfirmTestPaymentService {

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final IdGenerator ids;

    public ConfirmTestPaymentService(PaymentRepository payments, PaymentGateway gateway, IdGenerator ids) {
        this.payments = payments;
        this.gateway = gateway;
        this.ids = ids;
    }

    /**
     * What Stripe answered to the confirmation.
     *
     * @param stripeStatus the PaymentIntent status after the confirmation, or {@code null} if the card was refused
     * @param errorCode the provider's code if the card was refused ({@code card_declined}), else {@code null}
     * @param declineCode the card network's decline code, else {@code null}
     */
    public record Result(TestScenario scenario, String stripeStatus, String errorCode, String declineCode) {

        public boolean declined() {
            return errorCode != null;
        }
    }

    /**
     * @throws PaymentNotFoundException there is no payment for the order, or it is not the caller's
     * @throws PaymentNotConfirmableException the payment is not waiting for a payment method or an action
     * @throws PaymentGatewayException Stripe failed for a reason other than the card being refused
     */
    public Result confirm(UUID orderId, Caller caller, TestScenario scenario) {
        Payment payment = payments.findByOrderId(orderId)
                .filter(found -> !caller.admin() && caller.owns(found))
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
        boolean confirmable = payment.stripePaymentIntentId() != null
                && (payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD
                        || payment.status() == PaymentStatus.REQUIRES_ACTION);
        if (!confirmable) {
            throw new PaymentNotConfirmableException(orderId, payment.status());
        }
        try {
            GatewayPaymentIntent intent = gateway.confirmPaymentIntentForTest(new ConfirmPaymentIntentRequest(
                    payment.id(), payment.stripePaymentIntentId(), scenario.paymentMethodId(), ids.newId()));
            return new Result(scenario, intent.status(), null, null);
        } catch (PaymentGatewayException e) {
            if (e.errorClass() == GatewayErrorClass.PERMANENT && e.declineCode() != null) {
                // a refused card is the point of the decline scenarios: report it; the webhook follows
                return new Result(scenario, null, e.code(), e.declineCode());
            }
            throw e;
        }
    }
}
