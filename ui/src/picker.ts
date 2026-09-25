/**
 * Choosing many things from a long list, without the list taking the page.
 *
 * <p>Pure functions, so how options are grouped and filtered can be tested
 * without a browser.
 */

export type Option = {
  value: string;
  /** Shown beneath the value, e.g. the team that owns a namespace. */
  detail?: string | null;
  /** The group to show it in, when grouping by field. */
  group?: string;
};

export type Group = { name: string | null; options: Option[] };

/** Below this many options, grouping only adds headings to read past. */
export const GROUP_FROM = 12;

/**
 * The family a name belongs to, by the conventions clusters are usually named
 * with: everything but its last part. {@code prod-eu-west-1} and
 * {@code prod-eu-west-2} are both {@code prod-eu-west}, apart from
 * {@code prod-us-east-1}; {@code staging-eu} is {@code staging}. A name with no
 * separator is a family of its own.
 */
export function prefixOf(name: string): string {
  const cut = Math.max(name.lastIndexOf("-"), name.lastIndexOf("."), name.lastIndexOf("_"));
  return cut > 0 ? name.slice(0, cut) : name;
}

/**
 * Options in groups.
 *
 * <p>By prefix, a long list of clusters falls into the families its names
 * already encode, each with a toggle for the whole family; a family of one is
 * not a family, so those share an "Other" group at the end. When prefixes do
 * not divide the list usefully it stays one group. By field, each option goes
 * where its {@code group} says, in first-seen order.
 */
export function groupOptions(options: Option[], by: "none" | "prefix" | "field"): Group[] {
  if (by === "field") {
    const groups = new Map<string, Option[]>();
    for (const option of options) {
      const name = option.group ?? "";
      groups.set(name, [...(groups.get(name) ?? []), option]);
    }
    // One group needs no heading: it would only repeat what was chosen above.
    if (groups.size === 1) return [{ name: null, options }];
    return [...groups].map(([name, members]) => ({ name, options: members }));
  }
  if (by === "none" || options.length < GROUP_FROM) {
    return [{ name: null, options }];
  }
  const families = new Map<string, Option[]>();
  for (const option of options) {
    const family = prefixOf(option.value);
    families.set(family, [...(families.get(family) ?? []), option]);
  }
  const real = [...families].filter(([, members]) => members.length > 1);
  if (real.length < 2) {
    return [{ name: null, options }];
  }
  const loose = [...families].filter(([, members]) => members.length === 1).flatMap(([, m]) => m);
  return [
    ...real.map(([name, members]) => ({ name, options: members })),
    ...(loose.length ? [{ name: "Other", options: loose }] : []),
  ];
}

/**
 * How wide a pill must be to show the longest label whole, in characters,
 * counting the checkbox. Pills share one width so the grid stays a grid.
 */
export function pillWidth(options: Option[]): number {
  const longest = Math.max(0, ...options.map((o) => Math.max(o.value.length, (o.detail ?? "").length)));
  return Math.min(Math.max(longest, 10), 60) + 3;
}

/** Whether an option matches a filter, case-insensitively, on anything shown. */
export function matches(option: Option, filter: string): boolean {
  const needle = filter.trim().toLowerCase();
  if (!needle) return true;
  return [option.value, option.detail ?? "", option.group ?? ""].some((text) =>
    text.toLowerCase().includes(needle),
  );
}

/** {@code chosen} with every one of {@code values} added, or removed. */
export function setAll(chosen: string[], values: string[], on: boolean): string[] {
  if (on) {
    const added = values.filter((value) => !chosen.includes(value));
    return [...chosen, ...added];
  }
  return chosen.filter((value) => !values.includes(value));
}

/** How much of a group is chosen: drives its toggle's checked and mixed states. */
export function coverage(chosen: string[], values: string[]): "all" | "some" | "none" {
  const picked = values.filter((value) => chosen.includes(value)).length;
  if (picked === 0) return "none";
  return picked === values.length ? "all" : "some";
}
