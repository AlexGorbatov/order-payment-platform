"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useApi } from "@/lib/api";
import { orderIsInMotion } from "@/lib/status";
import type { DeadLetterPage, DeadLetterStatus, Order, OrderSummary, Page, Payment, Product, ReconciliationSummary } from "@/lib/types";

export const keys = {
  products: ["products"] as const,
  orders: (page: number, size: number) => ["orders", page, size] as const,
  order: (id: string) => ["order", id] as const,
  payment: (orderId: string) => ["payment", orderId] as const,
  deadLetters: (service: string, status: string, page: number) => ["dead-letters", service, status, page] as const,
  reconciliation: ["reconciliation"] as const,
};

export function useProducts() {
  const api = useApi();
  return useQuery({ queryKey: keys.products, queryFn: () => api.get<Product[]>("order", "/api/v1/products"), staleTime: 5 * 60_000 });
}

export function useOrders(page: number, size: number, enabled = true) {
  const api = useApi();
  return useQuery({
    queryKey: keys.orders(page, size),
    queryFn: ({ signal }) => api.get<Page<OrderSummary>>("order", `/api/v1/orders?page=${page}&size=${size}`, signal),
    placeholderData: keepPreviousData,
    refetchInterval: 8_000,
    enabled,
  });
}

/** An order, asked again every two seconds while its status is still moving. */
export function useOrder(id: string) {
  const api = useApi();
  return useQuery({
    queryKey: keys.order(id),
    queryFn: ({ signal }) => api.get<Order>("order", `/api/v1/orders/${id}`, signal),
    refetchInterval: (query) => (query.state.data && orderIsInMotion(query.state.data.status) ? 2_000 : false),
  });
}

/** The payment of an order; null until payment-service has created it (it answers 404 until then). */
export function usePayment(orderId: string, polling: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: keys.payment(orderId),
    queryFn: async ({ signal }) => {
      try {
        return await api.get<Payment>("payment", `/api/v1/payments/by-order/${orderId}`, signal);
      } catch (error) {
        if (error instanceof Error && "status" in error && (error as { status: number }).status === 404) return null;
        throw error;
      }
    },
    refetchInterval: polling ? 2_000 : false,
  });
}

export function useDeadLetters(service: "order" | "payment", status: DeadLetterStatus, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: keys.deadLetters(service, status, page),
    queryFn: ({ signal }) => api.get<DeadLetterPage>(service, `/admin/dead-letters?status=${status}&page=${page}&size=20`, signal),
    placeholderData: keepPreviousData,
    refetchInterval: 10_000,
  });
}

export function useLastReconciliation() {
  const api = useApi();
  return useQuery({
    queryKey: keys.reconciliation,
    queryFn: async ({ signal }) => {
      try {
        return await api.get<ReconciliationSummary>("payment", "/admin/reconciliation/last", signal);
      } catch (error) {
        if (error instanceof Error && "status" in error && (error as { status: number }).status === 404) return null;
        throw error;
      }
    },
  });
}
