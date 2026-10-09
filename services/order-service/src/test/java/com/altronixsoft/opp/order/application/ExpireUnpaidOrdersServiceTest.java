package com.altronixsoft.opp.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.order.domain.OrderFixtures;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.TransitionSource;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The payment timeout (architecture §6.4) against an in-memory repository. Orders are placed at T0 + n minutes. */
class ExpireUnpaidOrdersServiceTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final UUID RUN = UUID.fromString("0199e0a0-5555-7000-8000-000000000005");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final RecordingEventPublisher events = new RecordingEventPublisher();

    private ExpireUnpaidOrdersService serviceAt(int minutesAfterStart) {
        Clock clock = Clock.fixed(OrderFixtures.T0.plus(Duration.ofMinutes(minutesAfterStart)), ZoneOffset.UTC);
        return new ExpireUnpaidOrdersService(orders, events, clock);
    }

    private Order placedAt(int minutesAfterStart) {
        Order order = Order.place(
                UUID.randomUUID(), "customer-1", OrderFixtures.twoLines(), OrderFixtures.api(minutesAfterStart));
        order.pullDomainEvents();
        orders.stored.put(order.id(), order);
        return order;
    }

    @Test
    void cancelsOnlyOrdersOlderThanTheTimeout() {
        Order overdue = placedAt(0);
        Order fresh = placedAt(10);

        int cancelled = serviceAt(31).expireBatch(TIMEOUT, 100, RUN);

        assertThat(cancelled).isEqualTo(1);
        assertThat(overdue.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(overdue.cancelReason()).isEqualTo(CancelReason.TIMEOUT);
        assertThat(overdue.history().getLast().source()).isEqualTo(TransitionSource.JOB);
        assertThat(fresh.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.correlationId()).isEqualTo(RUN);
            assertThat(published.event())
                    .isInstanceOfSatisfying(
                            OrderDomainEvent.Cancelled.class,
                            event -> assertThat(event.reason()).isEqualTo(CancelReason.TIMEOUT));
        });
    }

    @Test
    void exactlyAtTheTimeoutTheOrderStillWaits() {
        Order order = placedAt(0);

        assertThat(serviceAt(30).expireBatch(TIMEOUT, 100, RUN)).isZero();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void ordersThatAreNoLongerPendingAreLeftAlone() {
        Order paid = OrderFixtures.inStatus(OrderStatus.PAID);
        orders.stored.put(paid.id(), paid);

        assertThat(serviceAt(120).expireBatch(TIMEOUT, 100, RUN)).isZero();
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void aBatchTakesTheOldestFirstAndAShortBatchMeansDone() {
        Order first = placedAt(0);
        Order second = placedAt(1);
        Order third = placedAt(2);
        ExpireUnpaidOrdersService service = serviceAt(60);

        assertThat(service.expireBatch(TIMEOUT, 2, RUN)).isEqualTo(2);
        assertThat(List.of(first.status(), second.status(), third.status()))
                .containsExactly(OrderStatus.CANCELLED, OrderStatus.CANCELLED, OrderStatus.PENDING_PAYMENT);
        assertThat(service.expireBatch(TIMEOUT, 2, RUN)).isEqualTo(1);
        assertThat(service.expireBatch(TIMEOUT, 2, RUN)).isZero();
    }

    @Test
    void rejectsNonsensicalArguments() {
        ExpireUnpaidOrdersService service = serviceAt(0);

        assertThatThrownBy(() -> service.expireBatch(Duration.ZERO, 1, RUN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expireBatch(TIMEOUT.negated(), 1, RUN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expireBatch(TIMEOUT, 0, RUN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.expireBatch(null, 1, RUN)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.expireBatch(TIMEOUT, 1, null)).isInstanceOf(NullPointerException.class);
    }
}
