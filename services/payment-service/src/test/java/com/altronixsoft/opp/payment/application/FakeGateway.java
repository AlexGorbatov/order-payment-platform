package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/**
 * A scripted {@link PaymentGateway}. Each {@code createPaymentIntent} takes the next scripted step (a result or a failure);
 * the last step repeats. Every call is recorded together with whether a transaction was open at that moment.
 */
class FakeGateway implements PaymentGateway {

    private final FakeTransactions transactions;
    private final Deque<Supplier<GatewayPaymentIntent>> script = new ArrayDeque<>();
    private Supplier<GatewayPaymentIntent> last = () -> intent("pi_default", "requires_payment_method");

    final List<CreatePaymentIntentRequest> created = new ArrayList<>();
    final List<CancelPaymentIntentRequest> canceled = new ArrayList<>();
    final List<Boolean> createdInsideTransaction = new ArrayList<>();
    PaymentGatewayException cancelFailure;
    /** Runs during {@code createPaymentIntent}, to play something that happens while Stripe is being called. */
    Runnable duringCreate = () -> {};

    FakeGateway(FakeTransactions transactions) {
        this.transactions = transactions;
    }

    static GatewayPaymentIntent intent(String id, String status) {
        return new GatewayPaymentIntent(
                id,
                status,
                Money.of(3097, "EUR"),
                Instant.parse("2026-10-09T12:00:00Z"),
                id + "_secret_x",
                null,
                null,
                null);
    }

    static PaymentGatewayException failure(GatewayErrorClass errorClass, String code) {
        return new PaymentGatewayException(errorClass, "boom " + code, code, null, null, "req_1", false, null);
    }

    static PaymentGatewayException circuitOpen() {
        return new PaymentGatewayException(
                GatewayErrorClass.TRANSIENT, "circuit open", "circuit_open", null, null, null, true, null);
    }

    FakeGateway thenReturn(GatewayPaymentIntent intent) {
        script.add(() -> intent);
        return this;
    }

    FakeGateway thenThrow(PaymentGatewayException failure) {
        script.add(() -> {
            throw failure;
        });
        return this;
    }

    @Override
    public GatewayPaymentIntent createPaymentIntent(CreatePaymentIntentRequest request) {
        created.add(request);
        createdInsideTransaction.add(transactions.isOpen());
        duringCreate.run();
        Supplier<GatewayPaymentIntent> step = script.isEmpty() ? last : script.poll();
        last = step;
        return step.get();
    }

    @Override
    public GatewayPaymentIntent retrievePaymentIntent(String paymentIntentId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GatewayPaymentIntent cancelPaymentIntent(CancelPaymentIntentRequest request) {
        canceled.add(request);
        if (cancelFailure != null) {
            throw cancelFailure;
        }
        return intent(request.paymentIntentId(), "canceled");
    }

    @Override
    public GatewayRefund createRefund(CreateRefundRequest request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public GatewayPaymentIntent confirmPaymentIntentForTest(ConfirmPaymentIntentRequest request) {
        throw new UnsupportedOperationException();
    }
}
