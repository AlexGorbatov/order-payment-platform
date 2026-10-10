"use client";

import { Button } from "@/components/ui/button";

export default function Error({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="grid min-h-dvh place-items-center p-6 text-center" role="alert">
      <div>
        <h1 className="font-display text-3xl font-semibold">Something broke on this page</h1>
        <p className="mt-2 text-muted">Nothing was lost: your orders live in the services. Try the page again.</p>
        <Button className="mt-6" onClick={reset}>
          Try again
        </Button>
      </div>
    </div>
  );
}
