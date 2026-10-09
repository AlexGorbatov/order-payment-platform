# Event Catalog

Contract of the messages exchanged between `order-service` and `payment-service` over Kafka — architecture §9,
[ADR-0011](adr/0011-json-events-explicit-versioning.md). The source of truth is code and schemas in
[`libs/event-contracts`](../libs/event-contracts): payload records, `EventCatalog`, and one JSON Schema (draft 2020-12)
per event in `src/main/resources/schemas/{EventType}.v{N}.json`. This page is checked against the catalog by
`EventCatalogTest`: every event must have a section here with its topic and producer.

## Topics

| Topic | Producer | Consumer group | Key | Partitions (local/prod) | Retention |
|---|---|---|---|---|---|
| `order.events.v1` | order-service | `payment-service` | `orderId` | 3 / 6 | 7 d |
| `payment.events.v1` | payment-service | `order-service` | `orderId` | 3 / 6 | 7 d |
| `<topic>-retry-*`, `<topic>-dlt` | Spring Kafka | DLT persister | `orderId` | as source | 14 d |

The key is always the **order id** (also for payment events), so everything about one order is ordered within a
partition. A consumer skips event types and versions it does not know (they are not dead-lettered).

## Envelope

Every record value is a JSON object (`EventEnvelope`, schema `envelope.v1.json`):

| Field | Type | Required | Semantics |
|---|---|---|---|
| `eventId` | string (UUIDv7) | yes | Unique id of the event; the consumer inbox deduplicates on it. |
| `eventType` | string | yes | Event name, e.g. `PaymentSucceeded`. Selects the payload class. |
| `eventVersion` | integer ≥ 1 | yes | Schema version of this event type. |
| `aggregateType` | `Order` \| `Payment` | yes | Aggregate that emitted the event. |
| `aggregateId` | string (UUID) | yes | `orderId` for order events, `paymentId` for payment events. |
| `partitionKey` | string | yes | Kafka key = `orderId`. |
| `occurredAt` | string (ISO-8601, UTC) | yes | When the event happened, microsecond precision, e.g. `2026-10-08T12:00:00Z`. |
| `producer` | `order-service` \| `payment-service` | yes | Emitting service. |
| `correlationId` | string (UUID) | yes | Shared by every event and request of one business flow. |
| `causationId` | string (UUID) | no | Event or request that directly triggered this event; omitted when there is none. |
| `payload` | object | yes | Event data, see below. |

Kafka headers (set by the messaging starter, not part of this module): `eventType`, `eventVersion`, `correlationId`,
`traceparent` (only when a tracer is configured; the services of v1.0.0 do not configure one, architecture §13).

```json
{
  "eventId": "0199e0a0-6c1b-7a4e-9f3d-2b8c5e7a1d04",
  "eventType": "PaymentSucceeded",
  "eventVersion": 1,
  "aggregateType": "Payment",
  "aggregateId": "0199e0a0-2222-7000-8000-000000000002",
  "partitionKey": "0199e0a0-1111-7000-8000-000000000001",
  "occurredAt": "2026-10-08T12:00:00Z",
  "producer": "payment-service",
  "correlationId": "0199e0a0-4444-7000-8000-000000000004",
  "causationId": "0199e0a0-5555-7000-8000-000000000005",
  "payload": {
    "paymentId": "0199e0a0-2222-7000-8000-000000000002",
    "orderId": "0199e0a0-1111-7000-8000-000000000001",
    "amountMinor": 4999,
    "currency": "EUR",
    "stripePaymentIntentId": "pi_3Q1xyzABC",
    "succeededAt": "2026-10-08T12:00:00Z"
  }
}
```

### Conventions

- **Money:** `amountMinor` is an integer in the minor unit of `currency` (cents for `EUR`), strictly positive.
  `currency` is an **upper-case** ISO-4217 code (`EUR`, not `eur`; the Stripe gateway converts). Both are validated in
  the payload constructors and in the schemas.
- **Ids** are canonical lower-case UUID strings; provider ids (`pi_…`, `re_…`, `dp_…`) are opaque non-empty strings.
- **Timestamps** are ISO-8601 strings in UTC, never numbers.
- **Optional fields** are omitted rather than sent as `null`.
- **Sanitized** fields (`errorCode`, `declineCode`, `failureReason`) carry machine-readable provider codes only —
  never card data, customer details or free-form provider messages.
- **Serialization** (`EventSerde`): unknown properties are ignored on read; there is no polymorphic typing — the
  payload class comes from `eventType` + `eventVersion` via `EventCatalog`.

## Order events (`order.events.v1`)

Producer: order-service · consumer: payment-service · aggregate: `Order`

### OrderCreated

An order was placed and awaits payment. Triggers payment creation.

| Field | Type | Semantics |
|---|---|---|
| `orderId` | UUID | The order. |
| `customerId` | string | Opaque id of the customer (JWT `sub`). |
| `amountMinor` | integer > 0 | Order total, minor units. |
| `currency` | string, ISO-4217 | Currency of the total. |
| `itemCount` | integer ≥ 1 | Number of order lines. |

### OrderCancelled

An order was cancelled; payment-service cancels the open PaymentIntent, or refunds a payment that already succeeded
(`LATE_PAYMENT_AFTER_CANCEL`, architecture §6.4).

