# ADR-0002: Two services with choreography-based saga

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08
- Related: architecture §1, §3, §5, §6, §9

## Context

An order must be paid through Stripe, and both sides have independent lifecycles: the order (pending, paid,
cancelled, refunded) and the payment (PaymentIntent states, refunds, disputes). The two must stay consistent without a
distributed transaction, and the integration must survive crashes, duplicates, and late or out-of-order Stripe events.
The project should demonstrate realistic integration engineering without growing into a product.

## Decision

- Two services with separate databases: `order-service` (`orders_db`) and `payment-service` (`payments_db`).
- They communicate **only** through Kafka topics `order.events.v1` and `payment.events.v1`, keyed by `orderId`.
- The saga is **choreographed**: each service reacts to the other's events and owns its own state machine
  (architecture §5.1, §5.2). There is no central orchestrator.
- Compensation is explicit: a payment that succeeds after the order was cancelled triggers an automatic refund
  (`LATE_PAYMENT_AFTER_CANCEL`, §6.4); a failed refund is visible as `REFUND_FAILED` and can be retried by an admin.

## Alternatives considered

- **Single service / modular monolith** — simplest and transactional, but hides exactly the messaging and consistency
  problems this reference implementation exists to show.
- **Orchestrated saga** (dedicated orchestrator or workflow engine) — clearer flow visibility, but adds a third
  component and infrastructure for a saga with only two participants and a handful of steps.
- **Synchronous REST between services** — temporal coupling; a payment-service outage would block order placement,
  and retries would need their own idempotency story (see ADR-0013).

## Consequences

- Each service can be deployed, scaled and fail independently; order placement never waits for Stripe.
- The flow is distributed across two state machines, so it must be documented (§6) and covered by E2E tests (§14).
- Eventual consistency is visible to clients (an order is `PENDING_PAYMENT` until `PaymentSucceeded` arrives).
- Reliability depends on the outbox (ADR-0004), inbox (ADR-0005) and retry/DLT handling (ADR-0007).
