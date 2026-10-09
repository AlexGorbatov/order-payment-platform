# ADR-0006: Multi-layer idempotency

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08 (HTTP layer details added with the HTTP implementation)
- Related: architecture §7.3, §8.2, §10, §11, §15 (F04, F05, F09, F16, F17, F21, F22), ADR-0013

## Context

Duplicates can enter the system at every boundary: clients retry HTTP requests, the platform retries Stripe calls,
Stripe redelivers webhooks, Kafka redelivers events, and reconciliation can race with webhooks. Quality goal 1 — at
most one successful PaymentIntent per order — must hold regardless of where the duplicate originates.

## Decision

Apply idempotency explicitly at each boundary:

| Boundary | Mechanism | Key |
|---|---|---|
| Client → API | `Idempotency-Key` header + request hash, stored in `idempotency_record` (24 h) | (principal, key) |
| Service → Stripe | Stripe `Idempotency-Key` derived from local IDs | `pi-create:{paymentId}`, `pi-cancel:{paymentId}`, `refund:{refundId}` |
| Stripe → webhook | `stripe_webhook_event` primary key (until manual purge; 30-day target) | `evt_...` |
| Kafka → consumer | Inbox (ADR-0005) | (group, eventId) |
| Relay → Kafka | Idempotent producer | producer id / sequence |
| Business | State machine + optimistic locking (`@Version`) | aggregate version |

HTTP semantics: same key and hash ⇒ stored response replayed (`Idempotent-Replayed: true`); same key, different hash
⇒ 422; still in progress ⇒ 409 with `Retry-After`; a 5xx removes the record so the client may retry.

Stripe can prune keys once they are at least 24 h old, so a payment still `CREATED` after 23 h becomes `INITIATION_FAILED` instead of being
retried with a fresh key that could create a second PaymentIntent.

## Alternatives considered

- **Single idempotency layer (e.g. only the inbox)** — leaves HTTP retries and Stripe retries unprotected.
- **Random Stripe idempotency keys per attempt** — defeats the purpose: a retry after a timeout could create a
  second PaymentIntent (F04, F05).
- **Redis-backed HTTP idempotency store** — higher throughput, but new infrastructure; listed as future work (§17).

## Consequences

- Every duplicate path in the failure matrix has a named mechanism and a test.
- More tables and retention jobs; key derivation rules must be followed by every new Stripe call.

## HTTP layer: how `@Idempotent` works

Implemented in `libs/platform-idempotency-starter` (`platform.idempotency.*`); a controller method opts in with
`@Idempotent(required = true, ttl = "PT24H")`.

### Mechanism: a handler interceptor plus a thin buffering filter

The feature needs three things that no single Spring extension point provides: to know **which handler method** (and its
annotation) serves the request, to read the **request body before** the handler runs, and to capture the **final
response** after it. The choice:

