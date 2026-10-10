import { describe, expect, it } from "vitest";
import { formatMoney, formatOffset, formatRelative, shortId } from "./format";

describe("formatMoney", () => {
  it("divides minor units once", () => {
    expect(formatMoney({ amountMinor: 3499, currency: "EUR" })).toBe("€34.99");
    expect(formatMoney({ amountMinor: 5, currency: "EUR" })).toBe("€0.05");
  });
});

describe("formatOffset", () => {
  it("shows sub-minute offsets in seconds and longer ones in minutes", () => {
    expect(formatOffset("2026-10-10T10:00:00Z", "2026-10-10T10:00:03.2Z")).toBe("+3.2 s");
    expect(formatOffset("2026-10-10T10:00:00Z", "2026-10-10T10:01:05Z")).toBe("+1 min 5 s");
    expect(formatOffset("2026-10-10T10:00:00Z", "2026-10-10T10:00:00Z")).toBe("+0 s");
  });
});

describe("formatRelative", () => {
  const now = Date.parse("2026-10-10T10:00:00Z");
  it("is relative for the last day", () => {
    expect(formatRelative("2026-10-10T09:59:58Z", now)).toBe("just now");
    expect(formatRelative("2026-10-10T09:59:30Z", now)).toBe("30 s ago");
    expect(formatRelative("2026-10-10T09:50:00Z", now)).toBe("10 min ago");
    expect(formatRelative("2026-10-10T07:00:00Z", now)).toBe("3 h ago");
  });
});

describe("shortId", () => {
  it("keeps both ends of a UUID", () => {
    expect(shortId("01a1223d-6b29-7cd4-866d-d61ae53f87d4")).toBe("01a1223d…87d4");
    expect(shortId("short")).toBe("short");
  });
});
