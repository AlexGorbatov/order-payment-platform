import { isService, serviceUrl } from "@/lib/server/targets";

/**
 * The browser talks to one origin; this forwards its calls to the two services, so they need no CORS configuration.
 * The user's own bearer token is passed through untouched: the services decide who may do what, as they always do.
 * Only the business API (`/api/...`) and the operator API (`/admin/...`) are reachable; actuator and the webhook
 * endpoint are not.
 */
const FORWARDED_REQUEST_HEADERS = ["authorization", "content-type", "accept", "idempotency-key", "x-correlation-id"];
const FORWARDED_RESPONSE_HEADERS = ["content-type", "cache-control", "idempotent-replayed", "retry-after", "x-correlation-id"];
const ALLOWED_ROOTS = new Set(["api", "admin"]);

async function forward(request: Request, ctx: RouteContext<"/proxy/[service]/[...path]">) {
  const { service, path } = await ctx.params;
  if (!isService(service) || !ALLOWED_ROOTS.has(path[0])) {
    return Response.json({ title: "Not found", status: 404, code: "not-found" }, { status: 404 });
  }

  const incoming = new URL(request.url);
  const target = `${serviceUrl(service)}/${path.map(encodeURIComponent).join("/")}${incoming.search}`;

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const hasBody = request.method !== "GET" && request.method !== "HEAD";

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "manual",
      cache: "no-store",
    });
  } catch {
    return Response.json(
      { title: "Service unavailable", status: 502, code: "upstream-unreachable", detail: `The ${service} service did not answer.` },
      { status: 502 },
    );
  }

  const responseHeaders = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
}

export { forward as GET, forward as POST, forward as PUT, forward as PATCH, forward as DELETE };
