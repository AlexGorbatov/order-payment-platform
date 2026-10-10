import { CANCEL_REASON, REFUND_REASON, type Tone } from "./status";
import type { Order, Payment } from "./types";

/** One thing that happened to an order, on the lane of the service that owns it. */
export interface JourneyEvent {
  key: string;
  lane: "order" | "payment";
  at: string;
  title: string;
  detail?: string;
  tone: Tone;
}

/** What the journey is waiting for, if anything. */
export interface JourneyNext {
  lane: "order" | "payment";
  title: string;
}

export interface Journey {
  events: JourneyEvent[];
  next: JourneyNext | null;
}

/**
 * Builds the two lanes of an order's journey from what the services report: the order's status history (which also
 * records payment events as notes: failed attempts, authentication, disputes) and the current payment. Payment
 * events that only show as an order status change (success, refund) are placed on the payment lane just before it.
 */
export function buildJourney(order: Order, payment: Payment | null | undefined): Journey {
  const events: JourneyEvent[] = [];
  const push = (event: Omit<JourneyEvent, "key">) => events.push({ ...event, key: `${events.length}` });

  order.history.forEach((entry) => {
    const { from, to, reason, occurredAt: at } = entry;
    if (!from) {
      push({ lane: "order", at, title: "Order placed", tone: "neutral" });
      return;
    }
    if (from === to) {
      if (reason?.startsWith("PAYMENT_ATTEMPT_FAILED")) {
        push({ lane: "payment", at, title: "Card declined", detail: reason.replace("PAYMENT_ATTEMPT_FAILED ", ""), tone: "fail" });
      } else if (reason === "PAYMENT_ACTION_REQUIRED") {
        push({ lane: "payment", at, title: "Authentication needed", tone: "flight" });
      } else if (reason === "DISPUTED") {
        push({ lane: "payment", at, title: "Dispute opened", detail: "the order stays paid", tone: "fail" });
      } else {
        push({ lane: "order", at, title: `Note: ${reason ?? to}`, tone: "neutral" });
      }
      return;
    }
    switch (to) {
      case "PAID":
        push({ lane: "payment", at, title: "Payment succeeded", tone: "settle" });
        push({ lane: "order", at, title: "Order paid", tone: "settle" });
        break;
      case "CANCELLED":
        push({ lane: "order", at, title: "Order cancelled", detail: CANCEL_REASON[reason ?? ""] ?? reason, tone: "neutral" });
        break;
      case "REFUND_REQUESTED":
        if (reason === "LATE_PAYMENT_AFTER_CANCEL") {
          push({ lane: "payment", at, title: "Payment arrived late", detail: "after the order was cancelled", tone: "flight" });
        }
        push({ lane: "order", at, title: "Refund requested", detail: REFUND_REASON[reason ?? ""] ?? reason, tone: "refund" });
        break;
      case "REFUNDED":
        push({ lane: "payment", at, title: "Refund settled", tone: "refund" });
        push({ lane: "order", at, title: "Order refunded", tone: "refund" });
        break;
      case "REFUND_FAILED":
        push({ lane: "payment", at, title: "Refund failed", tone: "fail" });
        push({ lane: "order", at, title: "Refund failed", detail: "an admin can retry", tone: "fail" });
        break;
      default:
        push({ lane: "order", at, title: to, tone: "neutral" });
    }
  });

  if (payment) {
    events.push({
      key: "payment-opened",
      lane: "payment",
      at: payment.createdAt,
      title: "Payment opened",
      detail: payment.stripePaymentIntentId,
      tone: "neutral",
    });
  }

  // Stable by time; the order of generation breaks ties (the payment lane's cause comes before the order's effect).
  const indexed = events.map((event, index) => ({ event, index }));
  indexed.sort((a, b) => Date.parse(a.event.at) - Date.parse(b.event.at) || a.index - b.index);
  const sorted = indexed.map(({ event }, index) => ({ ...event, key: `${index}` }));

  return { events: sorted, next: nextStep(order, payment) };
}

function nextStep(order: Order, payment: Payment | null | undefined): JourneyNext | null {
  if (order.status === "REFUND_REQUESTED") {
    return { lane: "payment", title: "Waiting for Stripe to confirm the refund" };
  }
  if (order.status !== "PENDING_PAYMENT") {
    return null;
  }
  switch (payment?.status) {
    case "REQUIRES_PAYMENT_METHOD":
      return { lane: "payment", title: "Waiting for a card" };
    case "REQUIRES_ACTION":
      return { lane: "payment", title: "Waiting for authentication" };
    case "PROCESSING":
      return { lane: "payment", title: "Waiting for the bank" };
    case undefined:
    case "CREATED":
      return { lane: "payment", title: "Creating the payment at Stripe" };
    default:
      return null;
  }
}
