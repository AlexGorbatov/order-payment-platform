package com.altronixsoft.opp.payment.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.payment.application.OrderEventCommand.Outcome;
import com.altronixsoft.opp.payment.domain.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderEventListenerTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");

    @Test
    void orderCreatedBecomesAPaymentToCreate() {
        var outcome = OrderEventListener.toOutcome(new OrderCreated(ORDER, "customer-1", 3097, "EUR", 2));

        assertThat(outcome).contains(new Outcome.Created("customer-1", Money.of(3097, "EUR")));
    }

    @Test
    void orderCancelledBecomesACancellationWithItsReason() {
        var outcome = OrderEventListener.toOutcome(new OrderCancelled(ORDER, CancelReason.TIMEOUT));

        assertThat(outcome).contains(new Outcome.Cancelled("TIMEOUT"));
    }

    @Test
    void orderRefundRequestedBecomesARefundWithItsRequestId() {
        UUID requestId = UUID.randomUUID();

        var outcome = OrderEventListener.toOutcome(
                new OrderRefundRequested(ORDER, requestId, 3097, "EUR", RefundReason.ADMIN));

        assertThat(outcome).contains(new Outcome.RefundRequested(requestId, Money.of(3097, "EUR"), "ADMIN"));
    }
}
