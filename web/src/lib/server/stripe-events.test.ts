import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { buildEvent, isSimulatable, signPayload } from "./stripe-events";

const facts = {
  orderId: "11111111-1111-4111-8111-111111111111",
  paymentId: "22222222-2222-4222-8222-222222222222",
  paymentIntentId: "pi_123",
  amountMinor: 6097,
  currency: "EUR",
};

describe("signPayload", () => {
  it("signs t.payload with HMAC-SHA256, the way Stripe does", () => {
    const header = signPayload('{"a":1}', "whsec_x", 1790000000);
    const expected = createHmac("sha256", "whsec_x").update('1790000000.{"a":1}').digest("hex");
    expect(header).toBe(`t=1790000000,v1=${expected}`);
  });
});

describe("buildEvent", () => {
  it("carries the ids the service looks the payment up by", () => {
    const event = JSON.parse(buildEvent("payment_intent.succeeded", facts, 1790000000).body);
    expect(event.type).toBe("payment_intent.succeeded");
    expect(event.livemode).toBe(false);
    expect(event.data.object).toMatchObject({
      id: "pi_123",
      status: "succeeded",
      amount: 6097,
      currency: "eur",
      metadata: { orderId: facts.orderId, paymentId: facts.paymentId },
    });
  });

  it("describes a decline with a card_declined error and no status change", () => {
    const event = JSON.parse(buildEvent("payment_intent.payment_failed", facts, 1790000000).body);
    expect(event.data.object.status).toBe("requires_payment_method");
    expect(event.data.object.last_payment_error.code).toBe("card_declined");
  });

  it("refers to the PaymentIntent from the charge and the dispute", () => {
    for (const type of ["charge.refunded", "charge.dispute.created"] as const) {
      const event = JSON.parse(buildEvent(type, facts, 1790000000).body);
      expect(event.data.object.payment_intent).toBe("pi_123");
    }
  });

  it("never marks an event as live mode, and gives every event its own id", () => {
    const a = buildEvent("payment_intent.succeeded", facts, 1);
    const b = buildEvent("payment_intent.succeeded", facts, 1);
    expect(a.id).not.toBe(b.id);
    expect(JSON.parse(a.body).livemode).toBe(false);
  });
});

describe("isSimulatable", () => {
  it("accepts only the events the interface may send", () => {
    expect(isSimulatable("payment_intent.succeeded")).toBe(true);
    expect(isSimulatable("customer.created")).toBe(false);
  });
});
