"use client";

import { Compass, LogOut, Package, ShieldCheck, ShoppingBag, Store, Wrench } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { CartButton } from "@/components/cart-sheet";
import { LogoMark } from "@/components/logo";
import { ThemeToggle } from "@/components/theme-toggle";
import { useAuth } from "@/components/auth-provider";
import { Badge } from "@/components/ui/badge";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

interface NavItem {
  href: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, config, logout } = useAuth();
  const pathname = usePathname();

  const nav: NavItem[] = [
    ...(user.isCustomer
      ? [
          { href: "/shop", label: "Shop", icon: Store },
          { href: "/orders", label: "My orders", icon: Package },
        ]
      : []),
    ...(user.isAdmin ? [{ href: "/admin", label: "Back office", icon: ShieldCheck }] : []),
    ...(user.isOps ? [{ href: "/ops", label: "Operations", icon: Wrench }] : []),
  ];
  const active = (href: string) => pathname === href || (href !== "/" && pathname.startsWith(`${href}/`));

  return (
    <div className="min-h-dvh md:grid md:grid-cols-[15rem_1fr]">
      <aside className="sticky top-0 hidden h-dvh flex-col border-r border-line bg-[var(--header-bg)] backdrop-blur-xl md:flex">
        <Link href="/" className="flex items-center gap-3 px-5 py-5">
          <LogoMark className="size-8" />
          <span className="font-display text-[15px] leading-tight">
            Orders &amp; payments
            <span className="eyebrow block !text-[10px] !font-normal">reference demo</span>
          </span>
        </Link>
        <nav className="flex flex-col gap-1 px-3 py-2" aria-label="Main">
          {nav.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active(item.href) ? "page" : undefined}
              className={cn(
                "flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold text-muted transition-colors hover:bg-ink/[0.06] hover:text-ink",
                active(item.href) && "bg-accent-soft text-accent-fg hover:bg-accent-soft hover:text-accent-fg",
              )}
            >
              <item.icon className="size-4" />
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="mt-auto space-y-3 border-t border-line p-4 text-xs text-muted">
          <p className="flex items-start gap-2">
            <Compass className="mt-0.5 size-3.5 shrink-0" />
            <span>
              Two services, one Kafka. Every status here comes from them, not from this app.
            </span>
          </p>
        </div>
      </aside>

      <div className="flex min-w-0 flex-col">
        <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-line bg-[var(--header-bg)] px-4 backdrop-blur-[18px] md:px-8">
          <Link href="/" className="flex items-center gap-2 md:hidden" aria-label="Home">
            <LogoMark className="size-7" />
          </Link>
          <nav className="flex gap-1 md:hidden" aria-label="Main">
            {nav.map((item) => (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active(item.href) ? "page" : undefined}
                className={cn("rounded-md px-2.5 py-1.5 text-sm font-medium text-muted", active(item.href) && "bg-accent-soft text-accent-fg")}
              >
                {item.label}
              </Link>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <Badge tone="flight" className="hidden sm:inline-flex">
              {config.paymentElement ? "Stripe test mode" : "Local mode · stripe-mock"}
            </Badge>
            {user.isCustomer ? <CartButton /> : null}
            <ThemeToggle />
            <DropdownMenu>
              <DropdownMenuTrigger className="flex items-center gap-2 rounded-full border border-ink/20 bg-ink/[0.04] py-1 pl-1 pr-3 text-sm font-semibold hover:bg-ink/[0.09]">
                <span className="btn-gradient grid size-7 place-items-center rounded-full text-xs font-bold uppercase text-white !shadow-none">
                  {user.name.slice(0, 1)}
                </span>
                <span className="hidden sm:inline">{user.username}</span>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuLabel>
                  Signed in as <span className="font-medium text-ink">{user.username}</span>
                  <span className="mt-0.5 block font-mono">{user.roles.filter((r) => ["customer", "admin", "ops"].includes(r)).join(" · ")}</span>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={logout}>
                  <LogOut /> Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 md:px-8">{children}</main>
        <footer className="border-t border-line px-4 py-4 text-xs text-muted md:px-8">
          <span className="inline-flex items-center gap-1.5">
            <ShoppingBag className="size-3.5" /> Test mode only: no real money moves.
          </span>
        </footer>
      </div>
    </div>
  );
}
