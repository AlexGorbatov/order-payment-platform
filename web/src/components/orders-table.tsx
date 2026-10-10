"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { OrderStatusBadge } from "@/components/status-badge";
import { formatDateTime, formatMoney, formatRelative, shortId } from "@/lib/format";
import type { OrderSummary } from "@/lib/types";

export function OrdersTable({ orders, showCustomer }: { orders: OrderSummary[]; showCustomer?: boolean }) {
  const router = useRouter();
  return (
    <div className="overflow-hidden rounded-card border border-line bg-surface shadow-card">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line bg-surface-2/60 text-left text-xs uppercase tracking-wider text-muted">
              <th className="px-4 py-3 font-medium">Order</th>
              {showCustomer ? <th className="px-4 py-3 font-medium">Customer</th> : null}
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="hidden px-4 py-3 font-medium sm:table-cell">Lines</th>
              <th className="px-4 py-3 text-right font-medium">Total</th>
              <th className="hidden px-4 py-3 font-medium md:table-cell">Updated</th>
              <th className="w-8" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {orders.map((order) => (
              <tr key={order.id} className="group cursor-pointer transition-colors hover:bg-surface-2/50" onClick={() => router.push(`/orders/${order.id}`)}>
                <td className="px-4 py-3">
                  <Link href={`/orders/${order.id}`} className="font-mono text-[13px] font-medium hover:text-accent" onClick={(event) => event.stopPropagation()}>
                    {shortId(order.id)}
                  </Link>
                  <div className="text-xs text-muted">{formatDateTime(order.createdAt)}</div>
                </td>
                {showCustomer ? <td className="px-4 py-3 font-mono text-xs text-muted">{shortId(order.customerId)}</td> : null}
                <td className="px-4 py-3">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <OrderStatusBadge status={order.status} />
                    {order.disputed ? <span className="rounded-full bg-fail-soft px-2 py-0.5 text-xs font-medium text-fail">disputed</span> : null}
                  </div>
                </td>
                <td className="hidden px-4 py-3 text-muted tabular sm:table-cell">{order.lineCount}</td>
                <td className="px-4 py-3 text-right font-medium tabular">{formatMoney(order.total)}</td>
                <td className="hidden px-4 py-3 text-muted md:table-cell">{formatRelative(order.updatedAt)}</td>
                <td className="pr-3 text-muted">
                  <ChevronRight className="size-4 transition-transform group-hover:translate-x-0.5" aria-hidden />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav className="mt-4 flex items-center justify-between text-sm text-muted" aria-label="Pages">
      <span>
        Page {page + 1} of {totalPages}
      </span>
      <div className="flex gap-2">
        <button className="rounded-lg border border-line bg-surface px-3 py-1.5 hover:bg-surface-2 disabled:opacity-40" disabled={page === 0} onClick={() => onPage(page - 1)}>
          Previous
        </button>
        <button className="rounded-lg border border-line bg-surface px-3 py-1.5 hover:bg-surface-2 disabled:opacity-40" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next
        </button>
      </div>
    </nav>
  );
}
