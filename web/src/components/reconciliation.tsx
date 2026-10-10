"use client";

import { useQueryClient } from "@tanstack/react-query";
import { RefreshCw } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { ErrorState } from "@/components/states";
import { Button } from "@/components/ui/button";
import { Card, CardBody, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { describeError, useApi } from "@/lib/api";
import { formatDateTime, shortId } from "@/lib/format";
import { keys, useLastReconciliation } from "@/lib/queries";
import type { ReconciliationSummary } from "@/lib/types";

export function Reconciliation() {
  const api = useApi();
  const queryClient = useQueryClient();
  const last = useLastReconciliation();
  const [running, setRunning] = useState(false);

  async function run() {
    setRunning(true);
    try {
      const summary = await api.post<ReconciliationSummary>("payment", "/admin/reconciliation/run");
      queryClient.setQueryData(keys.reconciliation, summary);
      toast.success("Reconciliation finished", { description: `${summary.checked} checked, ${summary.drifted} corrected.` });
    } catch (error) {
      toast.error("Reconciliation did not run", { description: describeError(error) });
    } finally {
      setRunning(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <div>
          <CardTitle>Reconciliation with Stripe</CardTitle>
          <CardDescription>
            Asks Stripe about payments that went quiet and applies what Stripe says, through the same state machine as a webhook. It is how a lost webhook heals.
            It looks at unfinished PaymentIntents that have not changed for a while; refunds and disputes are not checked.
          </CardDescription>
        </div>
        <Button onClick={run} loading={running}>
          <RefreshCw /> Run now
        </Button>
      </CardHeader>
      <CardBody>
        {last.isError ? (
          <ErrorState error={last.error} onRetry={() => last.refetch()} title="The last run did not load" />
        ) : last.isPending ? (
          <Skeleton className="h-24" />
        ) : last.data === null ? (
          <p className="text-sm text-muted">No run since this payment-service instance started. Run it now to see what it finds.</p>
        ) : (
          <Summary summary={last.data} />
        )}
      </CardBody>
    </Card>
  );
}

function Summary({ summary }: { summary: ReconciliationSummary }) {
  const figures = [
    { label: "Checked", value: summary.checked },
    { label: "Corrected", value: summary.drifted },
    { label: "Unchanged", value: summary.unchanged },
    { label: "Failed", value: summary.failed },
    { label: "Deferred", value: summary.deferred },
  ];
  return (
    <div className="space-y-5">
      <p className="text-xs text-muted">
        {summary.trigger === "MANUAL" ? "Run by hand" : "Scheduled run"}, finished {formatDateTime(summary.finishedAt)}.
      </p>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {figures.map((figure) => (
          <div key={figure.label} className="rounded-lg bg-surface-2 p-3">
            <p className="text-xs text-muted">{figure.label}</p>
            <p className="font-display text-2xl font-semibold tabular">{figure.value}</p>
          </div>
        ))}
      </div>
      {summary.drifts.length > 0 ? (
        <div>
          <p className="mb-2 text-sm font-medium">What it corrected</p>
          <ul className="divide-y divide-line rounded-lg border border-line text-sm">
            {summary.drifts.map((drift) => (
              <li key={drift.paymentId} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                <span className="font-mono text-xs">{shortId(drift.paymentId)}</span>
                <span className="text-muted">
                  {drift.from} <span aria-hidden>→</span> <span className="sr-only">to</span> <span className="font-medium text-ink">{drift.to}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
