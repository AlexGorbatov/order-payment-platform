"use client";

import { PackageSearch } from "lucide-react";
import { useAuth } from "@/components/auth-provider";
import { PageHeader } from "@/components/page-header";
import { ProductCard } from "@/components/product-card";
import { EmptyState, ErrorState } from "@/components/states";
import { Skeleton } from "@/components/ui/skeleton";
import { useProducts } from "@/lib/queries";

export default function ShopPage() {
  const { user } = useAuth();
  const products = useProducts();

  return (
    <>
      <PageHeader
        eyebrow="Catalog"
        title={<>Pick something <span className="gradient-text">to buy</span></>}
        description="Prices live on the server, so the total you see is the total you pay. Place an order, then watch it cross two services."
      />
      {!user.isCustomer ? (
        <div className="mb-6 rounded-card border border-line bg-surface-2 px-4 py-3 text-sm text-muted">
          Only customers can place orders. You can browse the catalog; sign in as <span className="font-mono text-ink">customer1</span> to buy.
        </div>
      ) : null}
      {products.isError ? (
        <ErrorState error={products.error} onRetry={() => products.refetch()} title="The catalog did not load" />
      ) : products.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-72" />
          ))}
        </div>
      ) : products.data.length === 0 ? (
        <EmptyState icon={<PackageSearch />} title="No products yet">
          The catalog is empty. Seed data is created by the order-service migrations.
        </EmptyState>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {products.data.map((product, index) => (
            <ProductCard key={product.sku} product={product} index={index} />
          ))}
        </div>
      )}
    </>
  );
}
