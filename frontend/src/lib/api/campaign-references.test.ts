import { describe, expect, it } from "vitest";

import {
  CURRENT_QUEUE_UNAVAILABLE_LABEL,
  REFERENCE_DATA_PAGE_SIZE,
  buildQueueSelectionOptions,
  filterByTenant,
  filterTtsForTenant,
  isCampaignDidUsable,
  isCampaignQueueUsable,
  isReferencePageTruncated,
  isTtsUsableForTenant,
} from "@/lib/api/campaign-references";
import { connectByAgentQueueId } from "@/lib/api/contracts";
import type {
  AllocationState,
  DidResponse,
  DidStatus,
  QueueResponse,
  QueueStatus,
  TtsTemplateResponse,
} from "@/lib/api/contracts";

/**
 * F4 — contract tests for campaign reference data.
 *
 * ## The limitation these tests make explicit
 *
 * `POST /campaigns` accepts `?tenantId=` for platform and reseller callers, and
 * every campaign reference must belong to the SAME tenant. VERIFIED, and
 * deliberately not relaxed by `SUPER_ADMIN` authority:
 *
 *  - `validateContactGroupReference` → `existsByIdAndTenantIdAndDeletedAtIsNull`
 *  - `validateDidReference` → `CampaignResourceValidationService.validateDid`
 *  - `validateContentReferences` → same-tenant audio; TTS same-tenant or GLOBAL
 *  - `validateAgentQueueReference` → `QueueReferenceService.usabilityOf`
 *
 * But VERIFIED from the controllers, **none of the five list endpoints accepts a
 * `tenantId` parameter**, and `DidService.list` honours the one it does have
 * only in the platform branch — for a reseller it is ignored in favour of the
 * whole hierarchy. So a platform or reseller user targeting one tenant is
 * served rows from several, and every row from another tenant is a guaranteed
 * 400 on submit.
 *
 * `filterByTenant` narrows the already-authorized list so the form cannot offer
 * a guaranteed rejection. It is a PRESENTATION filter, not a scope change, and
 * the backend re-validates ownership independently — which is why these tests
 * are about the filter's shape and not about any claim of enforcement.
 */

const TENANT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const TENANT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

describe("REFERENCE_DATA_PAGE_SIZE", () => {
  it("is 100, matching every list service's own clamp", () => {
    // VERIFIED: `CampaignService.MAX_PAGE_SIZE = 100` and the DID,
    // contact-group, audio, TTS and queue services each clamp in their own
    // `buildPageable`. A picker asking for more is silently capped.
    expect(REFERENCE_DATA_PAGE_SIZE).toBe(100);
  });
});

describe("isReferencePageTruncated", () => {
  it("is true once a page comes back full, because more may exist", () => {
    expect(isReferencePageTruncated(REFERENCE_DATA_PAGE_SIZE)).toBe(true);
    expect(isReferencePageTruncated(REFERENCE_DATA_PAGE_SIZE + 1)).toBe(true);
  });

  it("is false below the cap", () => {
    expect(isReferencePageTruncated(0)).toBe(false);
    expect(isReferencePageTruncated(99)).toBe(false);
  });

  it("never claims a count the endpoint could not have sent", () => {
    // The endpoint sends no total, so the UI must not imply one. This predicate
    // is the only place a "there may be more" claim is made, and it is made
    // from the page size alone.
    expect(isReferencePageTruncated(REFERENCE_DATA_PAGE_SIZE)).toBe(true);
  });
});

describe("filterByTenant", () => {
  const rows = [
    { id: "1", tenantId: TENANT_A, name: "A group" },
    { id: "2", tenantId: TENANT_B, name: "B group" },
    { id: "3", tenantId: TENANT_A, name: "Another A group" },
  ];

  it("returns everything for a null target — a tenant caller needs no narrowing", () => {
    expect(filterByTenant(rows, null)).toHaveLength(3);
  });

  it("keeps only the target tenant's rows", () => {
    expect(filterByTenant(rows, TENANT_A).map((row) => row.id)).toEqual(["1", "3"]);
    expect(filterByTenant(rows, TENANT_B).map((row) => row.id)).toEqual(["2"]);
  });

  it("returns an empty list for a tenant with no rows", () => {
    expect(filterByTenant(rows, "cccccccc-cccc-4ccc-8ccc-cccccccccccc")).toEqual([]);
  });

  it("returns a copy, not the input array, so callers cannot mutate the cache", () => {
    const input = [...rows];
    const output = filterByTenant(input, null);
    expect(output).not.toBe(input);
    expect(output).toEqual(input);
  });

  it("does not mutate its input", () => {
    const input = [...rows];
    filterByTenant(input, TENANT_A);
    expect(input).toHaveLength(3);
  });
});

