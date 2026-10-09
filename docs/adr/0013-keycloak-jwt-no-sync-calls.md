# ADR-0013: Keycloak as IdP; JWT resource servers; no synchronous service-to-service calls

- Status: Accepted
- Implementation review: 2026-10-09 (v1.0.0; limitations are documented in architecture §17)
- Date: 2026-10-08
- Related: architecture §3, §11, §12

## Context

The APIs serve customers (own orders and payments), admins (refunds, all orders) and ops (dead letters,
reconciliation). Authentication must be standard and demonstrable locally; authorization must include ownership
checks without leaking whether other customers' resources exist.

## Decision

- Keycloak 26.x, realm `opp`, realm roles `customer`, `admin`, `ops`.
- Clients: `opp-web` (public, PKCE; direct access grants enabled only in the dev realm for scripts) and `opp-ops-cli`
  (confidential, client credentials, service-account role `ops`).
- Both services are Spring Security OAuth2 resource servers: they validate signature (JWKS), `iss`, `exp` and `aud`
  (audience mappers add `order-service` and `payment-service`); roles come from `realm_access.roles` ⇒ `ROLE_*`.
- Ownership: `customerId = jwt.sub`; foreign resources return 404.
- The webhook endpoint uses no JWT; it is protected by signature, timestamp tolerance, livemode guard and body limit.
- **No synchronous service-to-service calls**: services interact only via Kafka (ADR-0002), so there are no
  service-to-service tokens, token relay or mTLS between services.

## Alternatives considered

- **Spring Authorization Server embedded in the project** — more code to own, less representative of real setups.
- **API keys / basic auth** — no standard roles, no token expiry, poor demonstration value.
- **Service-to-service REST with client-credentials tokens** — adds token management and temporal coupling with no
  functional need.

## Consequences

- Standard OIDC flows and a reproducible realm export for local use and tests (Testcontainers Keycloak).
- Security matrix tests per endpoint (role × ownership).
- Keycloak becomes a dependency of the local environment and service integration tests.