| Field | Type | Semantics |
|---|---|---|
| `orderId` | UUID | The order. |
| `reason` | enum | `CUSTOMER` (customer cancelled), `TIMEOUT` (payment window expired), `PAYMENT_INITIATION_FAILED` (consequence of `PaymentInitiationFailed`), `PAYMENT_CANCELED` (consequence of `PaymentCanceled`). |

### OrderRefundRequested

A refund of a paid order was requested.

| Field | Type | Semantics |
|---|---|---|
| `orderId` | UUID | The order. |
| `refundRequestId` | UUID | Idempotency id: a duplicate event creates no second refund. |
| `amountMinor` | integer > 0 | Amount to refund, minor units. |
| `currency` | string, ISO-4217 | Currency of the refund. |
| `reason` | enum | `ADMIN` (admin-initiated), `LATE_PAYMENT_AFTER_CANCEL` (payment succeeded after the order was cancelled). |

## Payment events (`payment.events.v1`)

Producer: payment-service · consumer: order-service · aggregate: `Payment`

How order-service reacts to each of these events — including which ones are no-ops for an order that has moved on and
which ones are dead-lettered — is specified in architecture §6.7.

### PaymentInitiated

A PaymentIntent was created at the provider.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `stripePaymentIntentId` | string | Provider PaymentIntent id. |

### PaymentInitiationFailed

The PaymentIntent could not be created (permanent provider error, or the idempotency window elapsed). The order is
cancelled with `PAYMENT_INITIATION_FAILED`.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `errorCode` | string | Sanitized error code. |

### PaymentActionRequired

The customer must complete an additional step, e.g. 3-D Secure (architecture §6.3).

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |

### PaymentAttemptFailed

A payment attempt failed, e.g. a declined card. The payment stays open; the customer may retry.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `errorCode` | string | Sanitized error code, e.g. `card_declined`. |
| `declineCode` | string, optional | Sanitized decline code, e.g. `insufficient_funds`; omitted when the provider gave none. |

### PaymentSucceeded

The payment succeeded; the order becomes paid (or is refunded if it was already cancelled).

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `amountMinor` | integer > 0 | Captured amount, minor units. |
| `currency` | string, ISO-4217 | Currency of the payment. |
| `stripePaymentIntentId` | string | Provider PaymentIntent id. |
| `succeededAt` | ISO-8601 timestamp | When the provider reported success (distinct from the envelope `occurredAt`). |

### PaymentCanceled

The payment was cancelled at the provider; no money was captured. The order is cancelled with `PAYMENT_CANCELED`.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `reason` | string | Cancellation reason (provider value, e.g. `requested_by_customer`). |

### PaymentRefunded

A refund succeeded at the provider.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `refundRequestId` | UUID | Correlates to `OrderRefundRequested.refundRequestId`. |
| `stripeRefundId` | string | Provider refund id. |
| `amountMinor` | integer > 0 | Refunded amount, minor units (currency is that of the payment). |

### PaymentRefundFailed

A refund failed at the provider; the order becomes `REFUND_FAILED` and an administrator can retry.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `refundRequestId` | UUID | Correlates to `OrderRefundRequested.refundRequestId`. |
| `failureReason` | string | Sanitized failure reason. |

### PaymentDisputed

The customer opened a dispute (chargeback); the order is flagged as disputed.

| Field | Type | Semantics |
|---|---|---|
| `paymentId` | UUID | The payment. |
| `orderId` | UUID | The order. |
| `disputeId` | string | Provider dispute id. |
| `reason` | string | Dispute reason (provider value, e.g. `fraudulent`). |

## Versioning rules

Contracts evolve per **event type**: `eventVersion` and the schema file `{EventType}.v{N}.json` change together.
Topic names carry the major version of the whole topic contract (`order.events.v1`).

**Additive changes keep the version.** Adding an *optional* payload or envelope field is compatible in both
directions: old consumers ignore it (`FAIL_ON_UNKNOWN_PROPERTIES=false`), and new consumers must treat it as absent
when reading events from old producers. Schemas therefore never set `additionalProperties: false`. Update the
record, the schema's `properties` (not `required`) and this page together.

**Breaking changes introduce a new version**, published in parallel during migration:

- adding a *required* field, removing or renaming a field, changing a type, unit, format or meaning;
- tightening validation (e.g. a narrower pattern or range);
- adding a constant to an **enum** (`reason`): consumers deserialize enums strictly, so an unknown value would fail the
  event. Use a new version, or model the field as a string if it is expected to grow;
- changing `partitionKey` semantics or the aggregate of an event type.

Procedure for `PaymentSucceeded` v1 → v2:

1. Add the v2 payload record and schema `PaymentSucceeded.v2.json`; register it in `EventCatalog`.
2. Ship consumers that understand v2 first (v1 consumers skip v2 — an unknown version is an
   `UnknownEventTypeException`, not a dead letter).
3. Producers publish **both** versions (same topic, same key, same partition) until every consumer has migrated.
4. Stop publishing v1, keep the v1 schema and class until retained messages (7 d, DLT 14 d) have expired, then remove.

A change to the envelope itself (new required field, different semantics) is a new `envelope.v2.json` and is treated
like a topic-level major version (`*.events.v2`).

Rules that apply to every change:

- Event type names, field names and enum constants are never reused with a different meaning.
- Every schema change comes with updated tests (`EventSchemaTest` fails when a record and its schema diverge).
- Unknown event types and versions are skipped by consumers, never dead-lettered; malformed or invalid events of a
  known type are dead-lettered (retry topics → DLT, architecture §7.4).
