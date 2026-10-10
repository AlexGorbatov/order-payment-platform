"use client";

import { Receipt } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { OrdersTable, Pagination } from "@/components/orders-table";
import { PageHeader } from "@/components/page-header";
import { EmptyState, ErrorState } from "@/components/states";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useOrders } from "@/lib/queries";

export default function MyOrdersPage() {
  const [page, setPage] = useState(0);
  const orders = useOrders(page, 10);

  return (
    <>
      <PageHeader
        eyebrow="Orders"
        title="My orders"
        description="Newest first. Open one to see its journey through the services, to pay it, or to cancel it."
        actions={
          <Button asChild>
            <Link href="/shop">New order</Link>
          </Button>
        }
      />
      {orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => orders.refetch()} title="Your orders did not load" />
      ) : orders.isPending ? (
        <div className="space-y-2">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-16" />
          ))}
        </div>
      ) : orders.data.totalElements === 0 ? (
        <EmptyState
          icon={<Receipt />}
          title="No orders yet"
          action={
            <Button asChild>
              <Link href="/shop">Browse the shop</Link>
            </Button>
          }
        >
          Place your first order and it will show up here with every status it goes through.
        </EmptyState>
      ) : (
        <>
          <OrdersTable orders={orders.data.content} />
          <Pagination page={orders.data.page} totalPages={orders.data.totalPages} onPage={setPage} />
        </>
      )}
    </>
  );
}
