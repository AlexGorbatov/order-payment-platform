"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { useState } from "react";
import { Toaster } from "sonner";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 1_000,
            refetchOnWindowFocus: true,
            // a 4xx is an answer, not a glitch: do not ask again
            retry: (failureCount, error) => !(error instanceof Error && "status" in error && (error as { status: number }).status < 500) && failureCount < 2,
          },
        },
      }),
  );
  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem disableTransitionOnChange>
      <QueryClientProvider client={client}>
        {children}
        <Toaster position="bottom-right" toastOptions={{ classNames: { toast: "!rounded-lg !border !border-line !bg-surface !text-ink !shadow-card" } }} />
      </QueryClientProvider>
    </ThemeProvider>
  );
}
