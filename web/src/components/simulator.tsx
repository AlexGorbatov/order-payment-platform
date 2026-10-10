"use client";

import { useQueryClient } from "@tanstack/react-query";
import { FlaskConical } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { useAuth } from "@/components/auth-provider";
import { Card, CardBody, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { describeError, useApi } from "@/lib/api";
import { keys } from "@/lib/queries";

export function useSimulate(orderId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  const [sending, setSending] = useState<string | null>(null);

  async function send(...events: string[]) {
    setSending(events[0]);
    try {
      for (const event of events) {
        await api.simulate(orderId, event);
      }
      toast.success("Webhook sent", { description: `${events.join(" + ")}, signed and delivered to payment-service.` });
      // the processor applies it within a second; the order query is already polling, the payment is asked again now
      await queryClient.invalidateQueries({ queryKey: keys.payment(orderId) });
    } catch (error) {
      toast.error("The webhook was not sent", { description: describeError(error) });
    } finally {
      setSending(null);
    }
  }
  return { send, sending };
}

const EVENTS = [
  { event: "payment_intent.succeeded", title: "Payment succeeded", text: "The card was charged." },
  { event: "payment_intent.payment_failed", title: "Payment failed", text: "A declined attempt; the order stays open." },
  { event: "payment_intent.requires_action", title: "Authentication required", text: "The customer must complete 3-D Secure." },
  { event: "charge.dispute.created", title: "Dispute opened", text: "The cardholder disputes the charge." },
  { event: "charge.refunded", title: "Refund settled", text: "Stripe confirms a refund went through." },
];

/**
 * Local mode only. stripe-mock answers API calls but never sends webhooks, so here the interface plays Stripe's part:
 * the app's server signs the event with the shared secret and delivers it to payment-service, the way the Stripe CLI would.
 */
export function StripeSimulatorCard({ orderId }: { orderId: string }) {
  const { config } = useAuth();
  const { send, sending } = useSimulate(orderId);
  if (!config.simulateWebhooks) return null;

  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle className="flex items-center gap-2">
            <FlaskConical className="size-4 text-flight" aria-hidden /> Play Stripe
          </CardTitle>
          <CardDescription>stripe-mock sends no webhooks, so send the ones Stripe would.</CardDescription>
        </div>
      </CardHeader>
      <CardBody className="grid gap-2 sm:grid-cols-2">
        {EVENTS.map((item) => (
          <button
            key={item.event}
            className="rounded-lg border border-line p-3 text-left transition-colors hover:bg-surface-2 disabled:opacity-50"
            disabled={sending !== null}
            onClick={() => send(item.event)}
          >
            <span className="block text-sm font-medium">{item.title}</span>
            <span className="block text-xs text-muted">{item.text}</span>
            <span className="mt-1 block font-mono text-[10px] text-muted">{item.event}</span>
          </button>
        ))}
      </CardBody>
    </Card>
  );
}
