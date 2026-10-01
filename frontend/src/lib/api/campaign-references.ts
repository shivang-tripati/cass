import { api } from "@/lib/api/client";
import type {
  AudioAssetResponse,
  ContactGroupResponse,
  DidResponse,
  QueueResponse,
  TtsTemplateResponse,
} from "@/lib/api/contracts";
import { unwrapPage } from "@/lib/api/transport";
import type { ApiResponse } from "@/lib/api/types";

/**
 * Reference data for the Campaign create/edit forms: the audiences, numbers and
 * content a campaign may point at.
 *
 * ## Why this is a separate module
 *
 * These are five OTHER domains' list endpoints, reached from the Campaign form.
 * Keeping them out of `campaigns.ts` makes it obvious that nothing here is a
 * campaign operation, and it gives one place to state the constraint that
 * governs all five.
 *
 * ## Every list endpoint clamps `size` to 1..100
 *
 * VERIFIED: each service clamps in its own `buildPageable` —
 * `CampaignService.MAX_PAGE_SIZE = 100`, and the DID, contact-group, audio, TTS
 * and queue services do the same. A picker that requested "all" would silently
 * see 100 rows and present them as the complete set.
 *
 * So every fetch here is explicitly bounded and every picker that uses one must
 * say so. F1 noted this and deferred the fix; F4 does not pretend to solve it
 * either — there is no search-as-you-type composable widget here, so the
 * constant is exported and `REFERENCE_DATA_TRUNCATION_NOTICE` is the sentence
 * to render when a page comes back full. Inventing a "you have more than 100"
 * client-side total would be worse, because the endpoint never sends one.
 *
 * ## THE TENANT-SCOPING LIMITATION — read before using these in a picker
 *
 * `POST /api/v1/campaigns` accepts `?tenantId=` for platform and reseller
 * callers, and the campaign is created in that tenant. A campaign's references
 * must then belong to the SAME tenant — VERIFIED, and deliberately not relaxed
 * by `SUPER_ADMIN` authority:
 *
 *  - `validateContactGroupReference` — `existsByIdAndTenantIdAndDeletedAtIsNull`
 *  - `validateDidReference` — `CampaignResourceValidationService.validateDid`
 *  - `validateContentReferences` — same-tenant for audio; for TTS, same-tenant
 *    or GLOBAL
 *  - `validateAgentQueueReference` — `QueueReferenceService.usabilityOf` looks
 *    up `findByIdAndTenantIdAndDeletedAtIsNull`
 *
 * None of those five list endpoints accepts a `tenantId` parameter. VERIFIED
 * from the controllers: `DidController` is the only one with `tenantId`, and
 * `DidService.list` honours it **only** in the platform branch (L156-157) — for
 * a reseller it is ignored in favour of the whole hierarchy. So a
 * platform/reseller user targeting tenant T is shown rows from several tenants
 * and every one that belongs to another tenant will be rejected on submit.
 *
 * Two honest options exist and this module takes the first:
 *
 *  1. **Filter the fetched rows client-side by `targetTenantId`.** Every one of
 *     these DTOs carries `tenantId`, so the filter uses real data. It is a
 *     presentation filter over an already-authorized list, not a scope change,
 *     and the backend still re-validates ownership independently. This is what
 *     `filterByTenant` does.
 *  2. Show the unfiltered list and let the operator discover the rejections.
 *
 * (1) is used because (2) is a form that submits guaranteed 400s. What is NOT
 * done is inventing a `?tenantId=` on endpoints that ignore it, or presenting
 * a client-side filter as if it were the security boundary — it is not.
 */

/** The server-side page ceiling, shared by all five list services. */
export const REFERENCE_DATA_PAGE_SIZE = 100;

/**
 * Rendered beside any picker fed by these fetches, so a full page reads as a
 * truncated result rather than as the whole catalogue.
 */
export const REFERENCE_DATA_TRUNCATION_NOTICE =
  `Showing the first ${REFERENCE_DATA_PAGE_SIZE} matching records. The server caps every list at ${REFERENCE_DATA_PAGE_SIZE} items; narrow your search to see more.`;

/** A list endpoint returned a full page, so the result is probably incomplete. */
export function isReferencePageTruncated(count: number): boolean {
  return count >= REFERENCE_DATA_PAGE_SIZE;
}

/* -------------------------------------------------------------------------- */
/* Fetchers. Each uses `unwrapPage`, so the envelope stays inside transport.ts. */
/* -------------------------------------------------------------------------- */

