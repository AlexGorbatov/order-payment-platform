package com.altronixsoft.opp.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Builders for orders in every status, reached only through legal commands. */
public final class OrderFixtures {

    public static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    private OrderFixtures() {}

    public static Trigger api(int minutesAfterStart) {
        return Trigger.api(T0.plusSeconds(60L * minutesAfterStart));
    }

    public static Trigger event(UUID eventId, int minutesAfterStart) {
        return Trigger.event(eventId, T0.plusSeconds(60L * minutesAfterStart));
    }

    public static OrderItem item(String sku, int quantity, long priceMinor) {
        return new OrderItem(sku, "Product " + sku, quantity, Money.of(priceMinor, "EUR"));
    }

    /** Two lines, total 2 × 1299 + 1 × 499 = 3097 EUR cents. */
    public static List<OrderItem> twoLines() {
        return List.of(item("MUG-JAVA", 2, 1299), item("STICKERS-PACK", 1, 499));
    }

    public static Order pending() {
        return Order.place(UUID.randomUUID(), "customer-1", twoLines(), api(0));
    }

    /** An order in {@code status}, with the events of the way there already pulled. */
    public static Order inStatus(OrderStatus status) {
        Order order = pending();
        switch (status) {
            case PENDING_PAYMENT -> {}
            case PAID -> order.markPaid(api(1));
            case CANCELLED -> order.cancel(CancelReason.CUSTOMER, api(1));
            case REFUND_REQUESTED -> {
                order.markPaid(api(1));
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(2));
            }
            case REFUNDED -> {
                order.markPaid(api(1));
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(2));
                order.markRefunded(api(3));
            }
            case REFUND_FAILED -> {
                order.markPaid(api(1));
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(2));
                order.markRefundFailed("card_closed", api(3));
            }
        }
        order.pullDomainEvents();
        return order;
    }
}
