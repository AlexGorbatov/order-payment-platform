"use client";

import { useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Minus, Plus, ShoppingBag, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogTrigger, SheetContent } from "@/components/ui/dialog";
import { newIdempotencyKey, useApi, describeError } from "@/lib/api";
import { MAX_QUANTITY, useCart } from "@/lib/cart";
import { formatMoney } from "@/lib/format";
import { useProducts } from "@/lib/queries";
import type { Money, Order } from "@/lib/types";

export function CartButton() {
  const { count } = useCart();
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="secondary" size="md" aria-label={`Cart, ${count} items`} className="relative">
          <ShoppingBag />
          <span className="hidden sm:inline">Cart</span>
          {count > 0 ? (
            <span className="grid min-w-5 place-items-center rounded-full btn-gradient px-1.5 text-xs font-bold text-white !shadow-none tabular">{count}</span>
          ) : null}
        </Button>
      </DialogTrigger>
      <SheetContent title="Your cart">
        <CartBody onPlaced={() => setOpen(false)} />
      </SheetContent>
    </Dialog>
  );
}

function CartBody({ onPlaced }: { onPlaced: () => void }) {
  const { lines, setQuantity, clear, count } = useCart();
  const products = useProducts();
  const api = useApi();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [placing, setPlacing] = useState(false);
  // One key per cart content: pressing the button twice, or retrying after a timeout, can never place two orders.
  const attempt = useRef<{ signature: string; key: string } | null>(null);

  const priced = (products.data ?? []).filter((p) => lines[p.sku]);
  const total: Money = {
    amountMinor: priced.reduce((sum, p) => sum + p.price.amountMinor * lines[p.sku], 0),
    currency: priced[0]?.price.currency ?? "EUR",
  };

  async function place() {
    const items = Object.entries(lines).map(([sku, quantity]) => ({ sku, quantity }));
    const signature = JSON.stringify(items);
    if (attempt.current?.signature !== signature) attempt.current = { signature, key: newIdempotencyKey() };
    setPlacing(true);
    try {
      const order = await api.post<Order>("order", "/api/v1/orders", { body: { items }, idempotencyKey: attempt.current.key });
      attempt.current = null;
      clear();
      await queryClient.invalidateQueries({ queryKey: ["orders"] });
      toast.success("Order placed", { description: "Your payment is being prepared." });
      onPlaced();
      router.push(`/orders/${order.id}`);
    } catch (error) {
      toast.error("The order was not placed", { description: describeError(error) });
    } finally {
      setPlacing(false);
    }
  }

  if (count === 0) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center px-8 text-center">
        <ShoppingBag className="mb-3 size-8 text-muted" strokeWidth={1.4} />
        <p className="font-display text-lg font-semibold">Your cart is empty</p>
        <p className="mt-1 text-sm text-muted">Add a product from the shop and it will wait here.</p>
      </div>
    );
  }

  return (
    <>
      <ul className="flex-1 divide-y divide-line overflow-y-auto px-5">
        {priced.map((product) => (
          <li key={product.sku} className="flex items-center gap-3 py-4">
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{product.name}</p>
              <p className="text-sm text-muted tabular">{formatMoney(product.price)} each</p>
            </div>
            <div className="flex items-center gap-1 rounded-lg border border-line p-0.5">
              <Button size="icon" variant="ghost" className="size-7" aria-label={`Remove one ${product.name}`} onClick={() => setQuantity(product.sku, lines[product.sku] - 1)}>
                <Minus />
              </Button>
              <span className="w-5 text-center text-sm tabular">{lines[product.sku]}</span>
              <Button size="icon" variant="ghost" className="size-7" aria-label={`Add one ${product.name}`} disabled={lines[product.sku] >= MAX_QUANTITY} onClick={() => setQuantity(product.sku, lines[product.sku] + 1)}>
                <Plus />
              </Button>
            </div>
            <p className="w-20 text-right text-sm font-medium tabular">{formatMoney({ amountMinor: product.price.amountMinor * lines[product.sku], currency: product.price.currency })}</p>
            <Button size="icon" variant="ghost" className="size-7" aria-label={`Remove ${product.name}`} onClick={() => setQuantity(product.sku, 0)}>
              <Trash2 />
            </Button>
          </li>
        ))}
      </ul>
      <div className="space-y-4 border-t border-line p-5">
        <div className="flex items-baseline justify-between">
          <span className="text-muted">Total</span>
          <span className="font-display text-3xl font-semibold tabular">{formatMoney(total)}</span>
        </div>
        <Button className="w-full" size="lg" loading={placing} disabled={products.isPending} onClick={place}>
          Place order <ArrowRight />
        </Button>
        <p className="text-xs text-muted">Prices come from the catalog on the server. Nothing is charged until you pay in the next step, and this is Stripe test mode.</p>
      </div>
    </>
  );
}
