import { describe, expect, it } from "vitest";
import { coverage, groupOptions, matches, pillWidth, prefixOf, setAll, type Option } from "../picker";

const names = (values: string[]): Option[] => values.map((value) => ({ value }));

describe("prefixOf", () => {
  it("takes everything but the last part of the name", () => {
    expect(prefixOf("prod-eu-west-1")).toBe("prod-eu-west");
    expect(prefixOf("staging.us")).toBe("staging");
    expect(prefixOf("k3d_loggate")).toBe("k3d");
    expect(prefixOf("eu.prod-2")).toBe("eu.prod");
    expect(prefixOf("single")).toBe("single");
    expect(prefixOf("-leading")).toBe("-leading");
  });
});

describe("groupOptions", () => {
  const thirty = names([
    ...Array.from({ length: 12 }, (_, i) => `prod-eu-${i}`),
    ...Array.from({ length: 8 }, (_, i) => `prod-us-${i}`),
    ...Array.from({ length: 5 }, (_, i) => `staging-${i}`),
    ...Array.from({ length: 3 }, (_, i) => `dev-${i}`),
    "k3d-loggate",
    "edge",
  ]);

  it("splits a long list of clusters into the families their names encode", () => {
    const groups = groupOptions(thirty, "prefix");
    // prod-eu and prod-us are fleets of their own, not one "prod".
    expect(groups.map((g) => g.name)).toEqual(["prod-eu", "prod-us", "staging", "dev", "Other"]);
    expect(groups[0]!.options).toHaveLength(12);
    expect(groups[4]!.options.map((o) => o.value)).toEqual(["k3d-loggate", "edge"]);
  });

  it("leaves a short list alone", () => {
    expect(groupOptions(names(["a-1", "a-2", "b-1", "b-2"]), "prefix")).toEqual([
      { name: null, options: names(["a-1", "a-2", "b-1", "b-2"]) },
    ]);
  });

  it("leaves a list alone when its names share no families worth showing", () => {
    const unrelated = names(Array.from({ length: 14 }, (_, i) => `c${i}`));
    expect(groupOptions(unrelated, "prefix")).toHaveLength(1);
    const oneFamily = names(Array.from({ length: 14 }, (_, i) => `prod-eu-${i}`));
    expect(groupOptions(oneFamily, "prefix")).toEqual([{ name: null, options: oneFamily }]);
  });

  it("drops the Other group when every name has a family", () => {
    const tidy = names([
      ...Array.from({ length: 7 }, (_, i) => `a-${i}`),
      ...Array.from({ length: 7 }, (_, i) => `b-${i}`),
    ]);
    expect(groupOptions(tidy, "prefix").map((g) => g.name)).toEqual(["a", "b"]);
  });

  it("groups by field in first-seen order", () => {
    const pods: Option[] = [
      { value: "api-1", group: "payments" },
      { value: "api-2", group: "platform" },
      { value: "worker", group: "payments" },
      { value: "loose" },
    ];
    expect(groupOptions(pods, "field")).toEqual([
      { name: "payments", options: [pods[0], pods[2]] },
      { name: "platform", options: [pods[1]] },
      { name: "", options: [pods[3]] },
    ]);
  });

  it("gives a single field group no heading", () => {
    const pods: Option[] = [
      { value: "a", group: "ns" },
      { value: "b", group: "ns" },
    ];
    expect(groupOptions(pods, "field")).toEqual([{ name: null, options: pods }]);
  });

  it("does not group when asked not to", () => {
    expect(groupOptions(thirty, "none")).toHaveLength(1);
  });
});

describe("pillWidth", () => {
  it("fits the longest label or detail, within bounds", () => {
    expect(pillWidth(names(["a", "b"]))).toBe(13);
    expect(pillWidth([{ value: "checkout-canary" }, { value: "x", detail: "a much longer team name" }])).toBe(26);
    expect(pillWidth(names(["x".repeat(200)]))).toBe(63);
    expect(pillWidth([])).toBe(13);
  });
});

describe("matches", () => {
  const option: Option = { value: "checkout-prod", detail: "payments", group: "edge-eu" };

  it("matches anything shown, ignoring case and surrounding space", () => {
    expect(matches(option, "")).toBe(true);
    expect(matches(option, "  CHECK ")).toBe(true);
    expect(matches(option, "pay")).toBe(true);
    expect(matches(option, "edge")).toBe(true);
    expect(matches(option, "platform")).toBe(false);
    expect(matches({ value: "x" }, "y")).toBe(false);
  });
});

describe("setAll and coverage", () => {
  it("adds every value once, keeping what was already chosen first", () => {
    expect(setAll(["b"], ["a", "b", "c"], true)).toEqual(["b", "a", "c"]);
  });

  it("removes only the values given", () => {
    expect(setAll(["a", "b", "z"], ["a", "b"], false)).toEqual(["z"]);
  });

  it("says how much of a group is chosen", () => {
    expect(coverage([], ["a", "b"])).toBe("none");
    expect(coverage(["a"], ["a", "b"])).toBe("some");
    expect(coverage(["b", "a", "x"], ["a", "b"])).toBe("all");
  });
});
