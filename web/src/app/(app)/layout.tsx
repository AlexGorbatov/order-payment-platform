import { AppShell } from "@/components/app-shell";
import { AuthProvider } from "@/components/auth-provider";
import { CartProvider } from "@/lib/cart";

export default function AppLayout({ children }: LayoutProps<"/">) {
  return (
    <AuthProvider>
      <CartProvider>
        <AppShell>{children}</AppShell>
      </CartProvider>
    </AuthProvider>
  );
}
