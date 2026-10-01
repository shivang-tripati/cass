import { api } from "@/lib/api/client";
import type {
  CampaignResponse,
  CreateCampaignPayload,
  UpdateCampaignPayload,
  UpdateCampaignStatusPayload,
  ExecuteCampaignPayload,
  CampaignReadinessResponse,
  CampaignExecutionResponse,
  CampaignStatus,
  CampaignType,
  CampaignRunMode,
} from "@/lib/api/contracts";
import { sendVoid, unwrap, unwrapPage } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/campaigns contract (verified against CampaignController/CampaignService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, createdAt, updatedAt, status, campaignType; default createdAt,desc
 * - status: CampaignStatus name; unknown → 400
 * - campaignType: CampaignType name; unknown → 400
 * - runMode: CampaignRunMode name; unknown → 400
 * - search: case-insensitive contains over name and description
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const CAMPAIGN_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
  "status",
  "campaignType",
] as const;

export type CampaignSortField = (typeof CAMPAIGN_SORTABLE_FIELDS)[number];

export interface CampaignListParams {
  page: number;
  size: number;
  sortField: CampaignSortField;
  sortDirection: "asc" | "desc";
  status?: CampaignStatus;
  campaignType?: CampaignType;
  runMode?: CampaignRunMode;
  search?: string;
}

/** Stable query-key factory for the Campaigns module. */
export const campaignsKeys = {
  all: ["campaigns"] as const,
  list: (params: CampaignListParams) => ["campaigns", "list", params] as const,
  detail: (campaignId: string) => ["campaigns", "detail", campaignId] as const,
  readiness: (campaignId: string) => ["campaigns", "readiness", campaignId] as const,
  executions: (campaignId: string) => ["campaigns", "executions", campaignId] as const,
  execution: (campaignId: string, executionId: string) => ["campaigns", "executions", campaignId, executionId] as const,
};

export interface CampaignPage {
  items: CampaignResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: CampaignListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export function getCampaigns(params: CampaignListParams): Promise<CampaignPage> {
  // F4: this endpoint genuinely paginates — `CampaignService.list` ends with
  // `ResponseFactory.page(items, PaginationMetadata.of(...))` — so `unwrapPage`
  // is the correct transport helper. The previous version read `data.data` and
  // `data.pagination` directly, which re-implemented the envelope in a domain
  // service and made `transport.ts` no longer the only module that knows
  // `ApiResponse` exists. See F4 doc §12, drift item D1.
  return unwrapPage(
    api.get("/campaigns", {
      params: {
        page: params.page,
        size: params.size,
        sort: buildSort(params),
        status: params.status || undefined,
        campaignType: params.campaignType || undefined,
        runMode: params.runMode || undefined,
        search: params.search || undefined,
      },
    }),
  );
}

export function getCampaign(campaignId: string): Promise<CampaignResponse> {
  return unwrap(api.get<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}`));
}

export function createCampaign(
  payload: CreateCampaignPayload,
  tenantId?: string,
): Promise<CampaignResponse> {
  // `?tenantId=` is a real backend parameter, not a frontend scope switch.
  // VERIFIED `CampaignService.create` L110-119: the caller's own tenant context
  // WINS when present, and this parameter is only consulted when it is absent.
  // So it must be sent only by a PLATFORM or RESELLER caller — a tenant caller
  // is silently ignored, which is why `campaignCreateTargetFor` returns `null`
  // for TENANT scope rather than passing the tenant's own id.
  //
  // Passing it never widens authority: the service still checks
  // `CAMPAIGN_MANAGE` against `forTenant(target)`, so a reseller can only target
  // a tenant in its own hierarchy.
  const params = tenantId ? { tenantId } : undefined;
  return unwrap(api.post<ApiResponse<CampaignResponse>>("/campaigns", payload, { params }));
}

export function updateCampaign(
  campaignId: string,
  payload: UpdateCampaignPayload,
): Promise<CampaignResponse> {
  return unwrap(
    api.put<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}`, payload),
  );
}

