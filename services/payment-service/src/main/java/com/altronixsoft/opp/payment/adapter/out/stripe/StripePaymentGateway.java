package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.CancelPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.ConfirmPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreatePaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreateRefundRequest;
import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import com.altronixsoft.opp.payment.application.GatewayPaymentIntent;
import com.altronixsoft.opp.payment.application.GatewayRefund;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentGatewayException;
import com.altronixsoft.opp.payment.domain.Money;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link PaymentGateway} on the official Stripe SDK (architecture §8.2, ADR-0012).
 *
 * <ul>
 *   <li>Every mutating call carries an idempotency key derived from local ids ({@link IdempotencyKeys}); the SDK reuses
 *       it for its own network retries, so a retry can never create a second PaymentIntent or refund.
 *   <li>Failures are classified ({@link StripeErrorClassifier}) and surface as {@link PaymentGatewayException}. A
 *       circuit breaker counts only the {@code TRANSIENT} ones; while it is open calls fail fast without touching Stripe.
 *   <li>Each call is timed and counted ({@link StripeMetrics}) and logged with Stripe's {@code Request-Id}. Nothing
 *       secret is logged: not the API key, not a client secret, and messages pass through {@link Redactor}.
 * </ul>
 *
 * Stripe types do not leave this class.
 */
