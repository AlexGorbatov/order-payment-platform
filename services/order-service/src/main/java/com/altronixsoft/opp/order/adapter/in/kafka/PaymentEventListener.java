package com.altronixsoft.opp.order.adapter.in.kafka;

import com.altronixsoft.opp.contracts.DomainEvent;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.PaymentActionRequired;
import com.altronixsoft.opp.contracts.PaymentAttemptFailed;
import com.altronixsoft.opp.contracts.PaymentCanceled;
import com.altronixsoft.opp.contracts.PaymentDisputed;
import com.altronixsoft.opp.contracts.PaymentEvent;
import com.altronixsoft.opp.contracts.PaymentInitiated;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.PaymentRefundFailed;
import com.altronixsoft.opp.contracts.PaymentRefunded;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.order.application.ApplyPaymentEventService;
import com.altronixsoft.opp.order.application.PaymentEventCommand;
import com.altronixsoft.opp.order.application.PaymentEventCommand.PaymentOutcome;
import com.altronixsoft.opp.order.application.PaymentEventResult;
import com.altronixsoft.opp.order.application.UnexpectedPaymentEventException;
import com.altronixsoft.opp.platform.messaging.consumer.EventEnvelopeReader;
import com.altronixsoft.opp.platform.messaging.consumer.NonRetryableEventException;
import com.altronixsoft.opp.platform.messaging.inbox.InboxGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payment.events.v1} as consumer group {@code order-service} (architecture §9.1). Translation only: the
 * rules are in {@link ApplyPaymentEventService}, which runs once per event id inside the inbox transaction.
 *
 * <ul>
 *   <li>Unknown event types are skipped by the {@link EventEnvelopeReader}; {@code PaymentInitiated} needs no reaction.
 *   <li>An event the order's state cannot explain becomes a {@link NonRetryableEventException}: straight to the DLT,
 *       persisted for an operator (architecture §7.4).
 *   <li>Anything else that fails (database down, an optimistic-lock conflict) is retried by the platform.
 * </ul>
 *
 * Metric {@code order.payment.events{type, outcome}}: {@code APPLIED}, {@code COMPENSATED}, {@code IGNORED},
 * {@code DUPLICATE} (already processed, caught by the inbox) or {@code REJECTED} (dead-lettered).
 */
@Component
class PaymentEventListener {

    static final String CONSUMER_GROUP = "order-service";
    static final String METRIC = "order.payment.events";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final EventEnvelopeReader reader;
    private final InboxGuard inbox;
    private final ApplyPaymentEventService service;
    private final MeterRegistry meters;

    PaymentEventListener(
            EventEnvelopeReader reader, InboxGuard inbox, ApplyPaymentEventService service, MeterRegistry meters) {
        this.reader = reader;
        this.inbox = inbox;
        this.service = service;
        this.meters = meters;
    }

    @KafkaListener(id = "payment-events", topics = Topics.PAYMENT_EVENTS, groupId = CONSUMER_GROUP)
    void on(ConsumerRecord<String, String> record) {
        reader.read(record).ifPresent(this::handle);
    }

    private void handle(EventEnvelope<? extends DomainEvent> envelope) {
        if (!(envelope.payload() instanceof PaymentEvent payment)) {
            throw new NonRetryableEventException(
                    "Event " + envelope.eventId() + " of type " + envelope.eventType() + " is not a payment event");
        }
        Optional<PaymentOutcome> outcome = toOutcome(payment);
        if (outcome.isEmpty()) {
            log.debug("No reaction to {} {}", envelope.eventType(), envelope.eventId());
            return;
        }
        PaymentEventCommand command =
                new PaymentEventCommand(envelope.eventId(), payment.orderId(), envelope.correlationId(), outcome.get());
        MDC.put("correlationId", envelope.correlationId().toString());
        MDC.put("orderId", payment.orderId().toString());
        MDC.put("paymentId", payment.paymentId().toString());
        try {
            AtomicReference<PaymentEventResult> result = new AtomicReference<>();
            boolean executed =
                    inbox.executeOnce(CONSUMER_GROUP, envelope.eventId(), () -> result.set(service.apply(command)));
            count(envelope, executed ? result.get().name() : "DUPLICATE");
        } catch (UnexpectedPaymentEventException e) {
            count(envelope, "REJECTED");
            throw new NonRetryableEventException(e.getMessage(), e);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("orderId");
            MDC.remove("paymentId");
        }
    }

    private void count(EventEnvelope<?> envelope, String outcome) {
        meters.counter(METRIC, "type", envelope.eventType(), "outcome", outcome).increment();
    }

    static Optional<PaymentOutcome> toOutcome(PaymentEvent event) {
        return switch (event) {
            case PaymentSucceeded ignored -> Optional.of(new PaymentOutcome.Succeeded());
            case PaymentInitiationFailed failed -> Optional.of(new PaymentOutcome.InitiationFailed(failed.errorCode()));
            case PaymentCanceled canceled -> Optional.of(new PaymentOutcome.Canceled(canceled.reason()));
            case PaymentAttemptFailed failed ->
                Optional.of(new PaymentOutcome.AttemptFailed(failed.errorCode(), failed.declineCode()));
            case PaymentActionRequired ignored -> Optional.of(new PaymentOutcome.ActionRequired());
            case PaymentRefunded refunded -> Optional.of(new PaymentOutcome.Refunded(refunded.refundRequestId()));
            case PaymentRefundFailed failed ->
                Optional.of(new PaymentOutcome.RefundFailed(failed.refundRequestId(), failed.failureReason()));
            case PaymentDisputed disputed -> Optional.of(new PaymentOutcome.Disputed(disputed.disputeId()));
            case PaymentInitiated ignored -> Optional.empty();
        };
    }
}
