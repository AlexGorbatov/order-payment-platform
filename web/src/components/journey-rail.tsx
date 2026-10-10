"use client";

import { motion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { formatClock, formatOffset } from "@/lib/format";
import type { Journey, JourneyEvent } from "@/lib/journey";
import type { Tone } from "@/lib/status";
import { cn } from "@/lib/utils";

const ring: Record<Tone, string> = {
  settle: "border-settle bg-settle",
  flight: "border-flight bg-flight",
  fail: "border-fail bg-fail",
  refund: "border-refund bg-refund",
  neutral: "border-muted bg-surface",
};

const LANES = [
  { id: "order" as const, name: "order-service", note: "owns the order" },
  { id: "payment" as const, name: "payment-service", note: "owns the payment, talks to Stripe" },
];

/**
 * The order's journey on two lanes, one per service, in the order things happened. Columns are shared, so what happened
 * on one lane before something on the other reads left to right. The dashed node is what the system waits for.
 */
export function JourneyRail({ journey, live }: { journey: Journey; live: boolean }) {
  const { events, next } = journey;
  const columns = events.length + (next ? 1 : 0);
  const first = events[0]?.at;
  const scroller = useRef<HTMLDivElement>(null);
  const [scrolled, setScrolled] = useState(false);

  // A long journey scrolls sideways: keep the newest event in view as it arrives.
  useEffect(() => {
    const element = scroller.current;
    if (element && element.scrollWidth > element.clientWidth) {
      element.scrollTo({ left: element.scrollWidth, behavior: "smooth" });
    }
  }, [columns]);

  return (
    <div className="overflow-x-auto" ref={scroller} onScroll={(event) => setScrolled(event.currentTarget.scrollLeft > 0)}>
      <div className="space-y-1 pb-1" style={{ minWidth: `${Math.max(columns, 3) * 6.75 + 9}rem` }}>
        {LANES.map((lane) => {
          const mine = events.map((event, column) => ({ event, column })).filter((entry) => entry.event.lane === lane.id);
          const nextHere = next?.lane === lane.id ? events.length : null;
          const columnsInLane = [...mine.map((m) => m.column), ...(nextHere !== null ? [nextHere] : [])];
          const from = Math.min(...columnsInLane);
          const to = Math.max(...columnsInLane);

          return (
            <div key={lane.id} className="flex items-stretch gap-4">
              <div className={cn("sticky left-0 z-20 w-32 shrink-0 pt-0.5 pr-2", scrolled && "bg-surface")}>
                <p className="font-mono text-xs font-medium">{lane.name}</p>
                <p className="text-[11px] leading-snug text-muted">{lane.note}</p>
              </div>
              <div className="relative flex-1 pb-6" style={{ display: "grid", gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
                {columnsInLane.length > 1 ? (
                  <div
                    className="absolute top-[7px] h-0.5 bg-line"
                    style={{ left: `${((from + 0.5) / columns) * 100}%`, width: `${((to - from) / columns) * 100}%` }}
                    aria-hidden
                  />
                ) : null}
                {mine.map(({ event, column }) => (
                  <Node key={event.key} event={event} column={column} first={first} />
                ))}
                {nextHere !== null && next ? (
                  <div className="relative flex flex-col items-center px-1 text-center" style={{ gridColumn: nextHere + 1 }}>
                    <span className={cn("relative z-10 size-4 rounded-full border-2 border-dashed border-muted bg-bg", live && "animate-pulse")} aria-hidden />
                    <p className="mt-2 text-xs text-muted">{next.title}</p>
                  </div>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function Node({ event, column, first }: { event: JourneyEvent; column: number; first?: string }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.28, ease: [0.2, 0.8, 0.2, 1] }}
      className="relative flex flex-col items-center px-1 text-center"
      style={{ gridColumn: column + 1 }}
    >
      <span className={cn("relative z-10 size-4 rounded-full border-2 ring-4 ring-surface", ring[event.tone])} aria-hidden />
      <p className="mt-2 text-xs font-medium leading-tight">{event.title}</p>
      {event.detail ? <p className="mt-0.5 break-all font-mono text-[10px] leading-tight text-muted">{event.detail}</p> : null}
      <p className="mt-1 font-mono text-[10px] text-muted tabular">
        {formatClock(event.at)}
        {first && event.at !== first ? <span className="ml-1 opacity-70">{formatOffset(first, event.at)}</span> : null}
      </p>
    </motion.div>
  );
}

export function JourneyLegend() {
  return (
    <div className="flex flex-wrap gap-2">
      <Badge tone="settle">landed</Badge>
      <Badge tone="flight">in flight</Badge>
      <Badge tone="refund">went back</Badge>
      <Badge tone="fail">failed</Badge>
    </div>
  );
}