function tts(
  id: string,
  tenantId: string,
  scope: TtsTemplateResponse["scope"],
): TtsTemplateResponse {
  return {
    id,
    tenantId,
    name: `Template ${id}`,
    description: null,
    templateText: "Hello",
    variables: [],
    status: "APPROVED",
    scope,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: null,
  } as TtsTemplateResponse;
}

describe("isTtsUsableForTenant", () => {
  it("accepts a GLOBAL row for any target tenant", () => {
    // VERIFIED `TtsTemplateRepository.existsUsableForTenant` accepts an APPROVED
    // template that is GLOBAL (platform-owned, usable by every tenant) OR owned
    // by the campaign's tenant. No other scope qualifies.
    expect(isTtsUsableForTenant(tts("g", "00000000-0000-4000-8000-000000000000", "GLOBAL"), TENANT_A)).toBe(true);
    expect(isTtsUsableForTenant(tts("g", "00000000-0000-4000-8000-000000000000", "GLOBAL"), TENANT_B)).toBe(true);
  });

  it("accepts a TENANT row only for its own tenant", () => {
    expect(isTtsUsableForTenant(tts("a", TENANT_A, "TENANT"), TENANT_A)).toBe(true);
    expect(isTtsUsableForTenant(tts("a", TENANT_A, "TENANT"), TENANT_B)).toBe(false);
  });

  it("accepts everything for a null target, matching the un-narrowed list", () => {
    expect(isTtsUsableForTenant(tts("a", TENANT_A, "TENANT"), null)).toBe(true);
    expect(
      isTtsUsableForTenant(tts("g", "00000000-0000-4000-8000-000000000000", "GLOBAL"), null),
    ).toBe(true);
  });
});

describe("filterTtsForTenant", () => {
  it("keeps the target tenant's rows AND every GLOBAL row", () => {
    // The asymmetry that plain `filterByTenant` gets wrong: a GLOBAL template is
    // owned by the platform but usable by the target tenant, so filtering it
    // out would hide a template the campaign may legitimately reference.
    const rows = [
      tts("a1", TENANT_A, "TENANT"),
      tts("a2", TENANT_A, "TENANT"),
      tts("g1", "00000000-0000-4000-8000-000000000000", "GLOBAL"),
      tts("b1", TENANT_B, "TENANT"),
    ];
    expect(filterTtsForTenant(rows, TENANT_A).map((row) => row.id)).toEqual([
      "a1",
      "a2",
      "g1",
    ]);
  });

  it("drops another tenant's rows entirely", () => {
    const rows = [tts("b1", TENANT_B, "TENANT"), tts("g1", "00000000-0000-4000-8000-000000000000", "GLOBAL")];
    expect(filterTtsForTenant(rows, TENANT_A).map((row) => row.id)).toEqual(["g1"]);
  });

  it("returns everything for a null target", () => {
    const rows = [tts("a1", TENANT_A, "TENANT"), tts("b1", TENANT_B, "TENANT")];
    expect(filterTtsForTenant(rows, null)).toHaveLength(2);
  });
});

/* -------------------------------------------------------------------------- */
/* F5 — Queue + DID usability and the stale-reference contract                 */
/* -------------------------------------------------------------------------- */

/** VERIFIED QueueStatus: exactly three constants. */
const QUEUE_STATUSES: readonly QueueStatus[] = ["ACTIVE", "INACTIVE", "DISABLED"];

function queue(id: string, status: QueueStatus): QueueResponse {
  return {
    id,
    tenantId: TENANT_A,
    name: `Queue ${id}`,
    description: null,
    status,
    maxWaitingCalls: 0,
    maxWaitSeconds: 0,
    overflowEnabled: false,
    overflowQueueId: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: null,
  };
}

