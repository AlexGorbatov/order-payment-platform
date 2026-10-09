package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Use case: show the payment of an order (architecture §11). A customer sees only their own payment, an administrator
 * sees every payment; someone else's payment is reported exactly like a missing one.
 *
 * <p>The client secret is the key to completing the payment, so it is handled with care: it is only for the paying
 * customer (never an administrator), it is asked of Stripe at the moment of the request ({@code retrieve}) instead of being
 * stored, and it is only given out while Stripe says the PaymentIntent is waiting for a payment method or for an action.
 *
 * <p>Deliberately not {@code @Transactional}: the database read is its own short transaction and the Stripe call must not
 * be inside one (ADR-0008).
 */
@Service
public class GetPaymentService {

    /** Stripe statuses in which a client secret is still useful. */
    private static final Set<String> SECRET_STATUSES = Set.of("requires_payment_method", "requires_action");

    private final PaymentRepository payments;
    private final PaymentGateway gateway;

    public GetPaymentService(PaymentRepository payments, PaymentGateway gateway) {
        this.payments = payments;
        this.gateway = gateway;
    }

    /**
     * @throws PaymentNotFoundException there is no payment for the order, or it belongs to someone else
     * @throws PaymentGatewayException the client secret was needed but Stripe could not be asked
     */
    public PaymentView getByOrder(UUID orderId, Caller caller) {
        Payment payment = payments.findByOrderId(orderId)
                .filter(found -> caller.admin() || caller.owns(found))
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
        return new PaymentView(payment, clientSecretFor(payment, caller));
    }

    private String clientSecretFor(Payment payment, Caller caller) {
        boolean payable = payment.status() == PaymentStatus.REQUIRES_PAYMENT_METHOD
                || payment.status() == PaymentStatus.REQUIRES_ACTION;
        if (caller.admin() || !payable || payment.stripePaymentIntentId() == null) {
            return null;
        }
        GatewayPaymentIntent intent = gateway.retrievePaymentIntent(payment.stripePaymentIntentId());
        return SECRET_STATUSES.contains(intent.status()) ? intent.clientSecret() : null;
    }
}
