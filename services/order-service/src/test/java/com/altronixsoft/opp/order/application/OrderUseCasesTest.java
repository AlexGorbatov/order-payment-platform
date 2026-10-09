package com.altronixsoft.opp.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.IllegalOrderTransitionException;
import com.altronixsoft.opp.order.domain.Money;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.order.domain.OrderFixtures;
import com.altronixsoft.opp.order.domain.OrderItem;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.Product;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.TransitionSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Ownership and role rules of the read, cancel and refund use cases, against an in-memory repository. */
class OrderUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00.123456789Z");
    private static final UUID REFUND_ID = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");
    private static final Caller ALICE = Caller.customer("alice");
    private static final Caller BOB = Caller.customer("bob");
    private static final Caller ADMIN = Caller.admin("admin-1");

    private static final UUID CORRELATION_ID = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Order orderOf(String customer, int minutesAfterStart) {
        Order order = Order.place(
                UUID.randomUUID(),
                customer,
                List.of(new OrderItem("MUG-JAVA", "Mug", 1, Money.of(1299, "EUR"))),
                OrderFixtures.api(minutesAfterStart));
        order.pullDomainEvents();
        orders.stored.put(order.id(), order);
        return order;
    }

    @Test
    void aCallerNeedsANonBlankSubject() {
        assertThatThrownBy(() -> new Caller(" ", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Caller(null, false)).isInstanceOf(NullPointerException.class);
    }

    @Nested
    class Get {

        private final GetOrderService service = new GetOrderService(orders);

        @Test
        void theOwnerSeesTheOrder() {
            Order order = orderOf("alice", 0);

            assertThat(service.get(order.id(), ALICE)).isSameAs(order);
        }

        @Test
        void anAdministratorSeesAnyOrder() {
            Order order = orderOf("alice", 0);

            assertThat(service.get(order.id(), ADMIN)).isSameAs(order);
        }

        @Test
        void someoneElsesOrderLooksExactlyLikeAMissingOne() {
            Order order = orderOf("alice", 0);
            UUID missing = UUID.randomUUID();

            assertThatThrownBy(() -> service.get(order.id(), BOB))
                    .isExactlyInstanceOf(OrderNotFoundException.class)
                    .hasMessage("Order " + order.id() + " was not found");
            assertThatThrownBy(() -> service.get(missing, BOB))
                    .isExactlyInstanceOf(OrderNotFoundException.class)
                    .hasMessage("Order " + missing + " was not found");
        }
    }

    @Nested
    class ListOrders {

        private final ListOrdersService service = new ListOrdersService(orders);

        @Test
        void aCustomerGetsOnlyTheirOwnOrdersNewestFirst() {
            Order old = orderOf("alice", 0);
            Order recent = orderOf("alice", 5);
            orderOf("bob", 3);

            OrderPage page = service.list(ALICE, 0, 20);

            assertThat(page.content()).containsExactly(recent, old);
            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        void anAdministratorGetsEveryOrder() {
            orderOf("alice", 0);
            orderOf("bob", 1);

            assertThat(service.list(ADMIN, 0, 20).totalElements()).isEqualTo(2);
        }

        @Test
        void pagesAreCutAtTheRequestedSize() {
            IntStream.range(0, 5).forEach(i -> orderOf("alice", i));

            OrderPage second = service.list(ALICE, 1, 2);

            assertThat(second.content()).hasSize(2);
            assertThat(second.page()).isEqualTo(1);
            assertThat(second.size()).isEqualTo(2);
            assertThat(second.totalElements()).isEqualTo(5);
            assertThat(second.totalPages()).isEqualTo(3);
            assertThat(service.list(ALICE, 2, 2).content()).hasSize(1);
            assertThat(service.list(ALICE, 3, 2).content()).isEmpty();
        }

        @Test
        void anEmptyListHasNoPages() {
            assertThat(service.list(ALICE, 0, 20).totalPages()).isZero();
        }

        @Test
        void rejectsPagesAndSizesOutsideTheLimits() {
            assertThatThrownBy(() -> service.list(ALICE, -1, 20)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.list(ALICE, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.list(ALICE, 0, 101)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.list(ALICE, 0, 100).size()).isEqualTo(100);
        }
    }

    @Nested
    class Cancel {

        private final CancelOrderService service = new CancelOrderService(orders, events, clock);

        @Test
        void theOwnerCancelsAPendingOrder() {
            Order order = orderOf("alice", 0);

            Order cancelled = service.cancel(order.id(), ALICE, CORRELATION_ID);

            assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.CUSTOMER);
            assertThat(orders.saves).isEqualTo(1);
            assertThat(cancelled.history().getLast().source()).isEqualTo(TransitionSource.API);
            assertThat(cancelled.updatedAt()).isEqualTo(NOW.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            assertThat(events.published).singleElement().satisfies(published -> {
                assertThat(published.event()).isInstanceOf(OrderDomainEvent.Cancelled.class);
                assertThat(published.correlationId()).isEqualTo(CORRELATION_ID);
            });
        }

        @Test
        void someoneElsesOrderCannotBeCancelledNorFoundOut() {
            Order order = orderOf("alice", 0);

            assertThatThrownBy(() -> service.cancel(order.id(), BOB, CORRELATION_ID))
                    .isInstanceOf(OrderNotFoundException.class);
            assertThatThrownBy(() -> service.cancel(order.id(), ADMIN, CORRELATION_ID))
                    .isInstanceOf(OrderNotFoundException.class);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(orders.saves).isZero();
        }

        @Test
        void aPaidOrderCannotBeCancelledAndNothingIsSaved() {
            Order order = orderOf("alice", 0);
            order.markPaid(OrderFixtures.api(1));

            assertThatThrownBy(() -> service.cancel(order.id(), ALICE, CORRELATION_ID))
                    .isInstanceOf(IllegalOrderTransitionException.class);
            assertThat(orders.saves).isZero();
        }

        @Test
        void aConflictingSaveSurfaces() {
            Order order = orderOf("alice", 0);
            orders.failOnSave = new OrderConcurrentlyModifiedException(order.id());

            assertThatThrownBy(() -> service.cancel(order.id(), ALICE, CORRELATION_ID))
                    .isInstanceOf(OrderConcurrentlyModifiedException.class);
        }
    }

    @Nested
    class Refund {

        private final RefundOrderService service = new RefundOrderService(orders, events, () -> REFUND_ID, clock);

        private Order paid() {
            Order order = orderOf("alice", 0);
            order.markPaid(OrderFixtures.api(1));
            order.pullDomainEvents();
            return order;
        }

        @Test
        void anAdministratorRequestsAFullRefundOfAPaidOrder() {
            Order order = paid();

            Order refunded = service.refund(order.id(), ADMIN, CORRELATION_ID);

            assertThat(refunded.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
            assertThat(refunded.refundRequestId()).isEqualTo(REFUND_ID);
            assertThat(events.events())
                    .singleElement()
                    .isInstanceOfSatisfying(OrderDomainEvent.RefundRequested.class, event -> {
                        assertThat(event.refundRequestId()).isEqualTo(REFUND_ID);
                        assertThat(event.reason()).isEqualTo(RefundReason.ADMIN);
                        assertThat(event.amount()).isEqualTo(Money.of(1299, "EUR"));
                    });
        }

        @ParameterizedTest
        @ValueSource(strings = {"alice", "bob"})
        void customersCannotRefund(String customer) {
            Order order = paid();

            assertThatThrownBy(() -> service.refund(order.id(), Caller.customer(customer), CORRELATION_ID))
                    .isInstanceOf(OperationNotPermittedException.class);
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(orders.saves).isZero();
        }

        @Test
        void aMissingOrderIsNotFound() {
            assertThatThrownBy(() -> service.refund(UUID.randomUUID(), ADMIN, CORRELATION_ID))
                    .isInstanceOf(OrderNotFoundException.class);
        }

        @Test
        void anOrderThatIsNotPaidCannotBeRefunded() {
            Order order = orderOf("alice", 0);

            assertThatThrownBy(() -> service.refund(order.id(), ADMIN, CORRELATION_ID))
                    .isInstanceOf(IllegalOrderTransitionException.class);
            assertThat(orders.saves).isZero();
        }
    }

    @Nested
    class Products {

        @Test
        void listsWhatTheCatalogSells() {
            Product mug = new Product("MUG-JAVA", "Mug", Money.of(1299, "EUR"), true);
            ProductCatalog catalog = new ProductCatalog() {
                @Override
                public List<Product> findBySkus(Collection<String> skus) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<Product> findActive() {
                    return List.of(mug);
                }
            };

            assertThat(new ListProductsService(catalog).list()).containsExactly(mug);
        }
    }
}
