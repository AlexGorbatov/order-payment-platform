# Runbook: dead letters

A **dead letter** is an event a consumer could not process after all retries (architecture §7.4, ADR-0007). It is stored
in `dead_letter_message` of the **consuming service** (each service has its own table and API) and waits there until an
operator replays or resolves it. Nothing is lost: the Kafka copy lives on `<topic>-dlt` for 14 days, the database row
until someone purges it.

| | |
|---|---|
| Alert | `dlt.messages{topic}` increases; or `GET /admin/dead-letters?status=NEW` is not empty |
| Who | an operator with the realm role `ops` |
| API | `https://<service>/admin/dead-letters` — order-service (8081) or payment-service (8082) |
| Statuses | `NEW` → `REPLAYED` and/or `RESOLVED` |

Local examples use `ORDER=http://localhost:8081` and a token from `./scripts/ops-token.sh`:

```bash
ORDER=http://localhost:8081
TOKEN=$(./scripts/ops-token.sh)
api() { curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }
```

## 1. Find

```bash
# everything waiting for a decision, newest first
api "$ORDER/admin/dead-letters?status=NEW&size=50" | jq '.items[] | {id, originalTopic, exceptionClass, exceptionMessage, createdAt}'

# one topic only, next page
api "$ORDER/admin/dead-letters?status=NEW&topic=payment.events.v1&page=1"
```

`size` is at most 100. The response has `totalItems` and `totalPages`.

If the API is unavailable, read the table directly:

```sql
SELECT id, original_topic, exception_class, left(exception_message, 120), created_at
FROM dead_letter_message WHERE status = 'NEW' ORDER BY created_at DESC;
```

Also useful: Kafka UI (`http://localhost:8085`) → topic `<topic>-dlt`, and the retry topics `<topic>-retry-0 … -2`
(records in them are in flight, not yet dead).

## 2. Understand

```bash
ID=<uuid>
api "$ORDER/admin/dead-letters/$ID" | jq '{status, originalTopic, messageKey, exceptionClass, exceptionMessage, payload, headers}'
```

Look at `exceptionClass` (the exception the listener threw), `exceptionMessage`, the `payload` (it may contain personal
data — do not paste it into tickets) and `headers` (`kafka_exception-stacktrace` has the stack trace, `retry_topic-attempts`
how many deliveries it took). Then decide:

| Cause | Typical `exceptionClass` | Action |
|---|---|---|
| Bug in the consumer, now fixed and deployed | any business/`RuntimeException` | **Replay** |
| Infrastructure was down longer than the retries (database, Stripe) and is back | `CannotGetJdbcConnectionException`, timeouts | **Replay** |
| Missing prerequisite that has been created since (e.g. a product, a payment) | `NonRetryableEventException` | fix the data, then **Replay** |
| Malformed or invalid message (producer bug) | `EventSerdeException`, `DeserializationException` | fix the producer, **Resolve** (it cannot be replayed: `422`) |
| Event that must not be applied (obsolete, handled manually) | — | **Resolve** with a comment |
| Event whose effect already happened (duplicate) | — | **Replay** is harmless (the inbox skips it) or **Resolve** |

Check the effect before deciding: look the order/payment up through the normal API or the database.

## 3. Replay

Replay re-publishes the **unchanged payload** to the **original topic through the outbox** and sets `REPLAYED`.

```bash
api -X POST "$ORDER/admin/dead-letters/$ID/replay" | jq '{id, status}'
```

What happens: the payload and the headers `eventType`, `eventVersion`, `correlationId`, `traceparent` plus
`x-replay-of: <dead letter id>` are written to `outbox_event` in the same transaction that sets `REPLAYED`; the outbox
relay publishes them within about a second; the consumer processes the event like any other. The **inbox does not
reject** the replay even though the `eventId` is the same: the failed processing rolled back, and its inbox row with it.

Verify:

```sql
SELECT id, published_at, attempts, last_error FROM outbox_event ORDER BY created_at DESC LIMIT 5;   -- published_at set
SELECT * FROM inbox_message WHERE event_id = '<eventId from the payload>';                             -- present after success
```

and the business result (order status, payment status). A trace/log search for `x-replay-of` or the `correlationId`
shows the whole path.

Rules:

- A dead letter can be replayed **once**. A second call, or a call on a resolved one, returns `409`. This prevents
  double effects from a double click.
- If the replayed event fails again, it produces a **new** dead letter (after the usual retries). The consumer is still
  broken: do not replay again before it is really fixed. Resolve the old one with a comment pointing to the new one.
- A payload that is not an event envelope cannot be replayed (`422`); resolve it.
- Replay many carefully, oldest first, and watch `dlt.messages`:

  ```bash
  api "$ORDER/admin/dead-letters?status=NEW&topic=payment.events.v1&size=100" | jq -r '.items | reverse[] | .id' |
    while read -r id; do api -X POST "$ORDER/admin/dead-letters/$id/replay" | jq -c '{id, status}'; sleep 1; done
  ```

## 4. Resolve

Closes a dead letter without or after a replay. The comment is mandatory (max 1000 characters) and is kept as `note`:
say **what** was decided and **why**, and link the ticket.

```bash
api -X POST "$ORDER/admin/dead-letters/$ID/resolve" \
  -d '{"comment":"Producer bug INC-142 (missing currency). Order cancelled manually; event discarded."}' | jq '{id, status, note}'
```

Resolving twice returns `409`. A `REPLAYED` dead letter should be resolved once the result has been verified.

## 5. Troubleshooting

| Symptom | Cause / action |
|---|---|
| `401` | missing or expired token (they live 15 minutes) |
| `403` | the token lacks the `ops` realm role |
| `404` | wrong service: dead letters are per service; check the other one |
| `409` on replay | already `REPLAYED` or `RESOLVED` |
| `422` on replay | the payload is not a valid event envelope or has no key |
| Dead letters appear in Kafka (`<topic>-dlt`) but not in the API | the persister is not running or the database is down: look for `Stored dead letter` / `dead-letter` errors in the service log. It retries the same record indefinitely, so nothing is skipped; the backlog is stored when the database is back |
| Replayed event does not arrive | `outbox_event.published_at` is null: the relay or Kafka is down (`outbox.oldest.age.seconds` grows) |
| Replayed event arrives but nothing changes | the inbox recognised it as a duplicate (its effect already happened) — check `inbox.duplicates` and the business state |
| The same message keeps coming back as a new dead letter | the consumer is not fixed yet, or the event is invalid for the current state: resolve instead of replaying |

## 6. Housekeeping

- DLT and retry topics keep records for 14 days; `dead_letter_message` rows are not deleted automatically. Review
  `RESOLVED` rows periodically and purge old ones:

  ```sql
  DELETE FROM dead_letter_message WHERE status = 'RESOLVED' AND updated_at < now() - interval '90 days';
  ```

- Review the exception classes of the last week: repeated causes are consumer bugs, not operations.
