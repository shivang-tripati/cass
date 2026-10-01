import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

import {
  CAMPAIGN_RESERVED_STATUSES,
  CAMPAIGN_STATUS_DESCRIPTION,
} from "@/lib/domain/campaign-lifecycle";
import type { CampaignStatus } from "@/lib/api/contracts";

/**
 * F5 — guards the Campaign schedule and lifecycle semantics against drift back
 * to a model the backend does not have.
 *
 * ## Why a source scan
 *
 * F1 deferred jsdom + Testing Library, so the established substitute is a
 * DOM-free scan of the source text. That is genuinely the right tool here: every
 * claim below is about code or copy that does not exist, and a nonexistent
 * semantic cannot be detected by rendering a component that does not contain it.
 *
 * `contact-links.test.ts` already established both the technique and the
 * discipline this file follows — one collector, comments stripped so a doc
 * comment cannot fail a source assertion, and a positive "the scan actually
 * scanned something" check so a broken glob cannot make every assertion vacuous.
 *
 * ## What is protected, and why each is a real hazard
 *
 *  1. **No campaign end date.** VERIFIED `ScheduleConfig`'s own Javadoc:
 *     "There is deliberately no end date: a campaign has a start and then
 *     remains eligible across future calling windows until its work is
 *     exhausted." A past `startDate` is not an expiry. Anything that reintroduces
 *     `endDate` / `expiresAt` / an `EXPIRED` concept would be inventing a
 *     product rule the backend does not have.
 *
 *  2. **No system-driven campaign transitions.** The name `SYSTEM_DRIVEN_TRANSITIONS`
 *     was transcribed in F4, described campaign RUNNING/COMPLETED/FAILED as
 *     "aggregate rollups driven by the future execution engine", and was wrong:
 *     the current `CampaignStatus` Javadoc states they are "reserved
 *     execution-facts, unreachable and not operator-settable". The surviving
 *     mentions are historical explanations of the rename, inside comments, which
 *     is why comments are stripped before the assertion.
 *
 *  3. **No campaign status derived from an execution status.** The backend makes
 *     this explicit: campaign status "is not derived from executions: several
 *     executions may run for one campaign, so no single execution could
 *     authoritatively set it." This is the one mistake that would be invisible to
 *     every other test in the suite, because assigning one enum's member to
 *     another enum's field is perfectly type-correct.
 *
 *  4. **No `ControlIntent`.** There is no such backend contract. PAUSED is a
 *     dispatch gate, not a request to cancel an execution.
 *
 * ## Scoped deliberately
 *
 * Only campaign-scoped paths are scanned. The word "expired" legitimately
 * appears elsewhere — `expiresInSeconds` on the auth response, and 401 handling
 * in `error.ts` — and a repo-wide scan for it would be both noisy and wrong.
 * Scoping to the campaign files is what makes the assertion meaningful instead of
 * permanently disabled by unrelated matches.
 */

const SRC = fileURLToPath(new URL("../../", import.meta.url));

function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === "__tests__" || entry === "node_modules") continue;
      walk(full, acc);
    } else if (/\.(ts|tsx)$/.test(entry)) {
      acc.push(full);
    }
  }
  return acc;
}

/** The campaign-scoped PRODUCTION files. Deliberately excludes auth, contacts
 * and content.
 *
 * Test files are excluded on purpose. This guard has to name the tokens it
 * forbids — `"endDate"`, `"SYSTEM_DRIVEN_TRANSITIONS"` and the rest appear in
 * `FORBIDDEN` arrays — so a scan that included `*.test.ts` would always fail on
 * its own source and would have to be narrowed until it could not fail at all.
 * What needs protecting is production code; a test may legitimately mention a
 * forbidden token in order to assert its absence.
 */
const CAMPAIGN_FILES: string[] = [
  ...walk(join(SRC, "lib", "domain")).filter((f) => /campaign/.test(f)),
  ...walk(join(SRC, "lib", "api")).filter((f) => /campaign/.test(f)),
  ...walk(join(SRC, "lib", "schemas")).filter((f) => /campaign/.test(f)),
  ...walk(join(SRC, "lib", "auth")).filter((f) => /campaign/.test(f)),
  ...walk(join(SRC, "components", "campaigns")),
]
  .filter((file) => !/\.test\.tsx?$/.test(file))
  .map((file) => relative(SRC, file).split(sep).join("/"));