final class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    private final StripeClient client;
    private final CircuitBreaker circuitBreaker;
    private final StripeMetrics metrics;

    StripePaymentGateway(StripeClient client, CircuitBreaker circuitBreaker, StripeMetrics metrics) {
        this.client = client;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------------------------------------- operations

    @Override
    public GatewayPaymentIntent createPaymentIntent(CreatePaymentIntentRequest request) {
        return call("create_payment_intent", () -> {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(request.amount().amountMinor())
                    .setCurrency(request.amount().currencyCode().toLowerCase(Locale.ROOT))
                    // cards and other synchronous methods only: nothing here can redirect the customer away
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build())
                    .putMetadata("orderId", request.orderId().toString())
                    .putMetadata("paymentId", request.paymentId().toString())
                    .build();
            return client.v1()
                    .paymentIntents()
                    .create(params, options(IdempotencyKeys.paymentIntentCreate(request.paymentId())));
        });
    }

    @Override
    public GatewayPaymentIntent retrievePaymentIntent(String paymentIntentId) {
        return call(
                "retrieve_payment_intent", () -> client.v1().paymentIntents().retrieve(paymentIntentId));
    }

    @Override
    public GatewayPaymentIntent cancelPaymentIntent(CancelPaymentIntentRequest request) {
        return call("cancel_payment_intent", () -> {
            PaymentIntentCancelParams params = PaymentIntentCancelParams.builder()
                    .setCancellationReason(
                            switch (request.reason()) {
                                case REQUESTED_BY_CUSTOMER ->
                                    PaymentIntentCancelParams.CancellationReason.REQUESTED_BY_CUSTOMER;
                                case ABANDONED -> PaymentIntentCancelParams.CancellationReason.ABANDONED;
                            })
                    .build();
            return client.v1()
                    .paymentIntents()
                    .cancel(
                            request.paymentIntentId(),
                            params,
                            options(IdempotencyKeys.paymentIntentCancel(request.paymentId())));
        });
    }

    @Override
    public GatewayRefund createRefund(CreateRefundRequest request) {
        return callRefund("create_refund", () -> {
            RefundCreateParams params = RefundCreateParams.builder()
                    .setPaymentIntent(request.paymentIntentId())
                    .setAmount(request.amount().amountMinor())
                    .putMetadata("refundId", request.refundId().toString())
                    .putMetadata("paymentId", request.paymentId().toString())
                    .putMetadata("orderId", request.orderId().toString())
                    .build();
            return client.v1().refunds().create(params, options(IdempotencyKeys.refund(request.refundId())));
        });
    }

    @Override
    public GatewayPaymentIntent confirmPaymentIntentForTest(ConfirmPaymentIntentRequest request) {
        return call("confirm_payment_intent", () -> {
            PaymentIntentConfirmParams params = PaymentIntentConfirmParams.builder()
                    .setPaymentMethod(request.paymentMethodId())
                    .build();
            return client.v1()
                    .paymentIntents()
                    .confirm(
                            request.paymentIntentId(),
                            params,
                            options(IdempotencyKeys.paymentIntentConfirm(request.paymentId(), request.attemptId())));
        });
    }

    // ---------------------------------------------------------------------------------------------- plumbing

    /** One Stripe call that may throw the SDK's checked exception. */
    @FunctionalInterface
    private interface StripeCall<T extends StripeObject> {
        T run() throws StripeException;
    }

    private GatewayPaymentIntent call(String operation, StripeCall<PaymentIntent> call) {
        PaymentIntent intent = execute(operation, call);
        return toGateway(intent);
    }

    private GatewayRefund callRefund(String operation, StripeCall<Refund> call) {
        Refund refund = execute(operation, call);
        return toGateway(refund);
    }

    private <T extends StripeObject> T execute(String operation, StripeCall<T> call) {
        long started = metrics.start();
        try {
            T result = circuitBreaker.executeSupplier(() -> invoke(operation, call));
            metrics.success(operation, started);
            log.debug("Stripe {} succeeded, request-id {}", operation, requestIdOf(result));
            return result;
        } catch (CallNotPermittedException open) {
            PaymentGatewayException failure = new PaymentGatewayException(
                    GatewayErrorClass.TRANSIENT,
                    "Stripe " + operation + " not attempted: the circuit breaker is open",
                    "circuit_open",
                    null,
                    null,
                    null,
                    true,
                    open);
            metrics.failure(operation, failure.errorClass(), true, started);
            log.warn("Stripe {} not attempted: circuit breaker is open", operation);
            throw failure;
        } catch (PaymentGatewayException failure) {
            metrics.failure(operation, failure.errorClass(), false, started);
            logFailure(operation, failure);
            throw failure;
        }
    }

    /** Runs inside the circuit breaker: turns every failure into the port's exception so the breaker can tell them apart. */
    private <T extends StripeObject> T invoke(String operation, StripeCall<T> call) {
        try {
            return call.run();
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (StripeException | RuntimeException e) {
            throw StripeErrorClassifier.toGatewayException(operation, e);
        }
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static void logFailure(String operation, PaymentGatewayException failure) {
        // The message is already scrubbed of secrets; the cause (which may hold the raw response) is not logged.
        switch (failure.errorClass()) {
            case TRANSIENT ->
                log.warn(
                        "Stripe {} failed (transient): code={} status={} request-id={}: {}",
                        operation,
                        failure.code(),
                        failure.httpStatus(),
                        failure.providerRequestId(),
                        failure.getMessage());
            case PERMANENT ->
                log.info(
                        "Stripe {} rejected: code={} status={} request-id={}: {}",
                        operation,
                        failure.code(),
                        failure.httpStatus(),
                        failure.providerRequestId(),
                        failure.getMessage());
            case CONFIG, IDEMPOTENCY_MISMATCH ->
                log.error(
                        "Stripe {} failed ({}): code={} status={} request-id={}: {}",
                        operation,
                        failure.errorClass(),
                        failure.code(),
                        failure.httpStatus(),
                        failure.providerRequestId(),
                        failure.getMessage());
        }
    }

    private static String requestIdOf(StripeObject object) {
        return object == null || object.getLastResponse() == null
                ? null
                : object.getLastResponse().requestId();
    }

    // ---------------------------------------------------------------------------------------------- mapping

    private static GatewayPaymentIntent toGateway(PaymentIntent intent) {
        StripeError error = intent.getLastPaymentError();
        return new GatewayPaymentIntent(
                intent.getId(),
                intent.getStatus(),
                money(intent.getAmount(), intent.getCurrency()),
                instant(intent.getCreated()),
                intent.getClientSecret(),
                error == null ? null : error.getCode(),
                error == null ? null : error.getDeclineCode(),
                error == null ? null : Redactor.redact(error.getMessage()));
    }

    private static GatewayRefund toGateway(Refund refund) {
        return new GatewayRefund(
                refund.getId(),
                refund.getStatus(),
                money(refund.getAmount(), refund.getCurrency()),
                refund.getPaymentIntent(),
                instant(refund.getCreated()),
                refund.getFailureReason());
    }

    private static Money money(Long amountMinor, String currency) {
        return Money.of(amountMinor == null ? 0 : amountMinor, currency.toUpperCase(Locale.ROOT));
    }

    private static Instant instant(Long epochSeconds) {
        return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds);
    }
}
