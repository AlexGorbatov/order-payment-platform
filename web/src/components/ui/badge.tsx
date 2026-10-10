import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";
import type { Tone } from "@/lib/status";

const badge = cva("inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap", {
  variants: {
    tone: {
      settle: "bg-settle-soft text-settle",
      flight: "bg-flight-soft text-flight",
      fail: "bg-fail-soft text-fail",
      refund: "bg-refund-soft text-refund",
      neutral: "bg-surface-2 text-muted",
    } satisfies Record<Tone, string>,
  },
  defaultVariants: { tone: "neutral" },
});

const dot: Record<Tone, string> = {
  settle: "bg-settle",
  flight: "bg-flight",
  fail: "bg-fail",
  refund: "bg-refund",
  neutral: "bg-muted",
};

export function Badge({
  tone = "neutral",
  pulse,
  className,
  children,
}: VariantProps<typeof badge> & { pulse?: boolean; className?: string; children: React.ReactNode }) {
  const t = tone ?? "neutral";
  return (
    <span className={cn(badge({ tone }), className)}>
      <span className={cn("size-1.5 rounded-full", dot[t], pulse && "animate-pulse")} aria-hidden />
      {children}
    </span>
  );
}