/**
 * DIDs a campaign may dial.
 *
 * VERIFIED `CampaignResourceValidationService.validateDid`: a DID is usable
 * when it exists, is not soft-deleted, belongs to the campaign's tenant, and is
 * `DidStatus.ACTIVE` **and** `AllocationState.ASSIGNED`. Both filters are
 * applied server-side here so the picker cannot offer a number the campaign
 * would reject.
 */
export function getCampaignDids(): Promise<DidResponse[]> {
  return unwrapPage<DidResponse>(
    api.get<ApiResponse<DidResponse[]>>("/dids", {
      params: {
        status: "ACTIVE",
        allocationState: "ASSIGNED",
        page: 0,
        size: REFERENCE_DATA_PAGE_SIZE,
        sort: "e164Number,asc",
      },
    }),
  ).then((page) => page.items.filter(isCampaignDidUsable));
}

/**
 * Contact groups a campaign may address as its audience.
 *
 * VERIFIED `validateContactGroupReference`: exists, not soft-deleted, same
 * tenant. There is no status filter, because `ContactGroupEntity` has no
 * lifecycle status — only `deletedAt`. So no status is sent.
 */
export function getCampaignContactGroups(): Promise<ContactGroupResponse[]> {
  return unwrapPage<ContactGroupResponse>(
    api.get<ApiResponse<ContactGroupResponse[]>>("/contact-groups", {
      params: { page: 0, size: REFERENCE_DATA_PAGE_SIZE, sort: "name,asc" },
    }),
  ).then((page) => page.items);
}

/**
 * Audio assets a campaign may play.
 *
 * VERIFIED `validateAudio`: same tenant, not soft-deleted, `APPROVED`, **and a
 * non-blank `storageReference`**. The first three are filterable here; the
 * fourth is not expressible as a query parameter, so a row that is APPROVED but
 * has no stored file remains selectable and will fail at activation with
 * `AUDIO_STORAGE_REFERENCE_MISSING`. F3 already documented that an approved
 * asset can lack a file, and the readiness panel surfaces that specific reason,
 * so the form does not claim to have excluded it.
 */
export function getCampaignAudioAssets(): Promise<AudioAssetResponse[]> {
  return unwrapPage<AudioAssetResponse>(
    api.get<ApiResponse<AudioAssetResponse[]>>("/audio-assets", {
      params: {
        status: "APPROVED",
        page: 0,
        size: REFERENCE_DATA_PAGE_SIZE,
        sort: "name,asc",
      },
    }),
  ).then((page) => page.items);
}

/**
 * TTS templates a campaign may reference.
 *
 * VERIFIED `validateTts` → `TtsTemplateRepository.existsUsableForTenant`, which
 * accepts an APPROVED template that is either GLOBAL (usable by every tenant) or
 * owned by the campaign's tenant. A tenant's list already merges its own rows
 * with APPROVED GLOBAL rows, so `status=APPROVED` yields the usable catalogue
 * across both scopes.
 *
 * See the TTS-executability note in the F4 doc: the backend refuses TTS content
 * for any type whose `playsMedia()` is true, so a media-playing campaign cannot
 * use these rows at all.
 */
export function getCampaignTtsTemplates(): Promise<TtsTemplateResponse[]> {
  return unwrapPage<TtsTemplateResponse>(
    api.get<ApiResponse<TtsTemplateResponse[]>>("/tts-templates", {
      params: {
        status: "APPROVED",
        page: 0,
        size: REFERENCE_DATA_PAGE_SIZE,
        sort: "name,asc",
      },
    }),
  ).then((page) => page.items);
}