/** Strips line and block comments, plus string literals are left intact. */
function withoutComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

/** The real file contents, comments removed. Keyed by repo-relative path. */
const CODE: Record<string, string> = Object.fromEntries(
  CAMPAIGN_FILES.map((file) => [file, withoutComments(readFileSync(join(SRC, file), "utf8"))]),
);

describe("the guard actually scanned the campaign surfaces", () => {
  it("found the campaign files, so no assertion below is vacuous", () => {
    // Without this, a bad path or a rename would make every assertion pass by
    // inspecting nothing — which is how a guard rots silently.
    expect(CAMPAIGN_FILES.length).toBeGreaterThan(5);
    expect(CAMPAIGN_FILES.some((f) => f.includes("campaign-lifecycle"))).toBe(true);
    expect(CAMPAIGN_FILES.some((f) => f.includes("components/campaigns"))).toBe(true);
  });

  it("excluded test files, so the guard is not asserting against itself", () => {
    // This file names every forbidden token in order to forbid it. If it were
    // scanned, all the absence assertions would be permanently unsatisfiable.
    expect(CAMPAIGN_FILES.some((f) => f.endsWith(".test.ts"))).toBe(false);
  });
});

describe("no campaign end date or expiry concept (VERIFIED ScheduleConfig)", () => {
  // "There is deliberately no end date: a campaign has a start and then remains
  // eligible across future calling windows until its work is exhausted."
  const FORBIDDEN = [
    "endDate",
    "scheduleEndDate",
    "end_date",
    "expiresAt",
    "SCHEDULE_EXPIRED",
    "isExpired",
    "hasExpired",
    "scheduleExpired",
    "campaignEnded",
    "campaignFinished",
  ] as const;

  it.each(FORBIDDEN)("no campaign file mentions %s", (token) => {
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} mentions ${token}`).not.toContain(token);
    }
  });

  it("no campaign file exposes an EXPIRED campaign status or reason code", () => {
    // `CampaignStatus` has seven constants and none is EXPIRED, so a bare
    // "EXPIRED" in campaign code can only be an invented status or reason.
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} invents an EXPIRED concept`).not.toMatch(/\bEXPIRED\b/);
    }
  });
});

describe("no system-driven campaign transitions (VERIFIED RESERVED_STATES)", () => {
  // The name changed because the model changed: these are reserved
  // execution-facts, not engine-owned rollups. Any *identifier* by this name is
  // the superseded contract resurfacing.
  it("no campaign file declares SYSTEM_DRIVEN_TRANSITIONS as an identifier", () => {
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} references SYSTEM_DRIVEN_TRANSITIONS`).not.toMatch(
        /SYSTEM_DRIVEN_TRANSITIONS/,
      );
    }
  });

  it("no campaign file exports a systemDrivenTransition message helper", () => {
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} exports systemDrivenTransitionMessage`).not.toMatch(
        /systemDrivenTransition/,
      );
    }
  });

  it("the reserved statuses are still exactly the three unreachable ones", () => {
    // Guards the positive claim, so a future edit cannot quietly make one of them
    // reachable again without this file noticing.
    expect([...CAMPAIGN_RESERVED_STATUSES]).toEqual(["RUNNING", "COMPLETED", "FAILED"]);
  });

  it("describes every reserved status as unreachable, not as a markable state", () => {
    // F5 corrected "Marked as running." / "Marked as finished." — wording that
    // implies a control exists to set them. Each must say the state cannot be
    // reached through the API.
    for (const status of CAMPAIGN_RESERVED_STATUSES) {
      const description = CAMPAIGN_STATUS_DESCRIPTION[status as CampaignStatus];
      expect(description, `${status} description`).toMatch(
        /not reachable through the API|Reserved execution-fact/i,
      );
    }
  });

  it("does not describe the SCHEDULED state as auto-advancing", () => {
    // A campaign that is actively dialling still reads SCHEDULED, so any copy
    // promising the status moves on its own is now wrong.
    expect(CAMPAIGN_STATUS_DESCRIPTION.SCHEDULED).toMatch(
      /Nothing moves it forward automatically/,
    );
  });
});

