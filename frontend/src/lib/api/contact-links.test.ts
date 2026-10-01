import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

/**
 * F2 — link and route regression guard for Contacts and Contact Groups.
 *
 * ## Why this test exists
 *
 * F0 found that every per-contact "View" link resolved to
 * `/contact-groups/undefined/contacts/…`, because the link was built from
 * `contact.contactGroupId`, a field the backend `ContactResponse` does not have.
 * F0 also found a group-detail "Edit" link to `/contact-groups/{id}/edit`, a route
 * that has never existed. Both are invisible to typecheck — `href` accepts any
 * string — and invisible to a service-layer unit test, because the URL is
 * assembled inside a component.
 *
 * A DOM-free source scan is therefore the cheapest honest way to hold this line
 * without adding jsdom. It:
 *
 *  1. collects every route pattern that exists on disk (a `page.tsx` under
 *     `src/app`), keeping dynamic segment names;
 *  2. collects every internal `href` and `router.push` / `router.replace` target
 *     in `src/app` and `src/components`, and
 *  3. asserts each one matches a real route.
 *
 * ### What it does NOT do
 *
 * It does not evaluate expressions. A link built from `id` is checked for the
 * SHAPE `/segment/<dynamic>/segment`, never for the runtime value of `id` —
 * whether an id is nullish is React's problem, and TypeScript already prevents
 * `string | undefined` from being interpolated into a template literal. A whole
 * path interpolated as a single expression (`` href={`${base}/x`} ``) is skipped
 * rather than guessed at.
 *
 * Adding a link without adding its route fails here. That is the point.
 */
const SRC = fileURLToPath(new URL("../../", import.meta.url));
const APP_DIR = join(SRC, "app");
const COMPONENTS_DIR = join(SRC, "components");

/** Route groups are organisational only and never appear in a URL. */
const ROUTE_GROUPS = new Set(["(platform)", "(auth)"]);

function sourceFiles(dir: string, extension: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) found.push(...sourceFiles(full, extension));
    else if (entry.endsWith(extension)) found.push(full);
  }
  return found;
}

/** `/contact-groups/[contactGroupId]/contacts` for every directory holding a page. */
function collectRoutes(): Set<string> {
  const routes = new Set<string>();
  const walk = (dir: string, segments: string[]) => {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (!statSync(full).isDirectory()) continue;
      const isGroup = ROUTE_GROUPS.has(entry) || entry.startsWith("@");
      const next = isGroup ? segments : [...segments, entry];
      if (readdirSync(full).some((child) => child === "page.tsx")) {
        routes.add(`/${next.join("/")}`);
      }
      walk(full, next);
    }
  };
  walk(APP_DIR, []);
  return routes;
}

/**
 * Does `pattern` match a real route?
 *
 * Segment-by-segment, with `<dynamic>` as a wildcard for exactly one segment.
 * Matching literal segments EXACTLY is the point: a link to
 * `/contact-groups/<dynamic>/edit` must not be accepted just because
 * `/contact-groups/<contactGroupId>/contacts/<contactId>` has the same number of
 * segments — that is precisely the F0 dead link this test exists to catch.
 */
function matchesRoute(pattern: string): boolean {
  const segments = pattern.split("/").slice(1);
  return [...ROUTES].some((route) => {
    const routeSegments = route.split("/").slice(1);
    if (routeSegments.length !== segments.length) return false;
    return segments.every(
      (segment, index) =>
        segment === "<dynamic>" || segment === routeSegments[index],
    );
  });
}

const ROUTES = collectRoutes();