| Piece | Does | Why this one |
|---|---|---|
| `IdempotencyInterceptor` (`HandlerInterceptor`) | all decisions: header check, claim, conflicts, replay (`preHandle` can answer and return `false`), recording the outcome (`afterCompletion`) | it receives the resolved `HandlerMethod`, so `@Idempotent` is read from the real controller method; `afterCompletion` runs *after* exception handlers, so it sees the final status |
| `IdempotencyBufferingFilter` (servlet filter, after Spring Security's chain) | buffers the request body fully and wraps the response in a `ContentCachingResponseWrapper`; only when the request carries an `Idempotency-Key` | the body must be hashed before the controller consumes it; the response body only exists in a wrapper |

Rejected:

- **Filter only** — it runs before the `DispatcherServlet` and cannot see the handler method without re-doing handler
  mapping (path-parsing pitfalls outside the servlet) to find the annotation.
- **Interceptor only** — cannot read the body ahead of the handler nor capture the response.
- **AOP `@Around` on controllers** — runs after argument binding and validation (a 400 for a bad body never reaches it),
  sees return values instead of the final HTTP response (status/headers set by exception handlers or `ResponseEntity`),
  and cannot answer before argument resolution.
- **`ResponseBodyAdvice`** — only sees bodies of `@ResponseBody` returns, not errors, empty responses or status-only
  results.

Keeping the filter dumb (buffer, nothing else) and the interceptor smart keeps the logic testable and the cost zero for
requests without the header.

### Algorithm

| Situation | Result |
|---|---|
| header missing and `required` | `400` `urn:opp:problem:idempotency-key-required` |
| key not 1..255 printable ASCII characters (`0x21–0x7E`), or several headers | `400` `urn:opp:problem:idempotency-key-invalid` |
| claim succeeds | the controller runs |
| record `COMPLETED`, same request hash | stored response + `Idempotent-Replayed: true` |
| record `COMPLETED`, different hash | `422` `urn:opp:problem:idempotency-key-reuse` (F17) |
| record `IN_PROGRESS` | `409` `urn:opp:problem:request-in-progress` + `Retry-After: 1` (F16) |
| body larger than `max-body-size` (1 MB) | `413` |
| after the controller: 2xx, or 4xx except 409/429 | response stored, record `COMPLETED` |
| after the controller: 5xx, 409, 429 or an unhandled exception | record removed — the client may retry |

Problem details are written directly as `application/problem+json` (RFC 9457), independent of the service's exception
handling. Problem types are URNs.

- **Request identity** = SHA-256 of method, path with query string, and the canonical body. A JSON body is canonicalised
  (object keys sorted, whitespace dropped, decimals kept exact), so retries that merely reorder fields are the same
  request; other bodies are hashed as received; form posts are hashed by their sorted parameters.
- **Principal** = the name of the Spring Security authentication — for a JWT this is `sub` (`customerId = jwt.sub`, ADR-0013);
  unauthenticated requests share `anonymous`; a `PrincipalResolver` bean overrides this. Keys are therefore private to a
  caller: two customers using the same key never collide.
- **Claim** is one statement, `INSERT … ON CONFLICT DO UPDATE … WHERE <expired or abandoned>`, executed in its own
  `REQUIRES_NEW` transaction so it is committed — visible to concurrent requests — before the business method starts.
  Of ten concurrent requests exactly one inserts; the others see the committed `IN_PROGRESS` row (tested: the business
  method runs once). Every repository call is its own short transaction, so recording the outcome works even if the
  request's own transaction rolled back.
- **TTL**: `expires_at = now() + ttl`; an expired record is simply taken over by the next claim (the key is free again),
  `IdempotencyCleanup` only reclaims the space.
- **Abandoned claims**: if the process dies after claiming, the row would block the key until `expires_at`. An
  `IN_PROGRESS` row older than `platform.idempotency.in-progress-timeout` (2 min, must exceed the slowest idempotent
  request) is therefore taken over like an expired one.
- **What is replayed**: status, body bytes and the headers in `platform.idempotency.replay-headers`
  (`Content-Type`, `Location`, `Content-Language`, `ETag`), plus `Idempotent-Replayed: true`. Headers added by other
  filters (security headers) are not stored; they are added again to every response anyway. A response that Spring
  produced through `sendError` (the default handling of e.g. a validation failure) has its body rendered later by the
  container's error page, so it is stored as "status via sendError" and replayed the same way; the client gets an answer
  of the same shape.
- **Metrics**: `idempotency.replays` and `idempotency.conflicts{reason=key-reuse|in-progress}`.

### Integration rules and limits

- Register nothing by hand: the filter (order 0, after Spring Security's chain at −100) and the interceptor are
  auto-configured in servlet applications; the migration `V1002__idempotency.sql` is added to Flyway by the starter.
- Handler methods must complete the response synchronously; `DeferredResult`, `Callable` and reactive returns are not
  supported (the key is released, an error is logged).
- A request with an `Idempotency-Key` is buffered in memory, hence the 1 MB limit; a service with larger payloads on
  idempotent endpoints must raise it consciously.
- **Remaining window.** The business transaction commits *before* the response is recorded. A crash between the two
  leaves an `IN_PROGRESS` record that is taken over after the timeout, and the retry executes the business method
  again. Closing this completely would mean writing the record inside the business transaction, which couples the
  starter to every service's persistence. Business constraints protect existing payments and refunds, but do not close this window for order placement: `PlaceOrderService` allocates a new order id on every execution. A retry after abandonment can therefore create another order. F16 proves concurrent-request deduplication, not crash-safe HTTP order creation; see the follow-up in [backlog](../backlog.md#http-idempotency-business-commit-and-response-recording).
- Idempotency applies to the success path of the whole HTTP exchange, not to side effects inside the controller; those
  use their own keys (Stripe `Idempotency-Key`, inbox, ADR-0004/0005).

