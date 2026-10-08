package com.altronixsoft.opp.order.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The order aggregate and the state machine of architecture §5.1.
 *
 * <p>Every change goes through one of the command methods. A command first checks that it is allowed and only then
 * changes anything, so a rejected command leaves the order exactly as it was. An accepted command
 *
 * <ol>
 *   <li>moves the status (or sets the dispute flag),
 *   <li>appends an entry to the status history, and
 *   <li>registers one {@link OrderDomainEvent}, which the caller collects with {@link #pullDomainEvents()} and hands to
 *       the outbox in the same transaction that saves the order.
 * </ol>
 *
 * The aggregate is plain Java: no framework, no clock (time comes with the {@link Trigger}), no persistence. The
 * {@link #version()} is the optimistic-locking token owned by the persistence adapter; the domain only carries it.
 */
public final class Order {

    /** Largest number of lines in one order. */
    public static final int MAX_LINES = 20;

    private final UUID id;
    private final String customerId;
    private final List<OrderItem> items;
    private final Money total;
    private final Instant createdAt;
    private final List<StatusChange> history;
    private final List<OrderDomainEvent> domainEvents = new ArrayList<>();

    private OrderStatus status;
    private CancelReason cancelReason;
    private boolean disputed;
    private Instant updatedAt;
    private final Long version;

    private Order(
            UUID id,
            String customerId,
            List<OrderItem> items,
            Money total,
            OrderStatus status,
            CancelReason cancelReason,
            boolean disputed,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            List<StatusChange> history) {
        this.id = id;
        this.customerId = customerId;
        this.items = List.copyOf(items);
        this.total = total;
        this.status = status;
        this.cancelReason = cancelReason;
        this.disputed = disputed;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.history = new ArrayList<>(history);
    }

    // ------------------------------------------------------------------------------------------ creation

    /**
     * Places a new order in {@link OrderStatus#PENDING_PAYMENT}. The lines carry catalog prices.
     *
     * @throws InvalidOrderException no or too many lines, a duplicate SKU, mixed currencies, a bad quantity or price,
     *     or a missing customer
     */
    public static Order place(UUID id, String customerId, List<OrderItem> lines, Trigger trigger) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(trigger, "trigger");
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidOrderException("customerId must not be blank");
        }
        requireLineCount(lines.size());
        Money total = totalOf(lines);

        Order order = new Order(
                id,
                customerId,
                lines,
                total,
                OrderStatus.PENDING_PAYMENT,
                null,
                false,
                trigger.occurredAt(),
                trigger.occurredAt(),
                null,
                List.of());
        order.history.add(change(null, OrderStatus.PENDING_PAYMENT, null, trigger));
        order.domainEvents.add(new OrderDomainEvent.Placed(id, customerId, total, lines.size(), trigger));
        return order;
    }

    /**
     * Rebuilds an order from storage. Nothing is validated except that the stored total matches the lines; no event is
     * registered.
     */
    public static Order restore(
            UUID id,
            String customerId,
            List<OrderItem> items,
            Money total,
            OrderStatus status,
            CancelReason cancelReason,
            boolean disputed,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            List<StatusChange> history) {
        if (!totalOf(items).equals(total)) {
            throw new IllegalStateException("Stored total of order " + id + " does not match its lines");
        }
        return new Order(
                id, customerId, items, total, status, cancelReason, disputed, createdAt, updatedAt, version, history);
    }

    /** Checks the number of lines of a request, so callers can reject it before looking anything up. */
    public static void requireLineCount(int lines) {
        if (lines < 1 || lines > MAX_LINES) {
            throw new InvalidOrderException("an order needs 1 to " + MAX_LINES + " lines, got " + lines);
        }
    }

    private static Money totalOf(List<OrderItem> lines) {
        Set<String> skus = new HashSet<>();
        Money total = null;
        for (OrderItem line : lines) {
            if (!skus.add(line.sku())) {
                throw new InvalidOrderException("sku " + line.sku() + " appears on more than one line");
            }
            if (total == null) {
                total = line.lineTotal();
            } else if (!total.currency().equals(line.unitPrice().currency())) {
                throw new InvalidOrderException("all lines must use one currency, got " + total.currency() + " and "
                        + line.unitPrice().currency());
            } else {
                total = total.plus(line.lineTotal());
            }
        }
        if (total == null) {
            throw new InvalidOrderException("an order needs at least one line");
        }
        return total;
    }

    // ------------------------------------------------------------------------------------------ commands

    /** The payment succeeded: {@code PENDING_PAYMENT → PAID}. */
    public void markPaid(Trigger trigger) {
        move(OrderStatus.PAID, "mark paid", null, trigger);
        domainEvents.add(new OrderDomainEvent.Paid(id, trigger));
    }

    /** Cancels an order that has not been paid: {@code PENDING_PAYMENT → CANCELLED}. */
    public void cancel(CancelReason reason, Trigger trigger) {
        Objects.requireNonNull(reason, "reason");
        move(OrderStatus.CANCELLED, "be cancelled", reason.name(), trigger);
        this.cancelReason = reason;
        domainEvents.add(new OrderDomainEvent.Cancelled(id, reason, trigger));
    }

    /**
     * Requests a full refund of the order total: {@code PAID → REFUND_REQUESTED} (an administrator refunds,
     * {@link RefundReason#ADMIN}), {@code REFUND_FAILED → REFUND_REQUESTED} (the administrator retries, also
     * {@code ADMIN}), or {@code CANCELLED → REFUND_REQUESTED} (a payment succeeded after the cancellation, only
     * {@link RefundReason#LATE_PAYMENT_AFTER_CANCEL}).
     *
     * @param refundRequestId makes the refund idempotent downstream; one request, one Stripe refund
     */
    public void requestRefund(RefundReason reason, UUID refundRequestId, Trigger trigger) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(refundRequestId, "refundRequestId");
        String action = "request a refund (" + reason + ")";
        boolean lateCompensation = reason == RefundReason.LATE_PAYMENT_AFTER_CANCEL;
        if ((status == OrderStatus.CANCELLED) != lateCompensation) {
            throw new IllegalOrderTransitionException(id, status, action);
        }
        move(OrderStatus.REFUND_REQUESTED, action, reason.name(), trigger);
        domainEvents.add(new OrderDomainEvent.RefundRequested(id, refundRequestId, total, reason, trigger));
    }

    /** The refund succeeded: {@code REFUND_REQUESTED → REFUNDED}. */
    public void markRefunded(Trigger trigger) {
        move(OrderStatus.REFUNDED, "be marked refunded", null, trigger);
        domainEvents.add(new OrderDomainEvent.Refunded(id, trigger));
    }

    /** The refund failed: {@code REFUND_REQUESTED → REFUND_FAILED}; an administrator may retry. */
    public void markRefundFailed(String failureReason, Trigger trigger) {
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("failureReason must not be blank");
        }
        move(OrderStatus.REFUND_FAILED, "be marked refund failed", failureReason, trigger);
        domainEvents.add(new OrderDomainEvent.RefundFailed(id, failureReason, trigger));
    }

    /**
     * The payment was disputed: sets the {@code disputed} flag in any status. The status does not change. Repeating it
     * does nothing (no history entry, no event).
     */
    public void markDisputed(Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (disputed) {
            return;
        }
        disputed = true;
        updatedAt = trigger.occurredAt();
        history.add(change(status, status, "DISPUTED", trigger));
        domainEvents.add(new OrderDomainEvent.Disputed(id, trigger));
    }

    private void move(OrderStatus target, String action, String reason, Trigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (!status.canTransitionTo(target)) {
            throw new IllegalOrderTransitionException(id, status, action);
        }
        history.add(change(status, target, reason, trigger));
        status = target;
        updatedAt = trigger.occurredAt();
    }

    private static StatusChange change(OrderStatus from, OrderStatus to, String reason, Trigger trigger) {
        return new StatusChange(from, to, reason, trigger.source(), trigger.sourceEventId(), trigger.occurredAt());
    }

    // ------------------------------------------------------------------------------------------ events

    /** Returns the events registered since the last call and clears them. */
    public List<OrderDomainEvent> pullDomainEvents() {
        List<OrderDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    // ------------------------------------------------------------------------------------------ state

    public UUID id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public OrderStatus status() {
        return status;
    }

    public List<OrderItem> items() {
        return items;
    }

    public Money total() {
        return total;
    }

    /** Set when the order was cancelled; kept when a refund follows. */
    public CancelReason cancelReason() {
        return cancelReason;
    }

    public boolean disputed() {
        return disputed;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** Optimistic-locking token of the stored order; {@code null} until it has been saved. */
    public Long version() {
        return version;
    }

    /** The complete status history, oldest first. */
    public List<StatusChange> history() {
        return Collections.unmodifiableList(history);
    }
}
