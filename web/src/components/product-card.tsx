"use client";

import { BookOpen, Coffee, Minus, Package, Plus, Shirt, Sticker } from "lucide-react";
import { Button } from "@/components/ui/button";
import { formatMoney } from "@/lib/format";
import { MAX_QUANTITY, useCart } from "@/lib/cart";
import type { Product } from "@/lib/types";

function Glyph({ sku, className }: { sku: string; className?: string }) {
  const props = { className, strokeWidth: 1.4, "aria-hidden": true };
  if (sku.startsWith("BOOK")) return <BookOpen {...props} />;
  if (sku.startsWith("MUG")) return <Coffee {...props} />;
  if (sku.startsWith("TSHIRT")) return <Shirt {...props} />;
  if (sku.startsWith("STICKER")) return <Sticker {...props} />;
  return <Package {...props} />;
}

export function ProductCard({ product, index }: { product: Product; index: number }) {
  const { quantityOf, setQuantity } = useCart();
  const quantity = quantityOf(product.sku);

  return (
    <article
      className="anim-rise group flex flex-col overflow-hidden rounded-card border border-line bg-surface shadow-card"
      style={{ animationDelay: `${index * 40}ms` }}
    >
      <div className="relative grid h-36 place-items-center bg-surface-2">
        <div className="absolute inset-0 opacity-[0.55] [background:radial-gradient(circle_at_30%_20%,var(--accent-soft),transparent_60%)]" aria-hidden />
        <Glyph sku={product.sku} className="relative size-12 text-accent transition-transform duration-300 group-hover:scale-110" />
        <span className="absolute bottom-2 left-3 font-mono text-[10px] uppercase tracking-widest text-muted">{product.sku}</span>
      </div>
      <div className="flex flex-1 flex-col gap-4 p-4">
        <h3 className="font-medium leading-snug">{product.name}</h3>
        <div className="mt-auto flex items-end justify-between gap-3">
          <p className="font-display text-2xl font-semibold tabular">{formatMoney(product.price)}</p>
          {quantity === 0 ? (
            <Button size="sm" onClick={() => setQuantity(product.sku, 1)}>
              <Plus /> Add
            </Button>
          ) : (
            <div className="flex items-center gap-1 rounded-lg border border-line bg-bg p-0.5" role="group" aria-label={`Quantity of ${product.name}`}>
              <Button size="icon" variant="ghost" className="size-8" aria-label="Remove one" onClick={() => setQuantity(product.sku, quantity - 1)}>
                <Minus />
              </Button>
              <span className="w-6 text-center text-sm font-medium tabular" aria-live="polite">
                {quantity}
              </span>
              <Button size="icon" variant="ghost" className="size-8" aria-label="Add one" disabled={quantity >= MAX_QUANTITY} onClick={() => setQuantity(product.sku, quantity + 1)}>
                <Plus />
              </Button>
            </div>
          )}
        </div>
      </div>
    </article>
  );
}
