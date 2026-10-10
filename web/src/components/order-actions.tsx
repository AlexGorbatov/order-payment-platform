"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Ban, Undo2 } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent } from "@/components/ui/dialog";
import { describeError, newIdempotencyKey, useApi } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { keys } from "@/lib/queries";
import { canCancel, canRefund } from "@/lib/status";
import type { Order } from "@/lib/types";

export function OrderActions({ order, isOwner, isAdmin }: { order: Order; isOwner: boolean; isAdmin: boolean }) {
  return (
    <>
      {isOwner && canCancel(order.status) ? <CancelOrder order={order} /> : null}
      {isAdmin && canRefund(order.status) ? <RefundOrder order={order} /> : null}
    </>
  );
}

function useOrderAction(order: Order, path: string, success: { title: string; description: string }, failure: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);

  async function run() {
    setBusy(true);
    try {
      await api.post<Order>("order", `/api/v1/orders/${order.id}/${path}`, { idempotencyKey: newIdempotencyKey() });
      toast.success(success.title, { description: success.description });
      setOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.order(order.id) }),
        queryClient.invalidateQueries({ queryKey: ["orders"] }),
      ]);
    } catch (error) {
      toast.error(failure, { description: describeError(error) });
    } finally {
      setBusy(false);
    }
  }
  return { busy, open, setOpen, run };
}

function CancelOrder({ order }: { order: Order }) {
  const { busy, open, setOpen, run } = useOrderAction(
    order,
    "cancel",
    { title: "Order cancelled", description: "The payment is being cancelled at Stripe." },
    "The order was not cancelled",
  );
  return (
    <>
      <Button variant="danger" onClick={() => setOpen(true)}>
        <Ban /> Cancel order
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title="Cancel this order?" description="You can only cancel an order that has not been paid.">
          <p className="text-sm text-muted">
            The order for <span className="font-medium text-ink tabular">{formatMoney(order.total)}</span> is cancelled and its payment is
            cancelled at Stripe. If a payment lands at the same moment, it is refunded automatically.
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <DialogClose asChild>
              <Button variant="secondary">Keep order</Button>
            </DialogClose>
            <Button variant="danger" loading={busy} onClick={run}>
              Cancel order
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

function RefundOrder({ order }: { order: Order }) {
  const retry = order.status === "REFUND_FAILED";
  const { busy, open, setOpen, run } = useOrderAction(
    order,
    "refund",
    { title: "Refund requested", description: "The money moves asynchronously; the order shows the result." },
    "The refund was not requested",
  );
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>
        <Undo2 /> {retry ? "Retry refund" : "Refund order"}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title={retry ? "Retry the refund?" : "Refund this order?"} description="Refunds are always for the full amount.">
          <p className="text-sm text-muted">
            <span className="font-medium text-ink tabular">{formatMoney(order.total)}</span> goes back to the customer. The order moves to
            “Refund in progress” and becomes “Refunded” when Stripe confirms.
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <DialogClose asChild>
              <Button variant="secondary">Not now</Button>
            </DialogClose>
            <Button loading={busy} onClick={run}>
              {retry ? "Retry refund" : "Refund order"}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
