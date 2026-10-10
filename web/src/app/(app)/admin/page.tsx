"use client";

import { Ban, CircleDollarSign, ListOrdered, Undo2, Hourglass, ShieldAlert, Search } from "lucide-react";
import { useMemo, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { OrdersTable, Pagination } from "@/components/orders-table";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { formatMoney } from "@/lib/format";
import { useOrders } from "@/lib/queries";
import { ORDER_STATUS } from "@/lib/status";
import type { OrderStatus, OrderSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

const FILTERS: ("ALL" | OrderStatus)[] = ["ALL", "PENDING_PAYMENT", "PAID", "REFUND_REQUESTED", "REFUNDED", "REFUND_FAILED", "CANCELLED"];

export default function AdminPage() {
  const { user } = useAuth();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<"ALL" | OrderStatus>("ALL");
  const [search, setSearch] = useState("");
  const orders = useOrders(page, 20, user.isAdmin);
  const recent = useOrders(0, 100, user.isAdmin);

  const visible = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (orders.data?.content ?? []).filter(
      (order) => (status === "ALL" || order.status === status) && (!term || order.id.toLowerCase().includes(term) || order.customerId.toLowerCase().includes(term)),
    );
  }, [orders.data, status, search]);

  if (!user.isAdmin) {
    return (
      <EmptyState icon={<ShieldAlert />} title="The back office is for admins">
        Sign in as <span className="font-mono">admin1</span> to see every order and issue refunds.
      </EmptyState>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="Back office"
        title={<>Every order, as the <span className="gradient-text">services see it</span></>}
        description="Open an order to see its journey and to refund it. Refunds are always for the full amount and finish when Stripe confirms."
      />

      <Kpis orders={recent.data?.content} total={recent.data?.totalElements} loading={recent.isPending} />

      <div className="mb-4 mt-8 flex flex-wrap items-center gap-3">
        <div className="relative min-w-56 flex-1 sm:max-w-xs">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
          <Input className="pl-9" placeholder="Find by order or customer id" value={search} onChange={(event) => setSearch(event.target.value)} aria-label="Find an order" />
        </div>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filter by status">
          {FILTERS.map((filter) => (
            <button
              key={filter}
              aria-pressed={status === filter}
              onClick={() => setStatus(filter)}
              className={cn(
                "rounded-full border border-line px-3 py-1 text-xs font-medium text-muted transition-colors hover:text-ink",
                status === filter && "border-accent bg-accent-soft text-accent-fg hover:text-accent-fg",
              )}
            >
              {filter === "ALL" ? "All" : ORDER_STATUS[filter].label}
            </button>
          ))}
        </div>
      </div>

      {orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => orders.refetch()} title="The orders did not load" />
      ) : orders.isPending ? (
        <Skeleton className="h-72" />
      ) : visible.length === 0 ? (
        <EmptyState icon={<ListOrdered />} title={orders.data.totalElements === 0 ? "No orders yet" : "No order matches"}>
          {orders.data.totalElements === 0
            ? "Orders placed by customers show up here."
            : "Filters apply to this page of results. Clear them, or go to another page."}
        </EmptyState>
      ) : (
        <>
          <OrdersTable orders={visible} showCustomer />
          <Pagination page={orders.data.page} totalPages={orders.data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  );
}

function Kpis({ orders, total, loading }: { orders?: OrderSummary[]; total?: number; loading: boolean }) {
  if (loading || !orders) {
    return (
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        {Array.from({ length: 5 }, (_, i) => (
          <Skeleton key={i} className="h-28" />
        ))}
      </div>
    );
  }
  const count = (...statuses: OrderStatus[]) => orders.filter((o) => statuses.includes(o.status)).length;
  const paid = orders.filter((o) => o.status === "PAID");
  const gross = paid.reduce((sum, o) => sum + o.total.amountMinor, 0);
  const cards = [
    { label: "Orders", value: String(total ?? orders.length), hint: "in total", icon: ListOrdered, tone: "text-ink" },
    { label: "Awaiting payment", value: String(count("PENDING_PAYMENT")), hint: "in the latest 100", icon: Hourglass, tone: "text-flight" },
    { label: "Paid", value: formatMoney({ amountMinor: gross, currency: "EUR" }), hint: `${paid.length} ${paid.length === 1 ? "order" : "orders"} in the latest 100`, icon: CircleDollarSign, tone: "text-settle" },
    { label: "Refunds", value: String(count("REFUND_REQUESTED", "REFUNDED", "REFUND_FAILED")), hint: `${count("REFUND_FAILED")} failed`, icon: Undo2, tone: "text-refund" },
    { label: "Cancelled", value: String(count("CANCELLED")), hint: `${orders.filter((o) => o.disputed).length} disputed`, icon: Ban, tone: "text-muted" },
  ];
  return (
    <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
      {cards.map((card, index) => (
        <Card key={card.label} className="anim-rise p-4" style={{ animationDelay: `${index * 40}ms` }}>
          <div className="flex items-center justify-between">
            <span className="text-sm text-muted">{card.label}</span>
            <card.icon className={cn("size-4", card.tone)} aria-hidden />
          </div>
          <p className="mt-3 font-display text-3xl font-semibold tabular">{card.value}</p>
          <p className="mt-1 text-xs text-muted">{card.hint}</p>
        </Card>
      ))}
    </div>
  );
}
