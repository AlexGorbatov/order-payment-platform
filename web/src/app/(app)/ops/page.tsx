"use client";

import { ShieldAlert } from "lucide-react";
import { useAuth } from "@/components/auth-provider";
import { DeadLetters } from "@/components/dead-letters";
import { PageHeader } from "@/components/page-header";
import { Reconciliation } from "@/components/reconciliation";
import { EmptyState } from "@/components/states";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

export default function OperationsPage() {
  const { user } = useAuth();

  if (!user.isOps) {
    return (
      <EmptyState icon={<ShieldAlert />} title="Operations is for the ops role">
        Sign in as <span className="font-mono">ops1</span> to handle dead letters and run reconciliation.
      </EmptyState>
    );
  }

  return (
    <>
      <PageHeader
        eyebrow="Operations"
        title="When something does not go through"
        description="Events that could not be processed wait here for a decision, and payments that went quiet can be checked against Stripe."
      />
      <Tabs defaultValue="dead-letters">
        <TabsList className="mb-5">
          <TabsTrigger value="dead-letters">Dead letters</TabsTrigger>
          <TabsTrigger value="reconciliation">Reconciliation</TabsTrigger>
        </TabsList>
        <TabsContent value="dead-letters">
          <DeadLetters />
        </TabsContent>
        <TabsContent value="reconciliation">
          <Reconciliation />
        </TabsContent>
      </Tabs>
    </>
  );
}