describe("campaign status is never derived from an execution status", () => {
  it("no campaign file assigns an execution status to a campaign field", () => {
    // This is the failure mode that is invisible to everything else: giving a
    // `CampaignStatus` field a `CampaignExecutionStatus` value is only a type
    // error if the two unions are declared separately, and the two share three
    // names — COMPLETED, FAILED and CANCELLED exist in both.
    const SHARED_NAMES = ["COMPLETED", "FAILED", "CANCELLED"];
    for (const [file, code] of Object.entries(CODE)) {
      for (const name of SHARED_NAMES) {
        expect(
          code,
          `${file} assigns execution.${name} to a campaign field`,
        ).not.toMatch(new RegExp(`campaignStatus\\s*[:=][^\\n]*${name}`));
        expect(
          code,
          `${file} derives a campaign status from an execution status`,
        ).not.toMatch(new RegExp(`(campaignStatus|toCampaignStatus)\\s*=\\s*[^\\n]*execution`));
      }
    }
  });

  it("no campaign file compares the two enums for equality", () => {
    // An equality check between the two would be the assumption stated outright.
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} compares campaign and execution status`).not.toMatch(
        /campaignStatus\s*===\s*execution|execution\.status\s*===\s*campaignStatus/,
      );
    }
  });

  it("the model still declares campaign status operator-driven", () => {
    // The positive counterpart: the modules that reason about campaign state
    // agree it is set by an operator, never by the engine.
    expect(CAMPAIGN_STATUS_DESCRIPTION.SCHEDULED).toContain("eligible to run");
  });
});

describe("PAUSED is a dispatch gate, not a cancellation request", () => {
  const FORBIDDEN = ["ControlIntent", "controlIntent"] as const;

  it.each(FORBIDDEN)("no campaign file introduces %s", (token) => {
    // No backend contract defines a control intent enum, and none is needed:
    // `OutboundDialService` gates dispatch on the live campaign status, and a
    // RUNNING execution stays RUNNING while its campaign is PAUSED.
    for (const [file, code] of Object.entries(CODE)) {
      expect(code, `${file} introduces ${token}`).not.toContain(token);
    }
  });

  it("the PAUSED description says established calls are not terminated", () => {
    // VERIFIED the pause gate runs in `dispatchClaimedAttempt`, before any dial.
    // Describing PAUSED as stopping calls in flight would overstate it.
    expect(CAMPAIGN_STATUS_DESCRIPTION.PAUSED).toMatch(
      /established calls are not terminated/i,
    );
  });

  it("the PAUSED description does not claim the campaign is paused-by-execution", () => {
    expect(CAMPAIGN_STATUS_DESCRIPTION.PAUSED).not.toMatch(/completed|failed|expired/i);
  });
});
/* -------------------------------------------------------------------------- */
/* F5.1 — campaign reference targeting                                         */
/* -------------------------------------------------------------------------- */

describe("both reference pickers are narrowed to a target tenant", () => {
  /**
   * F5.1 FIXED. The edit dialog mapped its query results straight into the
   * options while the create dialog has always narrowed them, so a platform or
   * reseller user editing a tenant-T draft was offered other tenants' DIDs,
   * contact groups, audio assets and queues — every one of which
   * `validateDidReference` / `validateContactGroupReference` /
   * `validateContentReferences` / `validateAgentQueueReference` would refuse.
   *
   * This is a source assertion rather than a rendering test because the defect
   * is "which array reaches the picker", which no pure helper can observe and
   * jsdom remains deferred. It is deliberately narrow: it checks that each
   * dialog derives its option rows through `filterByTenant`, which is the
   * decision that was wrong.
   */
  const dialogs = [
    "components/campaigns/create-campaign-dialog.tsx",
    "components/campaigns/edit-campaign-dialog.tsx",
  ];

  it("scans the real dialog files", () => {
    for (const file of dialogs) {
      expect(readFileSync(join(SRC, file), "utf8"), file).toContain("filterByTenant");
    }
  });

  it("the edit dialog narrows to the CAMPAIGN's own tenant", () => {
    // `campaign.tenantId` is the authoritative target and needs no picker,
    // which is why this is correct for every scope rather than only for
    // platform/reseller callers.
    const text = readFileSync(join(SRC, dialogs[1]), "utf8");
    expect(text).toMatch(/campaign\??\.tenantId/);
    expect(text).toMatch(/filterByTenant\(\s*didsQuery\.data/);
    expect(text).toMatch(/filterByTenant\(\s*groupsQuery\.data/);
    expect(text).toMatch(/filterByTenant\(\s*audioQuery\.data/);
    expect(text).toMatch(/filterByTenant\(\s*queuesQuery\.data/);
  });

  it("the edit dialog offers the narrowed rows, not the raw query results", () => {
    // Guards the specific regression: options built from `didsQuery.data ?? []`.
    const text = withoutComments(readFileSync(join(SRC, dialogs[1]), "utf8"));
    expect(text, "didOptions built from the raw query").not.toMatch(
      /didOptions=\{\(didsQuery\.data/,
    );
    expect(text, "didOptions built from the raw query").not.toMatch(
      /contactGroupOptions=\{\(groupsQuery\.data/,
    );
  });

  it("the create dialog keeps narrowing to its chosen target tenant", () => {
    // Unchanged behaviour, pinned so the edit fix did not come at its cost.
    const text = readFileSync(join(SRC, dialogs[0]), "utf8");
    expect(text).toMatch(/filterByTenant\(didsQuery\.data \?\? \[\], targetRequired \? targetTenantId : null\)/);
  });

  it("still offers ACTIVE-only queues and tenant-scoped DIDs on the wire", () => {
    // The F5 guarantees must survive the targeting change.
    const refs = readFileSync(join(SRC, "lib/api/campaign-references.ts"), "utf8");
    expect(refs).toMatch(/status: "ACTIVE"/);
    expect(refs).toMatch(/allocationState: "ASSIGNED"/);
    expect(refs).toMatch(/isCampaignQueueUsable/);
    expect(refs).toMatch(/isCampaignDidUsable/);
    // VERIFIED `QueueDirectoryController.listQueues` still takes only
    // page/size/sort/search, so the queue fetch must not grow a `status`
    // parameter to move this filter server-side. Scoped to the `/queues` call's
    // own params object: a greedy cross-file match would catch the DID fetch's
    // legitimate `status: "ACTIVE"` and fail for the wrong reason.
    const queuesCall = /"\/queues",\s*\{\s*params:\s*\{([^}]*)\}/.exec(refs);
    expect(queuesCall, "the /queues fetch was not found").not.toBeNull();
    expect(queuesCall?.[1], "the queue fetch must not send a status filter").not.toMatch(
      /\bstatus\b/,
    );
  });
});

describe("an absent reference is representable in the campaign schemas", () => {
  /**
   * F5.1 FIXED a defect that made two campaign types unsavable. Every reference
   * control is a `<Select>` whose first option is an empty sentinel, and both
   * dialogs seed an unset reference with `""` — so a CONNECT_BY_AGENT or
   * MISSED_CALL campaign, which never has an audio asset, seeded
   * `audioAssetId: ""` against a `z.string().uuid()` schema and could not be
   * submitted at all.
   */
  const mutation = withoutComments(
    readFileSync(join(SRC, "lib/schemas/campaign-mutation.ts"), "utf8"),
  );

  it("accepts the empty sentinel on every optional reference", () => {
    // The single shared helper both schemas use.
    expect(mutation).toMatch(/value === "" \|\| z\.string\(\)\.uuid\(\)\.safeParse/);
    expect(mutation).toMatch(/const optionalReferenceId = z/);
  });

  it("uses that helper for all four optional references, not a bare uuid()", () => {
    for (const field of ["contactGroupId", "didId", "audioAssetId", "ttsTemplateId"]) {
      expect(
        mutation,
        `${field} still declared as a bare uuid()`,
      ).not.toMatch(new RegExp(`${field}: z\\.string\\(\\)\\.uuid\\(\\)`));
    }
  });

  it("normalises the sentinel to absent before it reaches the wire", () => {
    // `values.didId ?? undefined` is the identity for null/undefined but NOT for
    // "", so the sentinel used to be sent as `{"didId":""}` for a UUID field.
    expect(mutation).toMatch(/function optionalReference\(value: string \| null \| undefined\)/);
    expect(mutation).toMatch(/value === "" \? undefined : value/);
    expect(mutation, "payload still uses ?? undefined for a reference").not.toMatch(
      /didId: values\.didId \?\? undefined/,
    );
  });
});