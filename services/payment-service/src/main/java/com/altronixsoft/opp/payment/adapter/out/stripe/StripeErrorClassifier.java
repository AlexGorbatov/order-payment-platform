package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import com.altronixsoft.opp.payment.application.PaymentGatewayException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiKeyMissingException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

/**
 * Classifies what went wrong with a call to Stripe (architecture §8.2).
 *
 * <ul>
 *   <li>{@code TRANSIENT}: no connection, a timeout, 5xx, 429, 409 (a request with the same idempotency key is still in
 *       flight), 424 (a dependency of Stripe failed), or a response that cannot be read. The request may or may not have
 *       been processed; repeating it with the same idempotency key is safe.
 *   <li>{@code PERMANENT}: Stripe understood the request and refused it: 400/404 (invalid parameters, unknown object), 402
 *       (the card was declined), other 4xx.
 *   <li>{@code CONFIG}: 401/403, a missing key: our credentials or permissions are wrong.
 *   <li>{@code IDEMPOTENCY_MISMATCH}: an idempotency key was reused with different parameters. A bug of ours.
 * </ul>
 *
 * The decision uses the HTTP status and the error {@code type}/{@code code} rather than the SDK's exception classes
 * alone, so a change in how the SDK picks its classes cannot silently change the retry behaviour.
 */
final class StripeErrorClassifier {

    private static final String TYPE_IDEMPOTENCY = "idempotency_error";
    private static final String CODE_KEY_IN_USE = "idempotency_key_in_use";

    private StripeErrorClassifier() {}

    static GatewayErrorClass classify(Throwable failure) {
        if (failure instanceof ApiKeyMissingException) {
            return GatewayErrorClass.CONFIG;
        }
        if (failure instanceof StripeException stripe) {
            return classify(stripe);
        }
        return GatewayErrorClass.TRANSIENT; // unknown: retry with backoff, the circuit breaker watches
    }

    private static GatewayErrorClass classify(StripeException e) {
        Integer status = httpStatusOf(e);
        String code = e.getCode();
        String type = e.getStripeError() == null ? null : e.getStripeError().getType();

        if (e instanceof ApiConnectionException) {
            return GatewayErrorClass.TRANSIENT;
        }
        if (status != null && status == 409 || CODE_KEY_IN_USE.equals(code)) {
            return GatewayErrorClass.TRANSIENT;
        }
        if (e instanceof IdempotencyException || TYPE_IDEMPOTENCY.equals(type)) {
            return GatewayErrorClass.IDEMPOTENCY_MISMATCH;
        }
        if (e instanceof AuthenticationException || e instanceof PermissionException) {
            return GatewayErrorClass.CONFIG;
        }
        if (e instanceof RateLimitException || status != null && status == 429) {
            return GatewayErrorClass.TRANSIENT;
        }
        if (e instanceof CardException || e instanceof InvalidRequestException) {
            return GatewayErrorClass.PERMANENT;
        }
        if (status == null || status >= 500 || status == 424 || status < 400) {
            return GatewayErrorClass.TRANSIENT;
        }
        return GatewayErrorClass.PERMANENT; // any other 4xx
    }

    /** The SDK reports {@code 0} for an exception that has no HTTP response (connection failures); that is no status. */
    private static Integer httpStatusOf(StripeException e) {
        Integer status = e.getStatusCode();
        return status == null || status <= 0 ? null : status;
    }

    /** Wraps a failure in the port's exception, with everything safe to log and store and nothing secret. */
    static PaymentGatewayException toGatewayException(String operation, Throwable failure) {
        GatewayErrorClass errorClass = classify(failure);
        String code = null;
        String declineCode = null;
        Integer status = null;
        String requestId = null;
        String providerMessage = failure.getMessage();
        if (failure instanceof StripeException stripe) {
            code = stripe.getCode();
            status = httpStatusOf(stripe);
            requestId = stripe.getRequestId();
            if (stripe instanceof CardException card) {
                declineCode = card.getDeclineCode();
            }
            if (stripe.getStripeError() != null && stripe.getStripeError().getMessage() != null) {
                providerMessage = stripe.getStripeError().getMessage();
            }
        } else if (failure instanceof ApiKeyMissingException) {
            code = "api_key_missing";
        } else {
            code = "gateway_internal_error";
        }
        String message = "Stripe " + operation + " failed [" + errorClass + (code == null ? "" : ", " + code)
                + (status == null ? "" : ", HTTP " + status) + (requestId == null ? "" : ", request-id " + requestId)
                + "]: " + Redactor.redact(providerMessage);
        return new PaymentGatewayException(errorClass, message, code, declineCode, status, requestId, false, failure);
    }
}
