package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import com.altronixsoft.opp.payment.application.PaymentGatewayException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.ApiKeyMissingException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The classification of architecture §8.2, on exceptions built the way the SDK builds them for each status. */
class StripeErrorClassifierTest {

    private static ApiException api(int status, String code) {
        return new ApiException("boom", "req_1", code, status, null);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of(
                        "connection failure",
                        new ApiConnectionException("could not connect", new IOException("refused")),
                        GatewayErrorClass.TRANSIENT),
                Arguments.of(
                        "read timeout",
                        new ApiConnectionException("timeout", new SocketTimeoutException("Read timed out")),
                        GatewayErrorClass.TRANSIENT),
                Arguments.of("500", api(500, null), GatewayErrorClass.TRANSIENT),
                Arguments.of("502", api(502, null), GatewayErrorClass.TRANSIENT),
                Arguments.of("503", api(503, null), GatewayErrorClass.TRANSIENT),
                Arguments.of("504", api(504, null), GatewayErrorClass.TRANSIENT),
                Arguments.of(
                        "429",
                        new RateLimitException("slow down", null, "req_1", "rate_limit", 429, null),
                        GatewayErrorClass.TRANSIENT),
                Arguments.of("429 as plain ApiException", api(429, null), GatewayErrorClass.TRANSIENT),
                Arguments.of("409 conflict, request in flight", api(409, "lock_timeout"), GatewayErrorClass.TRANSIENT),
                Arguments.of("409 key in use", api(409, "idempotency_key_in_use"), GatewayErrorClass.TRANSIENT),
                Arguments.of(
                        "key in use without a status",
                        new InvalidRequestException("in use", null, "req_1", "idempotency_key_in_use", 400, null),
                        GatewayErrorClass.TRANSIENT),
                Arguments.of("424 dependency failed", api(424, null), GatewayErrorClass.TRANSIENT),
                Arguments.of("unparseable 200", api(200, null), GatewayErrorClass.TRANSIENT),
                Arguments.of(
                        "idempotency mismatch",
                        new IdempotencyException(
                                "Keys for idempotent requests can only be used with the same parameters",
                                "req_1",
                                null,
                                400),
                        GatewayErrorClass.IDEMPOTENCY_MISMATCH),
                Arguments.of(
                        "401",
                        new AuthenticationException("Invalid API Key", "req_1", "api_key_invalid", 401),
                        GatewayErrorClass.CONFIG),
                Arguments.of(
                        "403",
                        new PermissionException("not allowed", "req_1", "permission_denied", 403),
                        GatewayErrorClass.CONFIG),
                Arguments.of("missing api key", new ApiKeyMissingException("no key"), GatewayErrorClass.CONFIG),
                Arguments.of(
                        "400 invalid request",
                        new InvalidRequestException(
                                "bad amount", "amount", "req_1", "parameter_invalid_integer", 400, null),
                        GatewayErrorClass.PERMANENT),
                Arguments.of(
                        "404 unknown object",
                        new InvalidRequestException(
                                "no such payment_intent", "intent", "req_1", "resource_missing", 404, null),
                        GatewayErrorClass.PERMANENT),
                Arguments.of(
                        "402 declined card",
                        new CardException(
                                "declined", "req_1", "card_declined", null, "generic_decline", null, 402, null),
                        GatewayErrorClass.PERMANENT),
                Arguments.of("418 other 4xx", api(418, null), GatewayErrorClass.PERMANENT),
                Arguments.of(
                        "an unexpected runtime failure",
                        new IllegalStateException("surprise"),
                        GatewayErrorClass.TRANSIENT));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("cases")
    void classifies(String name, Throwable failure, GatewayErrorClass expected) {
        assertThat(StripeErrorClassifier.classify(failure)).isEqualTo(expected);
    }

    @Test
    void theGatewayExceptionCarriesTheDiagnosticsAndTheCause() {
        StripeException failure = new CardException(
                "Your card was declined.",
                "req_abc",
                "card_declined",
                "number",
                "insufficient_funds",
                "ch_1",
                402,
                null);

        PaymentGatewayException wrapped = StripeErrorClassifier.toGatewayException("create_payment_intent", failure);

        assertThat(wrapped.errorClass()).isEqualTo(GatewayErrorClass.PERMANENT);
        assertThat(wrapped.code()).isEqualTo("card_declined");
        assertThat(wrapped.declineCode()).isEqualTo("insufficient_funds");
        assertThat(wrapped.httpStatus()).isEqualTo(402);
        assertThat(wrapped.providerRequestId()).isEqualTo("req_abc");
        assertThat(wrapped.circuitOpen()).isFalse();
        assertThat(wrapped.isTransient()).isFalse();
        assertThat(wrapped.getCause()).isSameAs(failure);
        assertThat(wrapped.getMessage())
                .contains("create_payment_intent")
                .contains("PERMANENT")
                .contains("card_declined")
                .contains("HTTP 402")
                .contains("req_abc");
    }

    @Test
    void secretsInAProviderMessageDoNotSurviveIntoTheMessage() {
        StripeException failure = new AuthenticationException(
                "Invalid API Key provided: sk_test_FAKEKEYVALUE1234", "req_1", "api_key_invalid", 401);

        PaymentGatewayException wrapped = StripeErrorClassifier.toGatewayException("retrieve_payment_intent", failure);

        assertThat(wrapped.getMessage()).contains("sk_test_***").doesNotContain("FAKEKEYVALUE1234");
        assertThat(wrapped.errorClass()).isEqualTo(GatewayErrorClass.CONFIG);
    }

    @Test
    void aFailureThatIsNotStripesGetsAnInternalCode() {
        PaymentGatewayException wrapped =
                StripeErrorClassifier.toGatewayException("create_refund", new IllegalStateException("bug"));

        assertThat(wrapped.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
        assertThat(wrapped.code()).isEqualTo("gateway_internal_error");
        assertThat(wrapped.httpStatus()).isNull();
        assertThat(wrapped.providerRequestId()).isNull();
    }
}
