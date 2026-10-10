// Where the two services are, read at run time (the container gets them from compose; on a laptop the defaults hold).

export type Service = "order" | "payment";

export function serviceUrl(service: Service): string {
  const url =
    service === "order"
      ? (process.env.ORDER_SERVICE_URL ?? "http://localhost:8081")
      : (process.env.PAYMENT_SERVICE_URL ?? "http://localhost:8082");
  return url.replace(/\/+$/, "");
}

export function isService(value: string): value is Service {
  return value === "order" || value === "payment";
}
