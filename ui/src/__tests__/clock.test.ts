import { afterEach, describe, expect, it } from "vitest";
import { observeServerDate, resetServerClock, serverNow } from "../clock";

afterEach(resetServerClock);

describe("serverNow", () => {
  it("is the local clock until the server has said otherwise", () => {
    expect(serverNow(1_000_000)).toBe(1_000_000);
  });

  it("follows the server when the browser's clock is out", () => {
    // The server says 12:00:00 at the moment the browser thinks it is 15:06.
    const local = Date.parse("2026-09-21T15:06:00Z");
    observeServerDate("Mon, 21 Sep 2026 12:00:00 GMT", local);

    expect(new Date(serverNow(local)).toISOString()).toBe("2026-09-21T12:00:00.000Z");
    // And keeps the same offset as local time moves on.
    expect(new Date(serverNow(local + 60_000)).toISOString()).toBe("2026-09-21T12:01:00.000Z");
  });

  it("ignores a missing or unreadable header rather than jumping", () => {
    observeServerDate("Mon, 21 Sep 2026 12:00:00 GMT", Date.parse("2026-09-21T12:00:10Z"));
    observeServerDate(null);
    observeServerDate("not a date");
    expect(serverNow(Date.parse("2026-09-21T12:00:10Z"))).toBe(Date.parse("2026-09-21T12:00:00Z"));
  });
});
