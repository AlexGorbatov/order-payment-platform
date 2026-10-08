package com.altronixsoft.opp.contracts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** One representative envelope per event type, with fixed values (except event ids, which are real UUIDv7). */
final class Samples {

    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final UUID ORDER_ID = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    static final UUID PAYMENT_ID = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");
    static final UUID REFUND_REQUEST_ID = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");
    static final UUID CORRELATION_ID = UUID.fromString("0199e0a0-4444-7000-8000-000000000004");
    static final UUID CAUSATION_ID = UUID.fromString("0199e0a0-5555-7000-8000-000000000005");

    private Samples() {}

    static List<DomainEvent> payloads() {
        return List.of(
                new OrderCreated(ORDER_ID, "3f0c4c0e-9a55-4b57-bf1d-6a5f7d7d2a10", 4_999, "EUR", 2),
                new OrderCancelled(ORDER_ID, CancelReason.TIMEOUT),
                new OrderRefundRequested(ORDER_ID, REFUND_REQUEST_ID, 4_999, "EUR", RefundReason.ADMIN),
                new PaymentInitiated(PAYMENT_ID, ORDER_ID, "pi_3Q1xyzABC"),
                new PaymentInitiationFailed(PAYMENT_ID, ORDER_ID, "invalid_request"),
                new PaymentActionRequired(PAYMENT_ID, ORDER_ID),
                new PaymentAttemptFailed(PAYMENT_ID, ORDER_ID, "card_declined", "insufficient_funds"),
                new PaymentSucceeded(PAYMENT_ID, ORDER_ID, 4_999, "EUR", "pi_3Q1xyzABC", NOW),
                new PaymentCanceled(PAYMENT_ID, ORDER_ID, "requested_by_customer"),
                new PaymentRefunded(PAYMENT_ID, ORDER_ID, REFUND_REQUEST_ID, "re_3Q1xyzABC", 4_999),
                new PaymentRefundFailed(PAYMENT_ID, ORDER_ID, REFUND_REQUEST_ID, "expired_or_canceled_card"),
                new PaymentDisputed(PAYMENT_ID, ORDER_ID, "dp_1Q1xyzABC", "fraudulent"));
    }

    static List<EventEnvelope<? extends DomainEvent>> envelopes() {
        return payloads().stream().map(Samples::envelope).toList();
    }

    static EventEnvelope<? extends DomainEvent> envelope(DomainEvent payload) {
        return EventEnvelope.create(payload, CORRELATION_ID, CAUSATION_ID, CLOCK);
    }
}
