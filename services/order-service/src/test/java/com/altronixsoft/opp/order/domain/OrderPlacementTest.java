package com.altronixsoft.opp.order.domain;

import static com.altronixsoft.opp.order.domain.OrderFixtures.T0;
import static com.altronixsoft.opp.order.domain.OrderFixtures.api;
import static com.altronixsoft.opp.order.domain.OrderFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderPlacementTest {

    private static Order place(List<OrderItem> lines) {
        return Order.place(UUID.randomUUID(), "customer-1", lines, api(0));
    }

    @Test
    void aNewOrderIsPendingPaymentWithATotalFromItsLines() {
        UUID id = UUID.randomUUID();

        Order order = Order.place(id, "customer-1", OrderFixtures.twoLines(), api(0));

        assertThat(order.id()).isEqualTo(id);
        assertThat(order.customerId()).isEqualTo("customer-1");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.total()).isEqualTo(Money.of(2 * 1299 + 499, "EUR"));
        assertThat(order.items()).hasSize(2);
        assertThat(order.disputed()).isFalse();
        assertThat(order.cancelReason()).isNull();
        assertThat(order.createdAt()).isEqualTo(T0);
        assertThat(order.updatedAt()).isEqualTo(T0);
        assertThat(order.version()).as("not stored yet").isNull();
    }

    @Test
    void placingRegistersTheHistoryEntryAndTheEvent() {
        Order order = Order.place(UUID.randomUUID(), "customer-1", OrderFixtures.twoLines(), api(0));

        assertThat(order.history())
                .containsExactly(
                        new StatusChange(null, OrderStatus.PENDING_PAYMENT, null, TransitionSource.API, null, T0));
        assertThat(order.pullDomainEvents())
                .containsExactly(
                        new OrderDomainEvent.Placed(order.id(), "customer-1", Money.of(3097, "EUR"), 2, api(0)));
        assertThat(order.pullDomainEvents()).as("pulling clears the events").isEmpty();
    }

    @Test
    void theOrderKeepsItsOwnCopyOfTheLines() {
        List<OrderItem> lines = new ArrayList<>(OrderFixtures.twoLines());
        Order order = place(lines);

        lines.clear();

        assertThat(order.items()).hasSize(2);
        assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void oneToTwentyLinesAreAccepted() {
        assertThat(place(lines(1)).items()).hasSize(1);
        assertThat(place(lines(20)).items()).hasSize(Order.MAX_LINES);
    }

    @Test
    void noLinesOrMoreThanTwentyAreRejected() {
        assertThatThrownBy(() -> place(List.of())).isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> place(lines(21)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("1 to 20");
        assertThatThrownBy(() -> Order.requireLineCount(0)).isInstanceOf(InvalidOrderException.class);
        Order.requireLineCount(20);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10})
    void quantitiesFromOneToTenAreAccepted(int quantity) {
        assertThat(item("SKU", quantity, 100).quantity()).isEqualTo(quantity);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11, 100})
    void otherQuantitiesAreRejected(int quantity) {
        assertThatThrownBy(() -> item("SKU", quantity, 100))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("between 1 and 10");
    }

    @Test
    void aSkuMayAppearOnOneLineOnly() {
        assertThatThrownBy(() -> place(List.of(item("MUG", 1, 100), item("MUG", 2, 100))))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("MUG");
    }

    @Test
    void allLinesMustUseOneCurrency() {
        OrderItem usd = new OrderItem("USD-ITEM", "In dollars", 1, Money.of(500, "USD"));

        assertThatThrownBy(() -> place(List.of(item("EUR-ITEM", 1, 500), usd)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("one currency");
    }

    @Test
    void aCustomerIsRequired() {
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), " ", OrderFixtures.twoLines(), api(0)))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> Order.place(UUID.randomUUID(), null, OrderFixtures.twoLines(), api(0)))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void invalidLinesAreRejected() {
        Money free = Money.of(0, "EUR");

        assertThatThrownBy(() -> new OrderItem("SKU", "Name", 1, free)).isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> new OrderItem(" ", "Name", 1, Money.of(1, "EUR")))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> new OrderItem("S".repeat(65), "Name", 1, Money.of(1, "EUR")))
                .isInstanceOf(InvalidOrderException.class);
        assertThatThrownBy(() -> new OrderItem("SKU", " ", 1, Money.of(1, "EUR")))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void aLineTotalIsUnitPriceTimesQuantity() {
        assertThat(item("SKU", 3, 1299).lineTotal()).isEqualTo(Money.of(3897, "EUR"));
    }

    @Test
    void aStoredTotalThatDisagreesWithTheLinesIsCorruption() {
        assertThatThrownBy(() -> Order.restore(
                        UUID.randomUUID(),
                        "customer-1",
                        OrderFixtures.twoLines(),
                        Money.of(1, "EUR"),
                        OrderStatus.PAID,
                        null,
                        null,
                        false,
                        T0,
                        T0,
                        3L,
                        List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoringDoesNotRegisterEvents() {
        Order restored = Order.restore(
                UUID.randomUUID(),
                "customer-1",
                OrderFixtures.twoLines(),
                Money.of(3097, "EUR"),
                OrderStatus.PAID,
                null,
                null,
                true,
                T0,
                T0,
                7L,
                List.of());

        assertThat(restored.version()).isEqualTo(7L);
        assertThat(restored.status()).isEqualTo(OrderStatus.PAID);
        assertThat(restored.disputed()).isTrue();
        assertThat(restored.pullDomainEvents()).isEmpty();
    }

    private static List<OrderItem> lines(int count) {
        List<OrderItem> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            lines.add(item("SKU-" + i, 1, 100));
        }
        return lines;
    }
}