function did(
  id: string,
  status: DidStatus,
  allocationState: AllocationState,
  tenantId: string | null,
): DidResponse {
  return {
    id,
    tenantId,
    resellerId: null,
    e164Number: "+918012345678",
    countryCode: "91",
    areaCode: null,
    circle: null,
    numberType: "MOBILE",
    provider: "TEST",
    status,
    capabilities: ["VOICE_OUTBOUND"],
    allocationState,
    allocationSource: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

describe("isCampaignQueueUsable", () => {
  it("accepts ACTIVE - the only campaign-usable queue status", () => {
    expect(isCampaignQueueUsable(queue("q1", "ACTIVE"))).toBe(true);
  });

  it("rejects INACTIVE and DISABLED - exhaustively over the enum", () => {
    // VERIFIED `QueueReferenceService.usabilityOf`: `status == ACTIVE ? USABLE :
    // NOT_ACTIVE`. INACTIVE is "configured and queryable but not operationally
    // active"; DISABLED is terminal. Neither can be dialled, and readiness reports
    // AGENT_QUEUE_NOT_ACTIVE for both.
    expect(isCampaignQueueUsable(queue("q1", "INACTIVE"))).toBe(false);
    expect(isCampaignQueueUsable(queue("q1", "DISABLED"))).toBe(false);
  });

  it("agrees with the enum exhaustively", () => {
    for (const status of QUEUE_STATUSES) {
      expect(isCampaignQueueUsable(queue("q1", status)), status).toBe(
        status === "ACTIVE",
      );
    }
  });

  it("depends on status alone - capacity, overflow and description are not gates", () => {
    // VERIFIED the controller's own tag: capacity/timeout/overflow are "persisted,
    // not executed". Only the administrative status decides usability.
    const busy = queue("q1", "ACTIVE");
    expect(isCampaignQueueUsable({ ...busy, maxWaitingCalls: 100000 })).toBe(true);
    expect(isCampaignQueueUsable({ ...busy, overflowEnabled: true })).toBe(true);
  });
});

describe("isCampaignDidUsable", () => {
  it("accepts an ACTIVE, ASSIGNED, tenant-bound DID", () => {
    expect(isCampaignDidUsable(did("d1", "ACTIVE", "ASSIGNED", TENANT_A))).toBe(true);
  });

  it("rejects an INACTIVE DID", () => {
    // VERIFIED `validateDid` requires `DidStatus.ACTIVE`.
    expect(isCampaignDidUsable(did("d1", "INACTIVE", "ASSIGNED", TENANT_A))).toBe(false);
  });

  it("rejects an unassigned DID", () => {
    // VERIFIED `validateDid` requires `AllocationState.ASSIGNED`.
    expect(isCampaignDidUsable(did("d1", "ACTIVE", "AVAILABLE", TENANT_A))).toBe(false);
  });

  it("rejects a pool DID, which has no owning tenant", () => {
    // The case the nullability fix makes representable. VERIFIED `AllocationState`:
    // "AVAILABLE numbers may sit in the platform or reseller pool", and
    // `validateDid` scopes its lookup BY the campaign tenant - so an unowned DID
    // can never match any campaign.
    expect(isCampaignDidUsable(did("d1", "ACTIVE", "ASSIGNED", null))).toBe(false);
  });

  it("rejects every combination that is not all-three - exhaustively", () => {
    const statuses: readonly DidStatus[] = ["ACTIVE", "INACTIVE"];
    const states: readonly AllocationState[] = ["AVAILABLE", "ASSIGNED"];
    for (const status of statuses) {
      for (const allocationState of states) {
        for (const tenantId of [TENANT_A, null]) {
          const expected = status === "ACTIVE" && allocationState === "ASSIGNED" && tenantId !== null;
          expect(
            isCampaignDidUsable(did("d1", status, allocationState, tenantId)),
            `${status}/${allocationState}/${tenantId === null ? "pool" : "tenant"}`,
          ).toBe(expected);
        }
      }
    }
  });
});

describe("filterByTenant handles an unowned row", () => {
  it("drops a null-tenant row from every targeted list", () => {
    // `DidEntity.tenantId` is nullable, so the wider `TenantOwned` type is real.
    // A pool row must never be offered for a chosen tenant.
    const rows = [
      { id: "1", tenantId: TENANT_A },
      { id: "2", tenantId: null },
    ];
    expect(filterByTenant(rows, TENANT_A).map((r) => r.id)).toEqual(["1"]);
  });

  it("keeps a null-tenant row when no target is chosen", () => {
    // A TENANT-scoped caller passes null because its list is already scoped. The
    // backend cannot serve it a pool DID under that scope, so nothing is filtered
    // here - but this documents that the filter is a no-op in that case rather
    // than a hidden ownership check.
    const rows = [{ id: "2", tenantId: null }];
    expect(filterByTenant(rows, null)).toHaveLength(1);
  });
});

describe("buildQueueSelectionOptions", () => {
  it("passes active queues through unchanged", () => {
    const selection = buildQueueSelectionOptions([queue("q1", "ACTIVE")], null);
    expect(selection.currentQueueUnavailable).toBe(false);
    expect(selection.options).toEqual([{ value: "q1", label: "Queue q1" }]);
  });

  it("does not duplicate the current queue when it is still active", () => {
    const selection = buildQueueSelectionOptions([queue("q1", "ACTIVE")], "q1");
    expect(selection.currentQueueUnavailable).toBe(false);
    expect(selection.options.filter((o) => o.value === "q1")).toHaveLength(1);
    expect(selection.options[0].label).toBe("Queue q1");
  });

  it("preserves a queue that is no longer active instead of dropping it", () => {
    // The defect this exists for: without it the select would hold a value
    // matching no option, rendering as unconfigured while still submitting the
    // stored id.
    const selection = buildQueueSelectionOptions([queue("q2", "ACTIVE")], "q1");
    expect(selection.currentQueueUnavailable).toBe(true);
    expect(selection.options[0]).toEqual({
      value: "q1",
      label: CURRENT_QUEUE_UNAVAILABLE_LABEL,
    });
    expect(selection.options.map((o) => o.value)).toContain("q2");
  });

  it("treats an empty current value as nothing to preserve", () => {
    const selection = buildQueueSelectionOptions([], "");
    expect(selection.currentQueueUnavailable).toBe(false);
    expect(selection.options).toEqual([]);
  });

  it("reports a preserved queue even when no active queue is returned at all", () => {
    const selection = buildQueueSelectionOptions([], "q1");
    expect(selection.currentQueueUnavailable).toBe(true);
    expect(selection.options).toHaveLength(1);
  });

  it("claims only that the queue is not active, never which status it is", () => {
    // The ACTIVE-filtered list cannot distinguish INACTIVE from DISABLED from
    // deleted from another tenant, so the label must not guess.
    expect(CURRENT_QUEUE_UNAVAILABLE_LABEL).not.toMatch(
      /inactive|disabled|deleted|removed/i,
    );
    expect(CURRENT_QUEUE_UNAVAILABLE_LABEL).toBe(
      "Current queue (not an active queue)",
    );
  });

  it("does not mutate the queues it was given", () => {
    const queues = [queue("q1", "ACTIVE")];
    buildQueueSelectionOptions(queues, "q9");
    expect(queues).toHaveLength(1);
  });
});

describe("connectByAgentQueueId", () => {
  it("reads the queue out of a CONNECT_BY_AGENT typeConfig", () => {
    expect(
      connectByAgentQueueId({
        connectByAgent: {
          queueId: "q1",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      }),
    ).toBe("q1");
  });

  it("returns null for every other campaign type - exhaustively", () => {
    // Campaign stores the queue INSIDE typeConfig, so reading
    // `typeConfig.connectByAgent.queueId` directly needs a cast, and a cast is
    // how a PLAYFILE campaign ends up rendering a queue row.
    expect(
      connectByAgentQueueId({
        dtmf: { expected: "1", maxDigits: 1, timeoutSecs: 10, action: "TERMINATE" },
      }),
    ).toBeNull();
    expect(connectByAgentQueueId({ missedCall: { ringDurationSeconds: 30 } })).toBeNull();
    expect(connectByAgentQueueId({})).toBeNull();
  });

  it("returns null for absent or non-object input", () => {
    expect(connectByAgentQueueId(null)).toBeNull();
    expect(connectByAgentQueueId(undefined)).toBeNull();
  });

  it("returns null rather than an empty string for a blank queue id", () => {
    expect(
      connectByAgentQueueId({
        connectByAgent: {
          queueId: "",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      }),
    ).toBeNull();
  });
});