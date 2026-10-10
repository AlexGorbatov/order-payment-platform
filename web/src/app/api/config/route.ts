import type { PublicConfig } from "@/lib/types";

export const dynamic = "force-dynamic";

/** What the browser needs to know about this deployment. Public values only: nothing secret is read here. */
export function GET() {
  const config: PublicConfig = {
    keycloakUrl: process.env.KEYCLOAK_PUBLIC_URL ?? "http://localhost:8180",
    realm: process.env.KEYCLOAK_REALM ?? "opp",
    clientId: process.env.KEYCLOAK_CLIENT_ID ?? "opp-web",
    stripePublishableKey: process.env.STRIPE_PUBLISHABLE_KEY ?? "",
    paymentElement: process.env.WEB_PAYMENT_ELEMENT === "true",
    simulateWebhooks: process.env.WEB_SIMULATE_WEBHOOKS === "true",
  };
  return Response.json(config, { headers: { "Cache-Control": "no-store" } });
}
