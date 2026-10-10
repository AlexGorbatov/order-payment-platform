import { createHmac, randomBytes } from "node:crypto";

/**
 * Local mode only: stripe-mock answers API calls but never sends webhooks, so the interface can play Stripe's part.
 * These are the same synthetic events the services' tests and scripts/send-test-webhook.sh use, signed the way
 * Stripe signs them (`t=<seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`).
 */
export const SIMULATABLE_EVENTS = [
  "payment_intent.succeeded",
  "payment_intent.payment_failed",
  "payment_intent.requires_action",
  "charge.dispute.created",
  "charge.refunded",
] as const;

export type SimulatableEvent = (typeof SIMULATABLE_EVENTS)[number];

export function isSimulatable(value: string): value is SimulatableEvent {
  return (SIMULATABLE_EVENTS as readonly string[]).includes(value);
}

export interface PaymentFacts {
  orderId: string;
  paymentId: string;
  paymentIntentId: string;
  amountMinor: number;
  currency: string;
}

export function buildEvent(type: SimulatableEvent, facts: PaymentFacts, nowSeconds: number) {
  const suffix = randomBytes(7).toString("hex");
  const id = `evt_local_${suffix}`;
  const currency = facts.currency.toLowerCase();
  const metadata = { orderId: facts.orderId, paymentId: facts.paymentId };
  const paymentIntent = (status: string, extra: Record<string, unknown>) => ({
    id: facts.paymentIntentId,
    object: "payment_intent",
    amount: facts.amountMinor,
    amount_received: status === "succeeded" ? facts.amountMinor : 0,
    currency,
    status,
    created: nowSeconds,
    last_payment_error: null,
    cancellation_reason: null,
    livemode: false,
    metadata,
    ...extra,
  });

  let object: Record<string, unknown>;
  switch (type) {
    case "payment_intent.succeeded":
      object = paymentIntent("succeeded", { latest_charge: `ch_local_${suffix}` });
      break;
    case "payment_intent.payment_failed":
      object = paymentIntent("requires_payment_method", {
        last_payment_error: {
          type: "card_error",
          code: "card_declined",
          decline_code: "insufficient_funds",
          message: "Your card has insufficient funds.",
        },
      });
      break;
    case "payment_intent.requires_action":
      object = paymentIntent("requires_action", { next_action: { type: "use_stripe_sdk" } });
      break;
    case "charge.refunded":
      object = {
        id: `ch_local_${suffix}`,
        object: "charge",
        amount: facts.amountMinor,
        amount_refunded: facts.amountMinor,
        currency,
        paid: true,
        refunded: true,
        status: "succeeded",
        payment_intent: facts.paymentIntentId,
        livemode: false,
        metadata: {},
      };
      break;
    case "charge.dispute.created":
      object = {
        id: `dp_local_${suffix}`,
        object: "dispute",
        amount: facts.amountMinor,
        currency,
        charge: `ch_local_${suffix}`,
        payment_intent: facts.paymentIntentId,
        reason: "fraudulent",
        status: "needs_response",
        created: nowSeconds,
        livemode: false,
        metadata: {},
      };
      break;
  }

  return {
    id,
    body: JSON.stringify({
      id,
      object: "event",
      api_version: "2026-09-30.endive",
      created: nowSeconds,
      data: { object },
      livemode: false,
      pending_webhooks: 1,
      request: { id: null, idempotency_key: null },
      type,
    }),
  };
}

export function signPayload(payload: string, secret: string, nowSeconds: number): string {
  const digest = createHmac("sha256", secret).update(`${nowSeconds}.${payload}`).digest("hex");
  return `t=${nowSeconds},v1=${digest}`;
}
