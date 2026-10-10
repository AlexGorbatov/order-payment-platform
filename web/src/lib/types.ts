// The shapes of the two services' REST APIs (architecture §11), as far as the interface reads them.

export type OrderStatus =
  | "PENDING_PAYMENT"
  | "PAID"
  | "CANCELLED"
  | "REFUND_REQUESTED"
  | "REFUNDED"
  | "REFUND_FAILED";

export type PaymentStatus =
  | "CREATED"
  | "REQUIRES_PAYMENT_METHOD"
  | "REQUIRES_ACTION"
  | "PROCESSING"
  | "SUCCEEDED"
  | "CANCELED"
  | "INITIATION_FAILED"
  | "REFUNDED";

export interface Money {
  amountMinor: number;
  currency: string;
}

export interface Product {
  sku: string;
  name: string;
  price: Money;
}

export interface OrderItem {
  sku: string;
  name: string;
  quantity: number;
  unitPrice: Money;
  lineTotal: Money;
}

export interface HistoryEntry {
  from?: OrderStatus;
  to: OrderStatus;
  reason?: string;
  occurredAt: string;
}

export interface Order {
  id: string;
  customerId: string;
  status: OrderStatus;
  total: Money;
  items: OrderItem[];
  disputed: boolean;
  createdAt: string;
  updatedAt: string;
  history: HistoryEntry[];
}

export interface OrderSummary {
  id: string;
  customerId: string;
  status: OrderStatus;
  total: Money;
  lineCount: number;
  disputed: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Payment {
  paymentId: string;
  orderId: string;
  status: PaymentStatus;
  amount: Money;
  stripePaymentIntentId?: string;
  clientSecret?: string;
  lastErrorCode?: string;
  disputed: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ConfirmResult {
  scenario: string;
  accepted: boolean;
  stripeStatus?: string;
  errorCode?: string;
  declineCode?: string;
}

export type DeadLetterStatus = "NEW" | "REPLAYED" | "RESOLVED";

export interface DeadLetter {
  id: string;
  status: DeadLetterStatus;
  originalTopic: string;
  dltTopic: string;
  messageKey?: string;
  exceptionClass?: string;
  exceptionMessage?: string;
  note?: string;
  createdAt: string;
  updatedAt: string;
}

export interface DeadLetterDetail extends DeadLetter {
  payload: string;
  headers: Record<string, string>;
}

export interface DeadLetterPage {
  items: DeadLetter[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ReconciliationSummary {
  trigger: string;
  startedAt: string;
  finishedAt: string;
  checked: number;
  drifted: number;
  unchanged: number;
  failed: number;
  deferred: number;
  drifts: { paymentId: string; from: string; to: string }[];
}

/** What the browser needs to know about this deployment; served by /api/config at run time. */
export interface PublicConfig {
  keycloakUrl: string;
  realm: string;
  clientId: string;
  stripePublishableKey: string;
  /** The real Stripe Payment Element (stripe-test) instead of the test-support endpoint. */
  paymentElement: boolean;
  /** Local mode: the interface can play Stripe's part and send the signed webhooks itself. */
  simulateWebhooks: boolean;
}
