import { buildEvent, isSimulatable, signPayload } from "@/lib/server/stripe-events";
import { serviceUrl } from "@/lib/server/targets";

export const dynamic = "force-dynamic";

/**
 * Local mode only (WEB_SIMULATE_WEBHOOKS=true): sends one synthetic, signed Stripe webhook for an order to
 * payment-service. The caller must be able to see the order's payment (the lookup runs with their own token), and the
 * secret never leaves this server. With real Stripe the flag is off and this answers 404: the Stripe CLI delivers.
 */
export async function POST(request: Request) {
  if (process.env.WEB_SIMULATE_WEBHOOKS !== "true") {
    return Response.json({ title: "Not found", status: 404, code: "not-found" }, { status: 404 });
  }
  const authorization = request.headers.get("authorization");
  if (!authorization) {
    return Response.json({ title: "Unauthorized", status: 401, code: "unauthorized" }, { status: 401 });
  }

  let input: { orderId?: unknown; event?: unknown };
  try {
    input = await request.json();
  } catch {
    return Response.json({ title: "Malformed request", status: 400, code: "malformed-request" }, { status: 400 });
  }
  const orderId = typeof input.orderId === "string" ? input.orderId : "";
  const event = typeof input.event === "string" ? input.event : "";
  if (!/^[0-9a-f-]{36}$/i.test(orderId) || !isSimulatable(event)) {
    return Response.json({ title: "Invalid request", status: 400, code: "validation-failed", detail: "Unknown order id or event." }, { status: 400 });
  }

  const lookup = await fetch(`${serviceUrl("payment")}/api/v1/payments/by-order/${orderId}`, {
    headers: { authorization },
    cache: "no-store",
  }).catch(() => null);
  if (!lookup) {
    return Response.json({ title: "Service unavailable", status: 502, code: "upstream-unreachable" }, { status: 502 });
  }
  if (!lookup.ok) {
    return Response.json({ title: "No such payment", status: lookup.status, code: "payment-not-found", detail: "That order has no payment you may see." }, { status: lookup.status });
  }
  const payment = await lookup.json();
  if (!payment.stripePaymentIntentId) {
    return Response.json({ title: "Too early", status: 409, code: "payment-not-ready", detail: "The PaymentIntent is not created yet." }, { status: 409 });
  }

  const now = Math.floor(Date.now() / 1000);
  const built = buildEvent(
    event,
    {
      orderId,
      paymentId: payment.paymentId,
      paymentIntentId: payment.stripePaymentIntentId,
      amountMinor: payment.amount.amountMinor,
      currency: payment.amount.currency,
    },
    now,
  );
  const secret = (process.env.STRIPE_WEBHOOK_SECRET ?? "whsec_local_demo").split(",")[0];
  const response = await fetch(`${serviceUrl("payment")}/webhooks/stripe`, {
    method: "POST",
    headers: { "content-type": "application/json", "stripe-signature": signPayload(built.body, secret, now) },
    body: built.body,
    cache: "no-store",
  }).catch(() => null);
  if (!response) {
    return Response.json({ title: "Service unavailable", status: 502, code: "upstream-unreachable" }, { status: 502 });
  }
  if (!response.ok) {
    return Response.json(
      { title: "Webhook refused", status: 502, code: "webhook-refused", detail: `payment-service answered ${response.status}; the web app and the service must share STRIPE_WEBHOOK_SECRET.` },
      { status: 502 },
    );
  }
  return Response.json({ sent: event, eventId: built.id });
}