/**
 * Queues a CONNECT_BY_AGENT campaign may hand calls to.
 *
 * This answers the F4 brief's §17 question with evidence rather than
 * assumption: the Queue domain EXISTS and is fully REST-exposed —
 * `QueueDirectoryController` at `/api/v1/queues`, gated on `QUEUE_VIEW` /
 * `QUEUE_MANAGE`, both seeded by `V37__create_queue_foundation.sql` and granted
 * to `SUPER_ADMIN`, `RESELLER_ADMIN` and `TENANT_ADMIN`.
 *
 * So `CONNECT_BY_AGENT` is genuinely configurable and gets a real picker. It is
 * NOT the blocker F1 and F3 recorded, which was the IVR capability seed.
 *
 * VERIFIED `QueueReferenceService.usabilityOf`: a queue is usable when it exists,
 * is not soft-deleted, belongs to the campaign's tenant, and is
 * `QueueStatus.ACTIVE`. So this filters to ACTIVE, which also matches the
 * readiness gate that reports `AGENT_QUEUE_NOT_ACTIVE` for an INACTIVE or
 * DISABLED queue. Live agent availability and queue depth are NOT filtered:
 * they are runtime facts the backend explicitly does not gate on.
 *
 * ## F5 CORRECTED THIS — the ACTIVE filter is CLIENT-SIDE, and must be
 *
 * F4 documented ACTIVE filtering here but never performed it: it sent no status
 * parameter and the two call sites then passed the rows through `filterByTenant`
 * alone. So the picker offered INACTIVE and DISABLED queues, which the campaign
 * would reject at activation with `AGENT_QUEUE_NOT_ACTIVE` — a form that submits
 * a guaranteed 400.
 *
 * The filter is genuinely unavoidable, because the endpoint has nowhere to put
 * it. VERIFIED `QueueDirectoryController.listQueues` takes exactly four
 * parameters — `page`, `size`, `sort`, `search`. There is **no `status`
 * parameter and no `tenantId` parameter**, unlike `DidController.list`, which has
 * both. `QueueDirectoryService.listQueues` composes only `notDeleted()`,
 * the caller's boundary, and `search(search)` — it never filters on status.
 *
 * So this is the same PRESENTATION-filter situation `filterByTenant` already
 * documents: it uses the real `QueueResponse.status` field, it narrows an
 * already-authorized list, and it grants nothing. `CampaignService` still
 * re-validates the queue server-side and a wrong pick is a 422 regardless.
 *
 * It is NOT a substitute for a server-side filter, and the endpoint's inability
 * to filter is reported as a contract gap rather than papered over.
 */
export function getCampaignQueues(): Promise<QueueResponse[]> {
  return unwrapPage<QueueResponse>(
    api.get<ApiResponse<QueueResponse[]>>("/queues", {
      params: { page: 0, size: REFERENCE_DATA_PAGE_SIZE, sort: "name,asc" },
    }),
  ).then((page) => page.items.filter(isCampaignQueueUsable));
}

/**
 * VERIFIED `QueueReferenceService.usabilityOf` + `CampaignReadinessService`:
 * a queue a CONNECT_BY_AGENT campaign may reference must be `ACTIVE`.
 *
 * `INACTIVE` is "configured and queryable but not operationally active" and
 * `DISABLED` is terminal; neither is dialable, so neither belongs in the picker.
 * The readiness gate rejects them at activation with `AGENT_QUEUE_NOT_ACTIVE`,
 * which is exactly the guaranteed failure this avoids offering.
 */
export function isCampaignQueueUsable(queue: QueueResponse): boolean {
  return queue.status === "ACTIVE";
}

/**
 * The option list plus whether the campaign's current queue is missing from it.
 *
 * ## Why the current value must be preserved rather than dropped
 *
 * `getCampaignQueues` returns only ACTIVE queues, because that is the only
 * campaign-usable status. So if the queue a campaign already points at is later
 * deactivated, it simply vanishes from the option list while the form value
 * still holds its id. The `<select>` then has a value that matches no
 * `<option>` and renders as if nothing were chosen — while a submit would still
 * send the stored id.
 *
 * That is the "silently replacing it" failure: the operator sees an empty
 * picker, has no idea the campaign is already pointed at an unusable queue, and
 * gets a rejection from `validateAgentQueueReference` with no explanation of
 * what they were actually looking at.
 *
 * ## What is deliberately NOT claimed
 *
 * The label says the queue is **not active**. It does not say whether it is
 * INACTIVE, DISABLED, deleted, or in another tenant — because the ACTIVE-filtered
 * list genuinely cannot distinguish those four, and guessing would be inventing a
 * status the frontend never read. `GET /queues/{queueId}` exists and could answer
 * it, but the picker deliberately does not add a request per stale value.
 *
 * Backend validation stays authoritative: submitting the preserved value still
 * goes to `validateAgentQueueReference` and is still refused with the backend's
 * own message. Preserving the option changes what the operator sees, not what the
 * server accepts.
 */
export interface QueueSelectionOptions {
  readonly options: readonly { value: string; label: string }[];
  /** The campaign points at a queue that is not an active one. */
  readonly currentQueueUnavailable: boolean;
}

export const CURRENT_QUEUE_UNAVAILABLE_LABEL =
  "Current queue (not an active queue)";

