package com.altronixsoft.opp.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** Events published by payment-service on {@link Topics#PAYMENT_EVENTS}; the aggregate is the payment. */
public sealed interface PaymentEvent extends DomainEvent
        permits PaymentInitiated,
                PaymentInitiationFailed,
                PaymentActionRequired,
                PaymentAttemptFailed,
                PaymentSucceeded,
                PaymentCanceled,
                PaymentRefunded,
                PaymentRefundFailed,
                PaymentDisputed {

    UUID paymentId();

    @Override
    @JsonIgnore
    default UUID aggregateId() {
        return paymentId();
    }
}