/** Normalises one href into a route pattern; literal text stays, `${…}` becomes dynamic. */
function hrefToPattern(href: string): string | null {
  if (!href.startsWith("/")) return null; // external URL or fragment
  const [path] = href.split(/[?#]/);
  // A whole path built from one expression cannot be analysed statically.
  if (!path || path.startsWith("${")) return null;
  const pattern = path
    .split("/")
    .filter(Boolean)
    .map((segment) => (segment.includes("${") ? "<dynamic>" : segment));
  return `/${pattern.join("/")}`;
}

interface Href {
  /** Repo-relative, POSIX-style, for readable failure output. */
  file: string;
  pattern: string;
  source: string;
}

function collectHrefs(): Href[] {
  const files = [
    ...sourceFiles(APP_DIR, ".tsx"),
    ...sourceFiles(COMPONENTS_DIR, ".tsx"),
  ];
  const patterns = [
    /href="(\/[^"]*)"/g,
    /href=\{`(\/[^`]*)`\}/g,
    /router\.(?:push|replace)\(\s*"(\/[^"]*)"/g,
    /router\.(?:push|replace)\(\s*`(\/[^`]*)`/g,
  ];
  const hrefs: Href[] = [];
  for (const file of files) {
    const text = readFileSync(file, "utf8");
    for (const pattern of patterns) {
      for (const match of text.matchAll(pattern)) {
        const href = match[1];
        if (!href) continue;
        const normalised = hrefToPattern(href);
        if (normalised) {
          hrefs.push({
            file: relative(SRC, file).split(sep).join("/"),
            pattern: normalised,
            source: href,
          });
        }
      }
    }
  }
  return hrefs;
}

const ALL_HREFS = collectHrefs();
/** Only the surfaces this phase owns. */
const CONTACT_HREFS = ALL_HREFS.filter(
  (href) =>
    href.pattern.startsWith("/contact-groups") ||
    href.pattern.startsWith("/contacts"),
);
/**
 * F3 extends the same scan to Audio Assets and TTS Templates. Reusing the
 * collector rather than writing a second one is the point: a route guard that
 * only knows about contacts is a route guard that will quietly stop covering
 * new domains.
 */
const CONTENT_HREFS = ALL_HREFS.filter(
  (href) =>
    href.pattern.startsWith("/audio-assets") ||
    href.pattern.startsWith("/tts-templates"),
);
/**
 * F4 extends the same scan to Campaigns. Three campaign links were dead when the
 * phase started and none of them were caught, because the guard only covered
 * contacts and content:
 *
 *  1. `campaign-detail-view.tsx` linked to `/campaigns/{id}/edit`, a route that
 *     has never existed — editing is a dialog, exactly as it is for
 *     contact groups (see the F2 assertion below).
 *  2. `execution-detail-view.tsx` linked to
 *     `/campaigns/{cid}/executions/{eid}/attempts`, and `call-attempt-table.tsx`
 *     linked to `/campaigns/{cid}/executions/{eid}/attempts/{aid}`. Neither
 *     route existed, so both resolved to the 404 page — while 315 lines of
 *     fully-written attempts table and a 162-line API module sat unreachable.
 *
 * F4 added the `/attempts` route and removed the `{attemptId}` link, because no
 * endpoint needs a separate attempt page: `CallAttemptResponse` is fully
 * rendered in the list. Both choices are asserted below so they cannot silently
 * regress.
 */
const CAMPAIGN_HREFS = ALL_HREFS.filter((href) => href.pattern.startsWith("/campaigns"));

/** Strips line and block comments so a doc comment cannot fail a source assertion. */
function withoutComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

describe("route inventory", () => {
  it("finds the contacts and contact-group routes on disk", () => {
    expect(ROUTES.has("/contact-groups")).toBe(true);
    expect(ROUTES.has("/contact-groups/[contactGroupId]")).toBe(true);
    expect(ROUTES.has("/contact-groups/[contactGroupId]/contacts")).toBe(true);
    // F2 added this route: the F0 "View" button on a contact pointed here and
    // every click resolved to the not-found page.
    expect(ROUTES.has("/contact-groups/[contactGroupId]/contacts/[contactId]")).toBe(
      true,
    );
  });

  it("has NO /contact-groups/{id}/edit route", () => {
    // The F0 group-detail page linked to it; editing is a dialog. If a real edit
    // page is ever added, replace this assertion with a route check — do not
    // delete it.
    expect(ROUTES.has("/contact-groups/[contactGroupId]/edit")).toBe(false);
  });

  it("has NO top-level /contacts route", () => {
    // VERIFIED: no ContactController exists and `ContactResponse` carries no
    // group id, so `/contacts/{id}` could never be resolved by the backend.
    expect([...ROUTES].some((route) => route.startsWith("/contacts"))).toBe(false);
  });

  it("actually scanned the contacts surfaces", () => {
    // Guards against a glob that silently matches nothing, which would make
    // every assertion below vacuously true.
    expect(CONTACT_HREFS.length).toBeGreaterThan(0);
    expect(CONTACT_HREFS.some((href) => href.file.includes("contact"))).toBe(true);
  });
});

describe("every contact link resolves to a real route", () => {
  it.each(CONTACT_HREFS.map((href) => [href.source, href.pattern] as const))(
    "%s",
    (_source, pattern) => {
      expect(
        matchesRoute(pattern),
        `no page.tsx route matches ${pattern}`,
      ).toBe(true);
    },
  );
});

describe("no phantom identifiers reach a URL", () => {
  it("no file reads `contact.contactGroupId`, which the backend never sends", () => {
    for (const { file } of CONTACT_HREFS) {
      const text = withoutComments(readFileSync(join(SRC, file), "utf8"));
      expect(text, `${file} reads contact.contactGroupId`).not.toMatch(
        /\.contactGroupId\b/,
      );
    }
  });

  it("no href interpolates a literal `undefined` or `null`", () => {
    for (const { file } of CONTACT_HREFS) {
      const text = readFileSync(join(SRC, file), "utf8");
      expect(text, `${file} interpolates undefined/null into a href`).not.toMatch(
        /href=\{?\{?`[^`]*\$\{\s*(undefined|null)\b/,
      );
    }
  });
});

/* ------------------------------- F3: audio / TTS ------------------------- */

describe("Audio Assets and TTS Templates routes", () => {
  it("finds the four routes that back these two domains", () => {
    expect(ROUTES.has("/audio-assets")).toBe(true);
    expect(ROUTES.has("/audio-assets/[audioAssetId]")).toBe(true);
    expect(ROUTES.has("/tts-templates")).toBe(true);
    expect(ROUTES.has("/tts-templates/[ttsTemplateId]")).toBe(true);
  });

  it("has NO route for a download, a preview, or an upload form", () => {
    // There is no binary endpoint in either domain, so a route that offered one
    // would be a page whose central control can never work.
    for (const route of ROUTES) {
      expect(route).not.toMatch(/\/(download|preview|play|export|generate|render)/);
    }
  });

  it("has NO top-level /tts route — templates live under /tts-templates", () => {
    expect([...ROUTES].some((route) => route === "/tts")).toBe(false);
  });

  it("actually scanned the audio and TTS surfaces", () => {
    // Guards against a filter that matches nothing, which would make every
    // assertion below vacuously true.
    expect(CONTENT_HREFS.length).toBeGreaterThan(0);
  });

  it.each(CONTENT_HREFS.map((href) => [href.source, href.pattern] as const))(
    "%s",
    (_source, pattern) => {
      expect(matchesRoute(pattern), `no page.tsx route matches ${pattern}`).toBe(
        true,
      );
    },
  );

  it("builds every asset and template link from the API record's own id", () => {
    // Not `undefined`: the ids come from `AudioAssetResponse.id` and
    // `TtsTemplateResponse.id`, both of which the backend always sends.
    for (const { file } of CONTENT_HREFS) {
      const text = readFileSync(join(SRC, file), "utf8");
      expect(text, `${file} interpolates undefined/null into a href`).not.toMatch(
        /href=\{?\{?`[^`]*\$\{\s*(undefined|null)\b/,
      );
    }
  });
});

/* ------------------------------- F4: campaigns --------------------------- */

describe("Campaign routes", () => {
  it("finds the four routes that back the campaign surface", () => {
    expect(ROUTES.has("/campaigns")).toBe(true);
    expect(ROUTES.has("/campaigns/[campaignId]")).toBe(true);
    expect(ROUTES.has("/campaigns/[campaignId]/executions/[executionId]")).toBe(
      true,
    );
    // F4 ADDED this route. Before F4 it did not exist, so the execution page's
    // "Call Attempts" button resolved to the 404 page and the already-written
    // attempts table was rendered by nothing.
    expect(
      ROUTES.has(
        "/campaigns/[campaignId]/executions/[executionId]/attempts",
      ),
    ).toBe(true);
  });

  it("has NO /campaigns/{id}/edit route — editing is a dialog", () => {
    // The F1 detail page linked here. VERIFIED there is no `PUT /campaigns/{id}`
    // *page* concept: the only server operation is the dialog's
    // `PUT /api/v1/campaigns/{id}`, and a route would need its own loader. F4
    // replaced the link with the dialog. If a real edit page is ever added,
    // replace this assertion with a route check — do not delete it.
    expect(ROUTES.has("/campaigns/[campaignId]/edit")).toBe(false);
  });

  it("has NO per-attempt page — the attempt list renders every field", () => {
    // VERIFIED `GET .../attempts/{attemptId}` returns the same
    // `CallAttemptResponse` the list returns, so a detail page would show
    // nothing the table does not. F4 removed the link rather than inventing a
    // route for an operation that adds no information.
    expect(
      [...ROUTES].some((route) =>
        route.endsWith("/attempts/[attemptId]"),
      ),
    ).toBe(false);
  });

  it("has NO route for a scheduler, a dashboard, or campaign analytics", () => {
    // VERIFIED: the platform DOES have an execution engine now —
    // `CampaignExecutionOrchestrator` is a `@Service` with
    // `@Scheduled(fixedDelay = 30000)`, alongside ten other `@Scheduled`
    // pollers. But none of them has any REST surface: an exhaustive scan finds
    // exactly 15 controllers carrying a `@RequestMapping`, and none of them is
    // an orchestrator, a scheduler or a metrics endpoint.
    //
    // So the correct reason for no route here is "the engine exists and is
    // deliberately not exposed", not "there is no engine". A route would need an
    // endpoint to read, and building one would mean inventing it.
    for (const route of ROUTES) {
      expect(route).not.toMatch(
        /\/(scheduler|dashboard|analytics|metrics|statistics|reports?|preview|play|download)/,
      );
    }
  });

  it("actually scanned the campaign surfaces", () => {
    // Guards against a filter that matches nothing, which would make every
    // assertion below vacuously true.
    expect(CAMPAIGN_HREFS.length).toBeGreaterThan(0);
  });

  it.each(CAMPAIGN_HREFS.map((href) => [href.source, href.pattern] as const))(
    "%s",
    (_source, pattern) => {
      expect(matchesRoute(pattern), `no page.tsx route matches ${pattern}`).toBe(
        true,
      );
    },
  );
});

describe("no phantom identifiers reach a campaign URL", () => {
  it("no campaign link interpolates a literal `undefined` or `null`", () => {
    for (const { file } of CAMPAIGN_HREFS) {
      const text = readFileSync(join(SRC, file), "utf8");
      expect(text, `${file} interpolates undefined/null into a href`).not.toMatch(
        /href=\{?\{?`[^`]*\$\{\s*(undefined|null)\b/,
      );
    }
  });

  it("no campaign link is built from a field the CampaignResponse lacks", () => {
    // The F0 contacts defect was exactly this: a link built from
    // `contact.contactGroupId`, a field the backend never sends, so every
    // click resolved to `/contact-groups/undefined/contacts/…`.
    // `CampaignResponse` DOES carry a `contactGroupId` — but it is a nullable
    // REFERENCE the campaign stores, not a route parameter, and no campaign
    // route is scoped by it. Asserting the absence of that shape keeps the
    // failure mode from being reintroduced by analogy.
    for (const { file } of CAMPAIGN_HREFS) {
      const text = withoutComments(readFileSync(join(SRC, file), "utf8"));
      expect(text, `${file} builds a campaign URL from contactGroupId`).not.toMatch(
        /href=\{?\{?`[^`]*\$\{\s*[A-Za-z0-9_.\[\]]*contactGroupId\b/,
      );
    }
  });
});

describe("the Campaign detail surface has no orphaned components", () => {
  it("every component under components/campaigns is referenced somewhere", () => {
    // VERIFIED drift F4 found: `call-attempt-table.tsx` was 315 lines of
    // complete, contract-checked UI rendered by NOTHING, whose only outbound
    // link pointed at a route that did not exist. A component no page and no
    // sibling component imports is invisible to every other test in this file.
    const dir = join(COMPONENTS_DIR, "campaigns");
    const files = readdirSync(dir).filter((name) => name.endsWith(".tsx"));
    expect(files.length).toBeGreaterThan(0);

    // A module counts as referenced when some file IMPORTS it. Matching on an
    // import specifier rather than on a derived symbol name matters: several
    // campaign modules export a family of components
    // (`campaign-type-config-fields` exports three, `campaign-config-cards`
    // exports four), so no single symbol corresponds to the file name and a
    // name-based check would report every one of them as an orphan.
    const consumers = [
      ...files.map((name) => readFileSync(join(dir, name), "utf8")),
      ...sourceFiles(APP_DIR, ".tsx").map((file) => readFileSync(file, "utf8")),
    ].join("\n");

    const orphan = files.filter((name) => {
      const base = name.replace(/\.tsx$/, "");
      const aliased = new RegExp(
        `from\\s+["'][^"']*/${base.replace(/[-/\\^$*+?.()|[\]{}]/g, "\\$&")}["']`,
      );
      return !aliased.test(consumers);
    });
    expect(orphan, `orphan campaign components: ${orphan.join(", ")}`).toEqual([]);
  });
});
