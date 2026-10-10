"use client";

import { Elements, PaymentElement, useElements, useStripe } from "@stripe/react-stripe-js";
import { loadStripe, type Stripe } from "@stripe/stripe-js";
import { useQueryClient } from "@tanstack/react-query";
import { CircleAlert, CreditCard, ShieldCheck } from "lucide-react";
import { useTheme } from "next-themes";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { useAuth } from "@/components/auth-provider";
import { PaymentStatusBadge } from "@/components/status-badge";
import { useSimulate } from "@/components/simulator";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { describeError, useApi } from "@/lib/api";
import { keys } from "@/lib/queries";
import type { ConfirmResult, Order, Payment } from "@/lib/types";

const SCENARIOS = [
  { id: "success", title: "A card that works", text: "pm_card_visa" },
  { id: "decline", title: "A declined card", text: "pm_card_chargeDeclined" },
  { id: "insufficient_funds", title: "Insufficient funds", text: "pm_card_chargeDeclinedInsufficientFunds" },
  { id: "requires_3ds", title: "Asks for 3-D Secure", text: "pm_card_authenticationRequired" },
  { id: "dispute", title: "Works, then disputed", text: "pm_card_createDispute" },
] as const;

// What Stripe would send after each test card (local mode: the interface sends it).
const WEBHOOKS: Record<string, string[]> = {
  success: ["payment_intent.succeeded"],
  decline: ["payment_intent.payment_failed"],
  insufficient_funds: ["payment_intent.payment_failed"],
  requires_3ds: ["payment_intent.requires_action"],
  dispute: ["payment_intent.succeeded", "charge.dispute.created"],
};

const stripes = new Map<string, Promise<Stripe | null>>();
function stripeFor(key: string) {
  if (!stripes.has(key)) stripes.set(key, loadStripe(key));
  return stripes.get(key)!;
}

export function PaymentPanel({ order, payment, isOwner }: { order: Order; payment: Payment | null | undefined; isOwner: boolean }) {
  const { config } = useAuth();
  const payable = order.status === "PENDING_PAYMENT" && (payment?.status === "REQUIRES_PAYMENT_METHOD" || payment?.status === "REQUIRES_ACTION");

  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Payment</CardTitle>
          <CardDescription>Created by payment-service from the order event.</CardDescription>
        </div>
        {payment ? <PaymentStatusBadge status={payment.status} /> : null}
      </CardHeader>
      <CardBody className="space-y-4">
        {payment === undefined ? (
          <Skeleton className="h-16" />
        ) : payment === null ? (
          <p className="text-sm text-muted">The payment is being created: order-service published the order, payment-service reacts and asks Stripe for a PaymentIntent.</p>
        ) : (
          <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
            <dt className="text-muted">PaymentIntent</dt>
            <dd className="break-all font-mono text-xs">{payment.stripePaymentIntentId ?? "not created yet"}</dd>
            {payment.lastErrorCode ? (
              <>
                <dt className="text-muted">Last attempt</dt>
                <dd className="flex items-center gap-1.5 text-fail">
                  <CircleAlert className="size-4" aria-hidden /> <span className="font-mono text-xs">{payment.lastErrorCode}</span>
                </dd>
              </>
            ) : null}
            {payment.disputed ? (
              <>
                <dt className="text-muted">Dispute</dt>
                <dd className="text-fail">Opened by the cardholder</dd>
              </>
            ) : null}
          </dl>
        )}

        {payable && payment && isOwner ? (
          config.paymentElement ? <StripePayment order={order} payment={payment} /> : <TestCardPayment order={order} payment={payment} />
        ) : null}
        {payable && !isOwner ? <p className="text-sm text-muted">Only the customer who placed the order can pay it.</p> : null}
        {order.status === "PENDING_PAYMENT" && payment?.status === "INITIATION_FAILED" ? (
          <p className="text-sm text-fail">Stripe refused to create the payment, so the order is being cancelled.</p>
        ) : null}
      </CardBody>
    </Card>
  );
}

