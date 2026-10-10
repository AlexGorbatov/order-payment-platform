"use client";

import { useMemo } from "react";
import { useAuth } from "@/components/auth-provider";

export type ServiceName = "order" | "payment";

/** An error answer of one of the services (RFC 9457 problem details), or of the proxy. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly retryAfter?: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

async function toError(response: Response): Promise<ApiError> {
  let title = response.statusText || `Request failed (${response.status})`;
  let code: string | undefined;
  try {
    const problem = await response.json();
    title = problem.detail ?? problem.title ?? title;
    code = problem.code ?? problem.type?.replace(/^urn:[a-z-]+:(problem:)?/, "");
  } catch {
    // not JSON: keep the status text
  }
  const retry = Number(response.headers.get("retry-after"));
  return new ApiError(response.status, title, code, Number.isFinite(retry) && retry > 0 ? retry : undefined);
}

export function createApi(getToken: () => Promise<string>) {
  async function request<T>(service: ServiceName, method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${await getToken()}`,
      Accept: "application/json",
    };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
    const response = await fetch(`/proxy/${service}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      cache: "no-store",
    });
    if (!response.ok) throw await toError(response);
    return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  }

  return {
    get: <T>(service: ServiceName, path: string, signal?: AbortSignal) => request<T>(service, "GET", path, { signal }),
    post: <T>(service: ServiceName, path: string, options?: RequestOptions) => request<T>(service, "POST", path, options),
    /** The interface's own server: sends the signed webhook of local mode. */
    simulate: async (orderId: string, event: string) => {
      const response = await fetch("/api/simulate", {
        method: "POST",
        headers: { Authorization: `Bearer ${await getToken()}`, "Content-Type": "application/json" },
        body: JSON.stringify({ orderId, event }),
      });
      if (!response.ok) throw await toError(response);
    },
  };
}

export type Api = ReturnType<typeof createApi>;

export function useApi(): Api {
  const { getToken } = useAuth();
  return useMemo(() => createApi(getToken), [getToken]);
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

/** One sentence for a toast or an error state: what went wrong, in the interface's voice. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return "Your session expired. Sign in again.";
    if (error.status === 403) return "Your account may not do this.";
    if (error.status === 502 || error.status === 503) return "A service is not answering right now. Try again in a moment.";
    return error.message;
  }
  return error instanceof Error ? error.message : "Something went wrong.";
}
