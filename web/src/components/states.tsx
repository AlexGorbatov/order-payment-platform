import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { describeError } from "@/lib/api";

export function EmptyState({
  icon,
  title,
  children,
  action,
}: {
  icon?: React.ReactNode;
  title: string;
  children?: React.ReactNode;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center rounded-card border border-dashed border-line px-6 py-14 text-center">
      {icon ? <div className="mb-3 grid size-11 place-items-center rounded-full bg-surface-2 text-muted [&_svg]:size-5">{icon}</div> : null}
      <h2 className="font-display text-lg font-semibold">{title}</h2>
      {children ? <p className="mt-1 max-w-sm text-sm text-muted">{children}</p> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}

export function ErrorState({ error, onRetry, title = "This did not load" }: { error: unknown; onRetry?: () => void; title?: string }) {
  return (
    <div className="flex flex-col items-center rounded-card border border-fail/30 bg-fail-soft px-6 py-10 text-center" role="alert">
      <AlertTriangle className="mb-3 size-6 text-fail" aria-hidden />
      <h2 className="font-display text-lg font-semibold">{title}</h2>
      <p className="mt-1 max-w-md text-sm text-muted">{describeError(error)}</p>
      {onRetry ? (
        <Button className="mt-4" variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  );
}