/** Soft-deletes a campaign. Bare 204 — no envelope to unwrap. */
export function deleteCampaign(campaignId: string): Promise<void> {
  return sendVoid(api.delete(`/campaigns/${campaignId}`));
}

/** Changes campaign lifecycle status via dedicated PATCH endpoint. */
export function changeCampaignStatus(
  campaignId: string,
  payload: UpdateCampaignStatusPayload,
): Promise<CampaignResponse> {
  return unwrap(
    api.patch<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}/status`, payload),
  );
}

/** Clones a campaign (POST /api/v1/campaigns/{id}/clone). */
export function cloneCampaign(campaignId: string): Promise<CampaignResponse> {
  return unwrap(
    api.post<ApiResponse<CampaignResponse>>(`/campaigns/${campaignId}/clone`),
  );
}

/** Checks campaign execution readiness (GET /api/v1/campaigns/{id}/readiness). */
export function getCampaignReadiness(
  campaignId: string,
): Promise<CampaignReadinessResponse> {
  return unwrap(
    api.get<ApiResponse<CampaignReadinessResponse>>(`/campaigns/${campaignId}/readiness`),
  );
}

/**
 * Requests an execution (POST /api/v1/campaigns/{id}/executions).
 *
 * Returns `REQUESTED`. VERIFIED `CampaignExecutionService.execute`: it evaluates
 * readiness first and throws `BUSINESS_RULE_VIOLATION` (422) listing every
 * blocking reason, and only then materialises the configuration snapshot.
 *
 * ## The status code is NOT always 201 — a retry is a 200
 *
 * VERIFIED L26-32: when a non-blank `idempotencyKey` already exists for this
 * campaign, the service returns the existing execution with
 * `ResponseFactory.ok(...)` — a **200** — instead of `created(...)`. So a client
 * that treats 201 as "I created this" is wrong on the second submit of the same
 * key. Both statuses carry the same body shape, so `unwrap` handles both; the
 * distinction matters only if the UI wants to say "already requested" rather
 * than "requested", which it must not assert without checking the status.
 *
 * An execution is a REQUESTED record that the platform's own execution engine
 * then advances. VERIFIED: `CampaignExecutionOrchestrator` is a `@Service` with
 * `@Scheduled(fixedDelay = 30000) scheduledTick()` (and `@EnableScheduling` on
 * `ObdApplication`), whose tick starts REQUESTED executions, processes retries,
 * dials due attempts, pumps ESL events and settles RUNNING ones. So the response
 * status is a starting position, not a final one, and a client that treats it as
 * settled is wrong.
 *
 * F4.1 — the engine's behaviour is more specific than "it advances executions",
 * and the two cases are distinguishable:
 *
 *  - If the campaign is not in an executable lifecycle state, `doStartExecution`
 *    calls `isDeferredRatherThanFailed`, sees
 *    `CAMPAIGN_NOT_EXECUTABLE_STATE`, and **returns without changing the
 *    execution**. It stays REQUESTED and is retried on the next tick, so a
 *    paused or draft campaign leaves its requests pending indefinitely.
 *  - Any OTHER readiness reason **fails** the execution outright, with
 *    `failureReason = "Campaign not ready: " + <reasons>`. So a request against a
 *    campaign whose resources degraded after the request can still end FAILED.
 *
 * There is no REST operation that advances an execution — the engine owns that.
 *
 * ## Campaign status is NOT derived from any execution — VERIFIED, and now
 * ## settled as intentional by the backend's own enum contract
 *
 * VERIFIED: the only production writer of `CampaignEntity.status` outside the
 * clone path is `CampaignService.changeStatus`, reachable only from
 * `PATCH /api/v1/campaigns/{id}/status`. No engine path writes it.
 *
 * F4.1 filed **B9** here as "RESOLVED — MISSING PRODUCER" on the strength of the
 * then-current `CampaignStatus` Javadoc, which declared RUNNING/COMPLETED/FAILED
 * "aggregate rollups driven by the future execution engine". The backend has
 * since rewritten that Javadoc, and the new text removes the ambiguity the old
 * text created:
 *
 * > "It is **not** derived from executions: several executions may run for one
 * > campaign, so no single execution could authoritatively set it."
 * > "A campaign that is actively dialling reads `SCHEDULED`; whether that work is
 * > running, finished or failed is read from its executions."
 * > "`RUNNING`, `COMPLETED` and `FAILED` are reserved execution-facts, unreachable
 * > and not operator-settable. They are retained only because
 * > `ck_campaigns_status` is a database CHECK constraint (V15) and they are part
 * > of the public contract."
 *
 * So B9 is **RESOLVED — INTENTIONAL**: there is no missing producer, and adding
 * one would be wrong, because the stated reason no producer exists is a real
 * modelling fact (many executions per campaign) rather than an oversight.
 *
 * The enforcement half is `CampaignService.RESERVED_STATES` (the name F4.1
 * corrected from the never-real `SYSTEM_DRIVEN_TRANSITIONS`): a transition whose
 * target is reserved is refused with 409, which is why
 * `availableTransitions` in `lib/domain/campaign-lifecycle` subtracts those
 * edges instead of offering controls that can only fail.
 */
export function executeCampaign(
  campaignId: string,
  payload: ExecuteCampaignPayload,
): Promise<CampaignExecutionResponse> {
  return unwrap(
    api.post<ApiResponse<CampaignExecutionResponse>>(
      `/campaigns/${campaignId}/executions`,
      payload,
    ),
  );
}

/**
 * Lists executions for a campaign (GET /api/v1/campaigns/{campaignId}/executions).
 *
 * VERIFIED `CampaignExecutionService.listExecutions` returns
 * `ResponseFactory.ok(list)` — **no pagination block** — ordered by
 * `requestedAt` descending. So this is honestly a bare array, and there is no
 * server-side filtering, sorting or paging to drive a control from.
 *
 * The statuses in the response change on their own: VERIFIED
 * `CampaignExecutionOrchestrator.scheduledTick` runs every 30 s and moves
 * REQUESTED -> RUNNING -> COMPLETED/FAILED. A caller displaying this list
 * therefore has to poll while any row is still dispatchable, or it shows a
 * permanently stale value.
 */
export function listCampaignExecutions(
  campaignId: string,
): Promise<CampaignExecutionResponse[]> {
  return unwrap(
    api.get<ApiResponse<CampaignExecutionResponse[]>>(
      `/campaigns/${campaignId}/executions`,
    ),
  );
}

/** Gets a specific execution (GET /api/v1/campaigns/{campaignId}/executions/{executionId}). */
export function getCampaignExecution(
  campaignId: string,
  executionId: string,
): Promise<CampaignExecutionResponse> {
  return unwrap(
    api.get<ApiResponse<CampaignExecutionResponse>>(
      `/campaigns/${campaignId}/executions/${executionId}`,
    ),
  );
}

/**
 * F4 REMOVED from this module — `CAMPAIGN_LEGAL_TRANSITIONS` and
 * `CAMPAIGN_SYSTEM_TRANSITIONS` now live in `@/lib/domain/campaign-lifecycle`,
 * next to the rule that explains WHY the engine-driven edges must be filtered
 * out before rendering.
 *
 * They were duplicated here AND in `campaign-table.tsx`, and the table's copy was
 * already wrong in a way that mattered: it filtered on
 * `["COMPLETED","FAILED","ARCHIVED"].includes(status)` instead of subtracting
 * the engine-driven edges, so a RUNNING campaign was offered COMPLETED and
 * FAILED — both permanently 409 through `PATCH /status`. See F4 doc §12, drift
 * items D3 and D4.
 */


// ==== Reference data ====
//
// F4 MOVED the four reference fetchers to `@/lib/api/campaign-references`,
// joined by a Queue fetcher that did not exist before. They are five other
// domains' list endpoints, not campaign operations, and keeping them here
// re-implemented the `ApiResponse` envelope four more times — each one read
// `data.data` directly, so `transport.ts` was not the only module that knew
// the envelope existed. See F4 doc §12, drift item D2.
