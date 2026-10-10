import type { OrderStatus, PaymentStatus } from "./types";

export type Tone = "settle" | "flight" | "fail" | "refund" | "neutral";

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: Tone; hint: string }> = {
  PENDING_PAYMENT: { label: "Awaiting payment", tone: "flight", hint: "The order is placed and waits for a payment." },
  PAID: { label: "Paid", tone: "settle", hint: "The payment succeeded." },
  CANCELLED: { label: "Cancelled", tone: "neutral", hint: "The order was cancelled; nothing is owed." },
  REFUND_REQUESTED: { label: "Refund in progress", tone: "refund", hint: "A full refund was requested and is on its way." },
  REFUNDED: { label: "Refunded", tone: "refund", hint: "The money went back to the customer." },
  REFUND_FAILED: { label: "Refund failed", tone: "fail", hint: "Stripe could not return the money; an admin can retry." },
};

export const PAYMENT_STATUS: Record<PaymentStatus, { label: string; tone: Tone }> = {
  CREATED: { label: "Creating payment", tone: "flight" },
  REQUIRES_PAYMENT_METHOD: { label: "Ready for a card", tone: "flight" },
  REQUIRES_ACTION: { label: "Needs authentication", tone: "flight" },
  PROCESSING: { label: "Processing", tone: "flight" },
  SUCCEEDED: { label: "Succeeded", tone: "settle" },
  CANCELED: { label: "Cancelled", tone: "neutral" },
  INITIATION_FAILED: { label: "Could not start", tone: "fail" },
  REFUNDED: { label: "Refunded", tone: "refund" },
};

/** Orders whose status is still moving: the interface keeps asking. */
export function orderIsInMotion(status: OrderStatus): boolean {
  return status === "PENDING_PAYMENT" || status === "REFUND_REQUESTED";
}

export function canCancel(status: OrderStatus): boolean {
  return status === "PENDING_PAYMENT";
}

export function canRefund(status: OrderStatus): boolean {
  return status === "PAID" || status === "REFUND_FAILED";
}

export const CANCEL_REASON: Record<string, string> = {
  CUSTOMER: "by the customer",
  TIMEOUT: "payment window expired",
  PAYMENT_INITIATION_FAILED: "payment could not start",
  PAYMENT_CANCELED: "payment cancelled at Stripe",
};

export const REFUND_REASON: Record<string, string> = {
  ADMIN: "requested by an admin",
  LATE_PAYMENT_AFTER_CANCEL: "payment arrived after the order was cancelled",
};
