package com.altronixsoft.opp.payment.application;

import java.util.Objects;

/**
 * A call to the payment provider failed. Carries only what is safe to log and to store in
 * {@code payment.last_error_message}: a classification, the provider's error code, the provider's request id and a
 * message scrubbed of secrets. The provider's own exception is the {@code cause}.
 */
public class PaymentGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final GatewayErrorClass errorClass;
    private final String code;
    private final String declineCode;
    private final Integer httpStatus;
    private final String providerRequestId;
    private final boolean circuitOpen;

    public PaymentGatewayException(
            GatewayErrorClass errorClass,
            String message,
            String code,
            String declineCode,
            Integer httpStatus,
            String providerRequestId,
            boolean circuitOpen,
            Throwable cause) {
        super(message, cause);
        this.errorClass = Objects.requireNonNull(errorClass, "errorClass");
        this.code = code;
        this.declineCode = declineCode;
        this.httpStatus = httpStatus;
        this.providerRequestId = providerRequestId;
        this.circuitOpen = circuitOpen;
    }

    public GatewayErrorClass errorClass() {
        return errorClass;
    }

    /** The provider's error code (for example {@code card_declined}), or a code of ours; may be {@code null}. */
    public String code() {
        return code;
    }

    /** The card network's decline code, if the provider sent one. */
    public String declineCode() {
        return declineCode;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** The provider's {@code Request-Id}, to quote when asking its support; {@code null} if no response arrived. */
    public String providerRequestId() {
        return providerRequestId;
    }

    /** The call was not made at all because the circuit breaker is open. */
    public boolean circuitOpen() {
        return circuitOpen;
    }

    public boolean isTransient() {
        return errorClass == GatewayErrorClass.TRANSIENT;
    }
}
