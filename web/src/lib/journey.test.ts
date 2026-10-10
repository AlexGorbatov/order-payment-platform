import { describe, expect, it } from "vitest";
import { buildJourney } from "./journey";
import type { HistoryEntry, Order, Payment } from "./types";

const order = (status: Order["status"], history: HistoryEntry[]): Order => ({
  id: "o1",
  customerId: "c1",
  status,
  total: { amountMinor: 6097, currency: "EUR" },
  items: [],
  disputed: false,
  createdAt: history[0].occurredAt,
  updatedAt: history[history.length - 1].occurredAt,
  history,
});

const payment = (status: Payment["status"]): Payment => ({
  paymentId: "p1",
  orderId: "o1",
  status,
  amount: { amountMinor: 6097, currency: "EUR" },
  stripePaymentIntentId: "pi_1",
  disputed: false,
  createdAt: "2026-10-10T10:00:00.200Z",
  updatedAt: "2026-10-10T10:00:00.200Z",
});

describe("buildJourney", () => {
  it("places a successful payment on the payment lane before the order it paid", () => {
    const journey = buildJourney(
      order("PAID", [
        { to: "PENDING_PAYMENT", occurredAt: "2026-10-10T10:00:00Z" },
        { from: "PENDING_PAYMENT", to: "PAID", occurredAt: "2026-10-10T10:00:05Z" },
      ]),
      payment("SUCCEEDED"),
    );
    expect(journey.events.map((e) => `${e.lane}:${e.title}`)).toEqual([
      "order:Order placed",
      "payment:Payment opened",
      "payment:Payment succeeded",
      "order:Order paid",
    ]);
    expect(journey.next).toBeNull();
  });

  it("shows a declined attempt without moving the order", () => {
    const journey = buildJourney(
      order("PENDING_PAYMENT", [
        { to: "PENDING_PAYMENT", occurredAt: "2026-10-10T10:00:00Z" },
        { from: "PENDING_PAYMENT", to: "PENDING_PAYMENT", reason: "PAYMENT_ATTEMPT_FAILED card_declined/insufficient_funds", occurredAt: "2026-10-10T10:00:04Z" },
      ]),
      payment("REQUIRES_PAYMENT_METHOD"),
    );
    const declined = journey.events.find((e) => e.title === "Card declined");
    expect(declined).toMatchObject({ lane: "payment", detail: "card_declined/insufficient_funds", tone: "fail" });
    expect(journey.next).toEqual({ lane: "payment", title: "Waiting for a card" });
  });

  it("tells the late payment story: cancelled, then paid, then refunded", () => {
    const journey = buildJourney(
      order("REFUNDED", [
        { to: "PENDING_PAYMENT", occurredAt: "2026-10-10T10:00:00Z" },
        { from: "PENDING_PAYMENT", to: "CANCELLED", reason: "TIMEOUT", occurredAt: "2026-10-10T10:01:00Z" },
        { from: "CANCELLED", to: "REFUND_REQUESTED", reason: "LATE_PAYMENT_AFTER_CANCEL", occurredAt: "2026-10-10T10:01:02Z" },
        { from: "REFUND_REQUESTED", to: "REFUNDED", occurredAt: "2026-10-10T10:01:07Z" },
      ]),
      payment("REFUNDED"),
    );
    expect(journey.events.map((e) => e.title)).toEqual([
      "Order placed",
      "Payment opened",
      "Order cancelled",
      "Payment arrived late",
      "Refund requested",
      "Refund settled",
      "Order refunded",
    ]);
    expect(journey.events.find((e) => e.title === "Order cancelled")?.detail).toBe("payment window expired");
  });

  it("waits for the refund while it is in progress", () => {
    const journey = buildJourney(
      order("REFUND_REQUESTED", [
        { to: "PENDING_PAYMENT", occurredAt: "2026-10-10T10:00:00Z" },
        { from: "PENDING_PAYMENT", to: "PAID", occurredAt: "2026-10-10T10:00:05Z" },
        { from: "PAID", to: "REFUND_REQUESTED", reason: "ADMIN", occurredAt: "2026-10-10T10:00:10Z" },
      ]),
      payment("SUCCEEDED"),
    );
    expect(journey.next).toEqual({ lane: "payment", title: "Waiting for Stripe to confirm the refund" });
  });

  it("copes with an order whose payment does not exist yet", () => {
    const journey = buildJourney(order("PENDING_PAYMENT", [{ to: "PENDING_PAYMENT", occurredAt: "2026-10-10T10:00:00Z" }]), null);
    expect(journey.next).toEqual({ lane: "payment", title: "Creating the payment at Stripe" });
  });
});