/** Local mode: the test-support endpoint confirms the PaymentIntent with a Stripe test card instead of the browser. */
function TestCardPayment({ order, payment }: { order: Order; payment: Payment }) {
  const api = useApi();
  const { config } = useAuth();
  const { send } = useSimulate(order.id);
  const queryClient = useQueryClient();
  const [paying, setPaying] = useState<string | null>(null);
  const [result, setResult] = useState<string | null>(null);

  async function pay(scenario: string) {
    setPaying(scenario);
    setResult(null);
    try {
      const outcome = await api.post<ConfirmResult>("payment", `/api/v1/test-support/payments/by-order/${order.id}/confirm?scenario=${scenario}`);
      setResult(outcome.accepted ? "Stripe took the card." : `Stripe refused the card: ${outcome.errorCode}${outcome.declineCode ? ` / ${outcome.declineCode}` : ""}.`);
      if (config.simulateWebhooks) await send(...WEBHOOKS[scenario]);
      await queryClient.invalidateQueries({ queryKey: keys.payment(order.id) });
    } catch (error) {
      toast.error("The payment did not go through", { description: describeError(error) });
    } finally {
      setPaying(null);
    }
  }

  if (payment.status === "REQUIRES_ACTION") {
    return (
      <div className="space-y-3 rounded-lg bg-flight-soft p-4">
        <p className="flex items-center gap-2 text-sm font-medium text-flight">
          <ShieldCheck className="size-4" aria-hidden /> The bank wants the customer to authenticate (3-D Secure).
        </p>
        <div className="flex flex-wrap gap-2">
          <Button size="sm" loading={paying === "auth"} onClick={() => { setPaying("auth"); send("payment_intent.succeeded").finally(() => setPaying(null)); }}>
            Authenticate
          </Button>
          <Button size="sm" variant="secondary" onClick={() => send("payment_intent.payment_failed")}>
            Fail the challenge
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium">Pay with a Stripe test card</p>
      <div className="grid gap-2 sm:grid-cols-2">
        {SCENARIOS.map((scenario) => (
          <button
            key={scenario.id}
            className="rounded-lg border border-line p-3 text-left transition-colors hover:border-accent hover:bg-accent-soft disabled:opacity-50"
            disabled={paying !== null}
            onClick={() => pay(scenario.id)}
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              <CreditCard className="size-4 text-accent" aria-hidden /> {scenario.title}
            </span>
            <span className="mt-0.5 block font-mono text-[10px] text-muted">{scenario.text}</span>
          </button>
        ))}
      </div>
      {result ? <p className="text-sm text-muted" aria-live="polite">{result}</p> : null}
    </div>
  );
}

/** Real Stripe test mode: the Payment Element with the client secret the platform returns to the paying customer. */
function StripePayment({ order, payment }: { order: Order; payment: Payment }) {
  const { config } = useAuth();
  const { resolvedTheme } = useTheme();
  const options = useMemo(
    () => ({
      clientSecret: payment.clientSecret ?? "",
      appearance: {
        theme: resolvedTheme === "dark" ? ("night" as const) : ("stripe" as const),
        variables: { colorPrimary: resolvedTheme === "dark" ? "#34c9b6" : "#0f8f82", borderRadius: "10px", fontFamily: "Geist, system-ui, sans-serif" },
      },
    }),
    [payment.clientSecret, resolvedTheme],
  );

  if (!payment.clientSecret) return <Skeleton className="h-32" />;
  if (!config.stripePublishableKey.startsWith("pk_test_")) {
    return <p className="text-sm text-fail">STRIPE_PUBLISHABLE_KEY must be a pk_test_ key for the Payment Element. See docs/demo.md.</p>;
  }
  return (
    <Elements stripe={stripeFor(config.stripePublishableKey)} options={options} key={payment.clientSecret}>
      <StripeForm order={order} />
    </Elements>
  );
}

function StripeForm({ order }: { order: Order }) {
  const stripe = useStripe();
  const elements = useElements();
  const queryClient = useQueryClient();
  const [paying, setPaying] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!stripe || !elements) return;
    setPaying(true);
    setMessage(null);
    const { error, paymentIntent } = await stripe.confirmPayment({ elements, redirect: "if_required", confirmParams: { return_url: window.location.href } });
    if (error) setMessage(error.message ?? "The payment did not go through.");
    else setMessage(`Stripe says ${paymentIntent?.status}. The order changes when Stripe's webhook has been processed.`);
    setPaying(false);
    await queryClient.invalidateQueries({ queryKey: keys.order(order.id) });
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      <p className="text-xs text-muted">
        Test cards: <span className="font-mono">4242 4242 4242 4242</span> pays, <span className="font-mono">4000 0000 0000 9995</span> is declined,{" "}
        <span className="font-mono">4000 0025 0000 3155</span> asks for 3-D Secure. Any future expiry and CVC.
      </p>
      <PaymentElement />
      <Button type="submit" size="lg" className="w-full" loading={paying} disabled={!stripe}>
        Pay now
      </Button>
      {message ? <p className="text-sm text-muted" aria-live="polite">{message}</p> : null}
    </form>
  );
}
