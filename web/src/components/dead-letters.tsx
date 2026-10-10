"use client";

import { useQueryClient } from "@tanstack/react-query";
import { Eye, Inbox, RotateCw, Check } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyState, ErrorState } from "@/components/states";
import { Pagination } from "@/components/orders-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/input";
import { describeError, useApi } from "@/lib/api";
import { formatDateTime, shortId } from "@/lib/format";
import { useDeadLetters } from "@/lib/queries";
import type { DeadLetter, DeadLetterDetail, DeadLetterStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

const SERVICES = [
  { id: "order" as const, label: "order-service", topic: "payment.events.v1" },
  { id: "payment" as const, label: "payment-service", topic: "order.events.v1" },
];
const STATUSES: DeadLetterStatus[] = ["NEW", "REPLAYED", "RESOLVED"];
const STATUS_TONE = { NEW: "fail", REPLAYED: "flight", RESOLVED: "settle" } as const;

function Segmented<T extends string>({ value, options, onChange, label }: { value: T; options: { id: T; label: string }[]; onChange: (value: T) => void; label: string }) {
  return (
    <div className="inline-flex rounded-lg bg-surface-2 p-1" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.id}
          aria-pressed={value === option.id}
          onClick={() => onChange(option.id)}
          className={cn("rounded-md px-3 py-1.5 text-sm font-medium text-muted transition-colors hover:text-ink", value === option.id && "bg-surface text-ink shadow-card")}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function DeadLetters() {
  const [service, setService] = useState<"order" | "payment">("order");
  const [status, setStatus] = useState<DeadLetterStatus>("NEW");
  const [page, setPage] = useState(0);
  const letters = useDeadLetters(service, status, page);
  const meta = SERVICES.find((s) => s.id === service)!;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Segmented label="Service" value={service} options={SERVICES.map((s) => ({ id: s.id, label: s.label }))} onChange={(v) => { setService(v); setPage(0); }} />
        <Segmented label="Status" value={status} options={STATUSES.map((s) => ({ id: s, label: s.charAt(0) + s.slice(1).toLowerCase() }))} onChange={(v) => { setStatus(v); setPage(0); }} />
        <p className="ml-auto text-xs text-muted">
          {meta.label} consumes <span className="font-mono">{meta.topic}</span>, so its dead letters come from there.
        </p>
      </div>

      {letters.isError ? (
        <ErrorState error={letters.error} onRetry={() => letters.refetch()} title="Dead letters did not load" />
      ) : letters.isPending ? (
        <Skeleton className="h-48" />
      ) : letters.data.items.length === 0 ? (
        <EmptyState icon={<Inbox />} title={status === "NEW" ? "Nothing waits for you" : `No ${status.toLowerCase()} dead letters`}>
          {status === "NEW" ? "Every event that was consumed was handled. A record that cannot be processed lands here after its retries." : "Nothing here."}
        </EmptyState>
      ) : (
        <>
          <div className="overflow-hidden rounded-card border border-line bg-surface shadow-card">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line bg-surface-2/60 text-left text-xs uppercase tracking-wider text-muted">
                    <th className="px-4 py-3 font-medium">Received</th>
                    <th className="px-4 py-3 font-medium">Order</th>
                    <th className="px-4 py-3 font-medium">Why it failed</th>
                    <th className="px-4 py-3 font-medium">Status</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {letters.data.items.map((letter) => (
                    <LetterRow key={letter.id} letter={letter} service={service} />
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <Pagination page={letters.data.page} totalPages={letters.data.totalPages} onPage={setPage} />
        </>
      )}
    </div>
  );
}

function LetterRow({ letter, service }: { letter: DeadLetter; service: "order" | "payment" }) {
  return (
    <tr className="align-top">
      <td className="px-4 py-3 text-muted">{formatDateTime(letter.createdAt)}</td>
      <td className="px-4 py-3 font-mono text-xs">{letter.messageKey ? shortId(letter.messageKey) : "-"}</td>
      <td className="max-w-md px-4 py-3">
        <p className="font-mono text-xs">{letter.exceptionClass?.split(".").pop()}</p>
        <p className="mt-0.5 line-clamp-2 text-muted">{letter.exceptionMessage}</p>
        {letter.note ? <p className="mt-1 text-xs text-muted">Note: {letter.note}</p> : null}
      </td>
      <td className="px-4 py-3">
        <Badge tone={STATUS_TONE[letter.status]}>{letter.status.toLowerCase()}</Badge>
      </td>
      <td className="px-4 py-3">
        <div className="flex justify-end gap-1.5">
          <Inspect letter={letter} service={service} />
          {letter.status === "NEW" ? <Replay letter={letter} service={service} /> : null}
          {letter.status !== "RESOLVED" ? <Resolve letter={letter} service={service} /> : null}
        </div>
      </td>
    </tr>
  );
}

function useRefresh() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ["dead-letters"] });
}

function Inspect({ letter, service }: { letter: DeadLetter; service: "order" | "payment" }) {
  const api = useApi();
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<DeadLetterDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function show() {
    setOpen(true);
    setError(null);
    try {
      setDetail(await api.get<DeadLetterDetail>(service, `/admin/dead-letters/${letter.id}`));
    } catch (e) {
      setError(describeError(e));
    }
  }
  return (
    <>
      <Button size="sm" variant="ghost" onClick={show} aria-label="View the dead letter">
        <Eye />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title="Dead letter" description={`${letter.originalTopic}, key ${letter.messageKey ?? "none"}`} className="max-w-2xl">
          {error ? <p className="text-sm text-fail">{error}</p> : null}
          {detail ? (
            <div className="space-y-4 text-sm">
              <div>
                <p className="text-xs uppercase tracking-wider text-muted">Exception</p>
                <p className="mt-1 break-all font-mono text-xs">{detail.exceptionClass}</p>
                <p className="mt-1 text-muted">{detail.exceptionMessage}</p>
              </div>
              <div>
                <p className="text-xs uppercase tracking-wider text-muted">Payload</p>
                <pre className="mt-1 max-h-56 overflow-auto rounded-lg bg-surface-2 p-3 font-mono text-xs">{detail.payload}</pre>
              </div>
              <p className="text-xs text-muted">The payload may contain personal data. Do not paste it into tickets.</p>
            </div>
          ) : !error ? (
            <Skeleton className="h-32" />
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  );
}

function Replay({ letter, service }: { letter: DeadLetter; service: "order" | "payment" }) {
  const api = useApi();
  const refresh = useRefresh();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  async function replay() {
    setBusy(true);
    try {
      await api.post(service, `/admin/dead-letters/${letter.id}/replay`);
      toast.success("Replay queued", { description: "The event goes back to its topic through the outbox." });
      setOpen(false);
      await refresh();
    } catch (error) {
      toast.error("The replay was not queued", { description: describeError(error) });
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Button size="sm" variant="secondary" onClick={() => setOpen(true)}>
        <RotateCw /> Replay
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title="Replay this event?" description="A dead letter can be replayed once.">
          <p className="text-sm text-muted">
            The unchanged event is published again to <span className="font-mono">{letter.originalTopic}</span>. Replay it when the cause is fixed. If it fails again it becomes a new
            dead letter. A record that is not a valid event cannot be replayed; resolve it instead.
          </p>
          <div className="mt-6 flex justify-end gap-2">
            <DialogClose asChild>
              <Button variant="secondary">Not now</Button>
            </DialogClose>
            <Button loading={busy} onClick={replay}>
              Replay event
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Resolve({ letter, service }: { letter: DeadLetter; service: "order" | "payment" }) {
  const api = useApi();
  const refresh = useRefresh();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [comment, setComment] = useState("");

  async function resolve() {
    setBusy(true);
    try {
      await api.post(service, `/admin/dead-letters/${letter.id}/resolve`, { body: { comment: comment.trim() } });
      toast.success("Dead letter resolved");
      setOpen(false);
      setComment("");
      await refresh();
    } catch (error) {
      toast.error("It was not resolved", { description: describeError(error) });
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Button size="sm" variant="ghost" onClick={() => setOpen(true)}>
        <Check /> Resolve
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title="Resolve this dead letter" description="Say what was decided and why. The comment is kept with it.">
          <Textarea value={comment} onChange={(event) => setComment(event.target.value)} maxLength={1000} placeholder="For example: producer bug, order cancelled by hand, event discarded." aria-label="Comment" />
          <div className="mt-6 flex justify-end gap-2">
            <DialogClose asChild>
              <Button variant="secondary">Cancel</Button>
            </DialogClose>
            <Button loading={busy} disabled={comment.trim().length === 0} onClick={resolve}>
              Resolve
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
