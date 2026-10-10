# Web interface

The browser side of the platform: a storefront for customers, a back office for admins and an operations console, in one
Next.js app. It has no business logic and no database. Every status on screen comes from `order-service` and
`payment-service`; the app signs users in, calls the two REST APIs and shows what they answer.

| Role | Lands on | Can |
|---|---|---|
| `customer` | `/shop` | browse the catalog, fill a cart, place an order, pay it, cancel it while unpaid, follow every order (`/orders`, `/orders/{id}`) |
| `admin` | `/admin` | see every order with figures and filters, open any order, refund a paid order (or retry a failed refund) |
| `ops` | `/ops` | list, inspect, replay and resolve dead letters of both services; run reconciliation with Stripe and read its result |

The signature of the interface is the **journey** on an order's page: two lanes, one per service, in the order things
happened, built from the order's status history and its payment. It refreshes while the order moves.

## Run it

With the rest of the platform (the usual way):

```bash
./scripts/up.sh --apps          # the web interface is http://localhost:8090
```

Users of the dev realm: `customer1`, `admin1`, `ops1`, password `password`.

For development, start the platform without the web container (or stop it), then:

```bash
cd web
npm ci
npm run dev                     # http://localhost:3000
```

The dev server uses the defaults below, which match `./scripts/up.sh`. Keycloak accepts `http://localhost:3000` as a
redirect URI of the client `opp-web` (infra/keycloak/realm-opp.json). In local mode set `WEB_SIMULATE_WEBHOOKS=true` and
`STRIPE_WEBHOOK_SECRET=whsec_local_demo` (the same secret payment-service has) so the app can play Stripe.

## Configuration

Read at run time by the server, so one image serves every environment. Nothing here is a secret except the webhook secret,
which never leaves the server.

| Variable | Default | Meaning |
|---|---|---|
| `ORDER_SERVICE_URL` | `http://localhost:8081` | where the server reaches order-service |
| `PAYMENT_SERVICE_URL` | `http://localhost:8082` | where the server reaches payment-service |
| `KEYCLOAK_PUBLIC_URL` | `http://localhost:8180` | Keycloak as the **browser** reaches it (tokens carry this issuer) |
| `KEYCLOAK_REALM`, `KEYCLOAK_CLIENT_ID` | `opp`, `opp-web` | the public PKCE client |
| `STRIPE_PUBLISHABLE_KEY` | empty | `pk_test_...`, for the Payment Element |
| `WEB_PAYMENT_ELEMENT` | `false` | `true`: pay with the real Stripe Payment Element (stripe-test mode) |
| `WEB_SIMULATE_WEBHOOKS` | `false` | `true`: local mode, the app sends the signed webhooks stripe-mock does not |
| `STRIPE_WEBHOOK_SECRET` | `whsec_local_demo` | signs those webhooks; must equal payment-service's |

## How it works

- **Sign-in** is `keycloak-js`: Authorization Code with PKCE (S256) against the public client `opp-web`. The token lives in the
  page's memory and is refreshed before it expires. Roles come from `realm_access.roles`; hiding a button is a courtesy,
  the services enforce who may do what.
- **One origin.** The browser calls `/proxy/order/...` and `/proxy/payment/...`; a route handler forwards them with the user's own
  bearer token (and `Idempotency-Key`), so the services need no CORS. Only `/api/...` and `/admin/...` are forwarded: actuator and
  the webhook endpoint are not reachable through it.
- **Idempotency.** Placing, cancelling and refunding send an `Idempotency-Key`; the cart keeps one key per content, so a double click
  or a retry after a timeout cannot place two orders.
- **Local mode.** `stripe-mock` answers API calls and sends no webhooks. With `WEB_SIMULATE_WEBHOOKS=true` the paying UI confirms the
  PaymentIntent with the chosen test card (`/api/v1/test-support/...`) and `/api/simulate` signs and delivers the webhook Stripe would
  send, after checking that the caller may see that order's payment. A "Play Stripe" card on the order page sends any of them by hand.
- **Stripe mode.** With `WEB_PAYMENT_ELEMENT=true` the order page mounts the Payment Element with the client secret payment-service returns
  to the paying customer, 3-D Secure included. The order changes when Stripe's webhook has been processed, not when the browser says so.

## Layout

```
src/app/(app)/        pages behind sign-in: shop, orders, orders/[id], admin, ops
src/app/api/          config (public settings), simulate (local-mode webhooks)
src/app/proxy/        the forwarder to the two services
src/components/       app shell, journey rail, payment panel, dead letters, ... ; ui/ holds the primitives
src/lib/              API client, queries, journey builder, status vocabulary, cart, server-side Stripe events
```

Stack: Next.js 16 (App Router), React 19, TypeScript, Tailwind CSS 4, Radix primitives, TanStack Query, `keycloak-js`,
`@stripe/react-stripe-js`, Motion, Sonner. Type: Manrope for headings and the interface, JetBrains Mono for ids, times and labels. The look follows the
Altronix Software site: a deep navy ground, a cyan to blue to violet brand gradient, dark by default with a light variant. Colors are
named after what the money is doing (settled, in flight, failed, went back) and live as tokens in `globals.css`.

## Checks

```bash
npm run lint
npm run typecheck
npm test            # journey builder, formatting, webhook signing and event shapes
npm run build
```

CI runs the same four (`web` job). There are no browser tests yet (backlog).
