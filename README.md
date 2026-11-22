# payment-gateway

Modular-monolith payment backend on **Java 21 / Spring Boot 3.2 / PostgreSQL**.

| Package | Responsibility |
|---|---|
| `payment` | Idempotent payment creation, status state machine, gateway call with retries |
| `webhook` | HMAC-SHA256 verification with replay window, exactly-once event application |
| `ledger` | Double-entry journals, idempotent posting, row-locked balance updates |
| `gateway` | Gateway port, REST adapter (timeouts and 5xx retryable, 4xx not), backoff policy |
| `reconciliation` | Pure comparison of internal payments against a settlement report |
| `error` | Domain exceptions mapped to HTTP status codes |

## Guarantees and where they live

- **At-most-once charge per idempotency key**: the key is claimed with `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the payment. Concurrent duplicates wait on the unique index and then replay.
- **Webhook dedupe**: `webhook_events.event_id` is the primary key. A redelivery inserts zero rows and returns `DUPLICATE`.
- **No double credit**: settlement posts journal `settlement:<reference>`, and `ledger_journals.journal_id` is unique.
- **Failure safety**: any exception rolls back the event claim, so the gateway's redelivery is processed from scratch.
- **Ledger safety**: accounts are locked with `SELECT ... FOR UPDATE` in sorted `(code, currency)` order, which prevents deadlocks. Each account also has a `@Version` column.
- **Unknown gateway outcome**: after retries are exhausted, the payment stays `PENDING` with `pendingReconciliation=true`. It is never failed or re-charged under a new reference.

## Running

```bash
# Unit tests (no Docker needed)
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test

# Unit + integration tests (needs a running Docker daemon for Testcontainers)
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn verify
```

Integration tests are `*IT` classes, run by Failsafe under `verify`.

## Configuration

| Property | Env var | Default |
|---|---|---|
| `payments.webhook.secret` | `WEBHOOK_SECRET` | `change-me-in-production` (**must be overridden**) |
| `payments.gateway.base-url` | `GATEWAY_BASE_URL` | `http://localhost:9000` |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/payments` |

## Known limits

- The schema is applied from `db/schema.sql` on startup. There is no migration tool yet. Adopt Flyway before the first production release.
- The gateway idempotency-key contract is an assumption: the gateway must treat the `Idempotency-Key` header (our merchant reference) as idempotent.
- No authentication on the merchant API. Put it behind the existing gateway.