export function buildQueueSelectionOptions(
  queues: readonly QueueResponse[],
  currentQueueId: string | null,
): QueueSelectionOptions {
  const options = queues.map((queue) => ({
    value: queue.id,
    label: queue.name,
  }));
  if (!currentQueueId || options.some((option) => option.value === currentQueueId)) {
    return { options, currentQueueUnavailable: false };
  }
  return {
    // Kept FIRST so the select shows the real situation rather than an empty
    // "— Select a queue —" that looks like an unconfigured campaign.
    options: [
      { value: currentQueueId, label: CURRENT_QUEUE_UNAVAILABLE_LABEL },
      ...options,
    ],
    currentQueueUnavailable: true,
  };
}

/**
 * Whether a DID may be offered as a campaign DID.
 *
 * VERIFIED `CampaignResourceValidationService.validateDid`:
 *
 * ```java
 * didRepository.existsByIdAndTenantIdAndDeletedAtIsNullAndStatusAndAllocationState(
 *     didId, tenantId, DidStatus.ACTIVE, AllocationState.ASSIGNED)
 * ```
 *
 * `getCampaignDids` already asks the server for `status=ACTIVE` and
 * `allocationState=ASSIGNED`, so those two halves are genuinely server-side and
 * are deliberately NOT re-checked here — a client cannot widen authority by
 * asserting a filter it did not perform.
 *
 * The third half is the one no endpoint can express, and it is the pool case:
 * VERIFIED `AllocationState`'s Javadoc — "AVAILABLE numbers may sit in the
 * platform or reseller pool; ASSIGNED numbers belong to exactly one tenant
 * (enforced by `ck_dids_assigned_requires_tenant`)" — so an AVAILABLE DID has
 * `tenantId = null`, and `validateDid` scopes its lookup BY that tenant id. A
 * pool DID therefore cannot match any campaign and is never usable, no matter
 * which tenant the campaign is created in.
 *
 * The backend already excludes these today, because an unassigned DID is
 * `AVAILABLE` and the server-side `allocationState=ASSIGNED` filter removes it.
 * This predicate states that rule explicitly rather than leaving it as an
 * emergent consequence of two independent filters, which is what makes it safe
 * to keep if either filter is ever changed.
 */
export function isCampaignDidUsable(did: DidResponse): boolean {
  return did.status === "ACTIVE" && did.allocationState === "ASSIGNED" && did.tenantId !== null;
}

/* -------------------------------------------------------------------------- */
/* Tenant filtering                                                            */
/* -------------------------------------------------------------------------- */

/** Any reference row that carries the owning tenant, if it has one.
 *
 * F5 CORRECTED THIS: `tenantId` is `string | null`, not `string`. VERIFIED
 * `DidEntity.tenantId` is a nullable column — "null while unassigned" — so an
 * AVAILABLE pool DID genuinely carries `null` and the previous non-nullable type
 * was unsound.
 *
 * The wider type does not weaken the filter: `filterByTenant` compares against a
 * concrete target id, and `null` never equals one, so an unowned row is dropped
 * from every targeted list exactly as before.
 */
export interface TenantOwned {
  readonly tenantId: string | null;
}

/**
 * Restrict a fetched list to the tenant the campaign is being created in.
 *
 * Pass `null` for a TENANT-scoped caller, whose list is already restricted by
 * the backend and needs no narrowing.
 *
 * This is a PRESENTATION filter over data the caller is already authorized to
 * read. It grants nothing: `CampaignService` re-validates every reference's
 * tenant server-side and a wrong pick is a 400 regardless of what this returns.
 */
export function filterByTenant<T extends TenantOwned>(
  rows: readonly T[],
  targetTenantId: string | null,
): T[] {
  if (targetTenantId === null) return [...rows];
  return rows.filter((row) => row.tenantId === targetTenantId);
}

/**
 * A TTS row is usable by a target tenant when it is that tenant's own, or a
 * GLOBAL platform-owned row. VERIFIED: `existsUsableForTenant` accepts exactly
 * those two, and no other scope.
 */
export function isTtsUsableForTenant(
  row: TtsTemplateResponse,
  targetTenantId: string | null,
): boolean {
  if (row.scope === "GLOBAL") return true;
  if (targetTenantId === null) return true;
  return row.tenantId === targetTenantId;
}

/** `filterByTenant` plus the GLOBAL-row exemption TTS needs. */
export function filterTtsForTenant(
  rows: readonly TtsTemplateResponse[],
  targetTenantId: string | null,
): TtsTemplateResponse[] {
  if (targetTenantId === null) return [...rows];
  return rows.filter((row) => isTtsUsableForTenant(row, targetTenantId));
}
