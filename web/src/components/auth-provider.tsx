"use client";

import Keycloak from "keycloak-js";
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import type { PublicConfig } from "@/lib/types";

export interface User {
  subject: string;
  username: string;
  name: string;
  roles: string[];
  isCustomer: boolean;
  isAdmin: boolean;
  isOps: boolean;
}

interface AuthContextValue {
  user: User;
  config: PublicConfig;
  getToken: () => Promise<string>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

interface Session {
  keycloak: Keycloak;
  config: PublicConfig;
}

// keycloak-js may be initialised once per page; React Strict Mode renders effects twice in development.
let session: Promise<Session> | null = null;

function openSession(): Promise<Session> {
  session ??= (async () => {
    const response = await fetch("/api/config", { cache: "no-store" });
    if (!response.ok) throw new Error("The web app could not read its configuration.");
    const config = (await response.json()) as PublicConfig;
    const keycloak = new Keycloak({ url: config.keycloakUrl, realm: config.realm, clientId: config.clientId });
    // Authorization Code with PKCE (S256): the flow the public client opp-web is configured for.
    await keycloak.init({ onLoad: "login-required", pkceMethod: "S256", checkLoginIframe: false });
    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.login());
    };
    return { keycloak, config };
  })().catch((error) => {
    session = null;
    throw error;
  });
  return session;
}

function toUser(keycloak: Keycloak): User {
  const claims = keycloak.tokenParsed as { sub?: string; preferred_username?: string; name?: string; realm_access?: { roles?: string[] } } | undefined;
  const roles = claims?.realm_access?.roles ?? [];
  const username = claims?.preferred_username ?? "unknown";
  return {
    subject: claims?.sub ?? "",
    username,
    name: claims?.name ?? username,
    roles,
    isCustomer: roles.includes("customer"),
    isAdmin: roles.includes("admin"),
    isOps: roles.includes("ops"),
  };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<{ status: "loading" } | { status: "error"; message: string } | { status: "ready"; session: Session }>({
    status: "loading",
  });

  const settle = useCallback(() => {
    openSession().then(
      (opened) => setState({ status: "ready", session: opened }),
      (error: unknown) => setState({ status: "error", message: error instanceof Error ? error.message : "Sign-in failed." }),
    );
  }, []);
  const retry = useCallback(() => {
    setState({ status: "loading" });
    settle();
  }, [settle]);

  useEffect(() => {
    settle();
  }, [settle]);

  const value = useMemo<AuthContextValue | null>(() => {
    if (state.status !== "ready") return null;
    const { keycloak, config } = state.session;
    return {
      user: toUser(keycloak),
      config,
      getToken: async () => {
        await keycloak.updateToken(30);
        return keycloak.token as string;
      },
      logout: () => void keycloak.logout({ redirectUri: window.location.origin }),
    };
  }, [state]);

  if (state.status === "error") {
    return (
      <div className="grid min-h-dvh place-items-center p-6">
        <div className="max-w-sm text-center">
          <h1 className="font-display text-xl font-semibold">Can&apos;t reach the sign-in service</h1>
          <p className="mt-2 text-sm text-muted">{state.message} Check that Keycloak is running, then try again.</p>
          <Button className="mt-5" onClick={retry}>
            Try again
          </Button>
        </div>
      </div>
    );
  }
  if (!value) {
    return (
      <div className="grid min-h-dvh place-items-center" role="status" aria-label="Signing in">
        <div className="size-6 animate-spin rounded-full border-2 border-line border-t-accent" />
      </div>
    );
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
