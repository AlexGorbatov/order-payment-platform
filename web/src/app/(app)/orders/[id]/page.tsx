"use client";

import { ArrowLeft, SearchX } from "lucide-react";
import Link from "next/link";
import { use, useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/components/auth-provider";
import { JourneyLegend, JourneyRail } from "@/components/journey-rail";
import { OrderActions } from "@/components/order-actions";
import { PageHeader } from "@/components/page-header";
import { PaymentPanel } from "@/components/payment-panel";
import { StripeSimulatorCard } from "@/components/simulator";
import { EmptyState, ErrorState } from "@/components/states";
import { OrderStatusBadge } from "@/components/status-badge";
import { Card, CardBody, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError } from "@/lib/api";
import { formatDateTime, formatMoney, shortId } from "@/lib/format";
import { buildJourney } from "@/lib/journey";
import { keys, useOrder, usePayment } from "@/lib/queries";
import { ORDER_STATUS, orderIsInMotion } from "@/lib/status";

export default function OrderPage({ params }: PageProps<"/orders/[id]">) {
  const { id } = use(params);
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const order = useOrder(id);
  const inMotion = order.data ? orderIsInMotion(order.data.status) : false;
  const payment = usePayment(id, inMotion);
  const status = order.data?.status;

  // The payment is asked again whenever the order's status moves, so the two panels never disagree.
  useEffect(() => {
    if (status) void queryClient.invalidateQueries({ queryKey: keys.payment(id) });
  }, [status, id, queryClient]);

  const back = user.isAdmin ? { href: "/admin", label: "All orders" } : { href: "/orders", label: "My orders" };

  if (order.isError) {
    const missing = order.error instanceof ApiError && order.error.status === 404;
    return missing ? (
      <EmptyState
        icon={<SearchX />}
        title="Order not found"
        action={
          <Link href={back.href} className="text-sm font-medium text-accent hover:underline">
            Back to {back.label.toLowerCase()}
          </Link>
        }
      >
        There is no order with this id, or it belongs to someone else.
      </EmptyState>
    ) : (
      <ErrorState error={order.error} onRetry={() => order.refetch()} title="The order did not load" />
    );
  }
  if (!order.data) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-12 w-72" />
        <Skeleton className="h-52" />
        <div className="grid gap-4 lg:grid-cols-2">
          <Skeleton className="h-64" />
          <Skeleton className="h-64" />
        </div>
      </div>
    );
  }

  const data = order.data;
  const isOwner = data.customerId === user.subject;
  const journey = buildJourney(data, payment.data);

  return (
    <>
      <Link href={back.href} className="mb-4 inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4" /> {back.label}
      </Link>
      <PageHeader
        eyebrow={<span className="font-mono normal-case tracking-normal">{data.id}</span>}
        title={
          <span className="flex flex-wrap items-center gap-3">
            Order {shortId(data.id)}
            <OrderStatusBadge status={data.status} />
          </span>
        }
        description={ORDER_STATUS[data.status].hint}
        actions={<OrderActions order={data} isOwner={isOwner} isAdmin={user.isAdmin} />}
      />

      <Card className="mb-4">
        <CardHeader>
          <div>
            <CardTitle>Journey</CardTitle>
            <CardDescription>Each lane belongs to one service; they only talk through Kafka. This view refreshes while the order is moving.</CardDescription>
          </div>
          <JourneyLegend />
        </CardHeader>
        <CardBody>
          <JourneyRail journey={journey} live={inMotion} />
        </CardBody>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>Items</CardTitle>
              <CardDescription>
                Placed {formatDateTime(data.createdAt)}
                {user.isAdmin ? <> by <span className="font-mono text-xs">{shortId(data.customerId)}</span></> : null}
              </CardDescription>
            </div>
            {data.disputed ? <span className="rounded-full bg-fail-soft px-2.5 py-0.5 text-xs font-medium text-fail">disputed</span> : null}
          </CardHeader>
          <CardBody>
            <ul className="divide-y divide-line">
              {data.items.map((item) => (
                <li key={item.sku} className="flex items-baseline justify-between gap-4 py-3 first:pt-0">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{item.name}</p>
                    <p className="text-xs text-muted tabular">
                      {item.quantity} × {formatMoney(item.unitPrice)}
                    </p>
                  </div>
                  <p className="text-sm tabular">{formatMoney(item.lineTotal)}</p>
                </li>
              ))}
            </ul>
            <div className="mt-2 flex items-baseline justify-between border-t border-line pt-4">
              <span className="text-muted">Total</span>
              <span className="font-display text-3xl font-semibold tabular">{formatMoney(data.total)}</span>
            </div>
          </CardBody>
        </Card>

        <PaymentPanel order={data} payment={payment.isPending ? undefined : (payment.data ?? null)} isOwner={isOwner} />
      </div>

      <div className="mt-4">
        <StripeSimulatorCard orderId={data.id} />
      </div>
    </>
  );
}
