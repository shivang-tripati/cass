import { api } from "@/lib/api/client";
import type {
  CreateTtsTemplatePayload,
  TtsTemplateResponse,
  TtsTemplateStatus,
  UpdateTtsTemplatePayload,
} from "@/lib/api/contracts";
import { sendVoid, unwrap } from "@/lib/api/transport";
import type { ApiResponse, PaginationMetadata } from "@/lib/api/types";

/**
 * /api/v1/tts-templates — VERIFIED (F3) against `TtsTemplateController` and
 * `TtsTemplateService`.
 *
 * ## There is NO generation or synthesis endpoint
 *
 * The controller's own tag says "Rendering/synthesis belongs to the future
 * execution layer", and the `tts` package contains no provider, no voice model
 * and no render method. So F3 builds **no** generate/preview/synthesise UI, and
 * no "this template produces an audio asset" flow. A template is a reusable
 * *text definition*; nothing in this API turns it into sound.
 *
 * ## This is not a generic list of templates
 *
 * `TtsTemplateScope` splits the resource in two, and V42 makes the split a
 * database invariant rather than a convention:
 *
 * ```sql
 * CHECK (scope <> 'GLOBAL' OR tenant_id IS NULL)   -- global is platform-owned
 * CHECK (scope <> 'TENANT' OR tenant_id IS NOT NULL)
 * ```
 *
 * | | GLOBAL | TENANT |
 * |---|---|---|
 * | `tenantId` | `null` | owning tenant |
 * | created by | platform callers only | the owning tenant, or a platform caller seeding one |
 * | created in | `APPROVED` (a system catalog) | `PENDING_APPROVAL` |
 * | visible to | every caller | the owning tenant; hierarchy tenants for a reseller |
 * | manage / approve target | `AccessCheck.platformWide()` | `forTenant(tenantId)` |
 *
 * The list endpoint therefore returns a **merged** page: a tenant sees its own
 * rows (any status) plus the `APPROVED GLOBAL` catalog, and a reseller sees its
 * hierarchy's rows plus the same catalog. `scope` is on every row, so the UI
 * distinguishes them per row rather than presenting one undifferentiated list.
 *
 * **There is no `scope` query parameter.** `list` accepts only `page`, `size`,
 * `sort`, `status` and `search`. F3 therefore adds no scope *filter* — a filter
 * the server cannot honour over a paginated merged result would silently hide
 * rows on other pages. The scope is presented explicitly instead.
 *
 * ## List parameters, all verified
 *
 *  - `page` 0-based, `size` server-clamped to 1..100 (`MAX_PAGE_SIZE` L60).
 *  - `sort` whitelist `name, createdAt, updatedAt, status`
 *    (`SORTABLE_FIELDS` L57-58) — note there is **no** `scope` and no
 *    `templateText`. Default `createdAt` **DESC** (L59). `buildPageable`
 *    (L361-369) honours `sort[1]` only when it is `asc`, so the client always
 *    sends an explicit direction.
 *  - `status` is a `TtsTemplateStatus` name; an unknown value is a **400**
 *    "Unknown status filter: …" (L340-349).
 *  - `search` is a case-insensitive contains over **`name` AND `templateText`**
 *    (`TtsTemplateSpecifications.search`) — NOT `description`.
 *  - The response DOES carry real `PaginationMetadata` (`ResponseFactory.page`,
 *    L164).
 */
export const TTS_TEMPLATE_SORTABLE_FIELDS = [
  "name",
  "createdAt",
  "updatedAt",
  "status",
] as const;

export type TtsTemplateSortField =
  (typeof TTS_TEMPLATE_SORTABLE_FIELDS)[number];

export const TTS_TEMPLATE_STATUSES = [
  "PENDING_APPROVAL",
  "APPROVED",
  "REJECTED",
] as const;

export interface TtsTemplateListParams {
  page: number;
  size: number;
  sortField: TtsTemplateSortField;
  sortDirection: "asc" | "desc";
  status?: TtsTemplateStatus;
  search?: string;
}

/** Stable query-key factory. `all` is a prefix of every other key. */
export const ttsTemplatesKeys = {
  all: ["tts-templates"] as const,
  list: (params: TtsTemplateListParams) =>
    ["tts-templates", "list", params] as const,
  detail: (templateId: string) =>
    ["tts-templates", "detail", templateId] as const,
};

export interface TtsTemplatePage {
  items: TtsTemplateResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: TtsTemplateListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getTtsTemplates(
  params: TtsTemplateListParams,
): Promise<TtsTemplatePage> {
  const { data } = await api.get<ApiResponse<TtsTemplateResponse[]>>(
    "/tts-templates",
    {
      params: {
        page: params.page,
        size: params.size,
        sort: buildSort(params),
        status: params.status || undefined,
        search: params.search || undefined,
      },
    },
  );
  return {
    items: data.data ?? [],
    pagination:
      data.pagination ??
      // F3: this endpoint genuinely sends PaginationMetadata. The F1/F2-era
      // `totalElements: 0` fallback made a populated list render as "0
      // templates" behind working pagination controls.
      {
        page: params.page,
        size: params.size,
        totalElements: data.data?.length ?? 0,
        totalPages: data.data?.length ? 1 : 0,
        hasNext: false,
        hasPrevious: params.page > 0,
      },
  };
}

export function getTtsTemplate(
  templateId: string,
): Promise<TtsTemplateResponse> {
  return unwrap(
    api.get<ApiResponse<TtsTemplateResponse>>(`/tts-templates/${templateId}`),
  );
}

/**
 * POST /api/v1/tts-templates
 *
 * `scope` and `tenantId` are the two fields that carry the whole ownership
 * model, and they are NOT free-form. VERIFIED `TtsTemplateService.create`
 * L68-114:
 *
 *  - `scope: "GLOBAL"` requires PLATFORM scope and must be sent **without** a
 *    `tenantId` — sending one is a 400 "GLOBAL templates are platform-owned and
 *    must not reference a tenant." The created row is `APPROVED`.
 *  - `scope: "TENANT"` (or omitted — the service defaults it) with a
 *    TENANT-scoped caller uses the caller's own tenant; no `tenantId` is sent
 *    and the row is `PENDING_APPROVAL`.
 *  - `scope: "TENANT"` from a **platform** caller may carry a `tenantId` to seed
 *    a system template into a specific **ACTIVE** tenant. The target is verified
 *    to exist and be ACTIVE (a 400 otherwise), and the capability is then
 *    checked with `AccessCheck.platformWide()`. A reseller is refused: a
 *    RESELLER-scoped assignment does not cover `platformWide()`.
 *  - Any other combination is a 400 "A tenant must be specified for this
 *    operation."
 *
 * The client mirrors that in `lib/auth/content-gates.ts`
 * (`ttsCreateOptionsFor`) rather than offering a form that can only fail.
 */
export function createTtsTemplate(
  payload: CreateTtsTemplatePayload,
): Promise<TtsTemplateResponse> {
  return unwrap(
    api.post<ApiResponse<TtsTemplateResponse>>("/tts-templates", payload),
  );
}

/**
 * PUT /api/v1/tts-templates/{id} — `UpdateTtsTemplateRequest` has exactly four
 * fields: `name`, `description`, `templateText`, `variables`. No `scope`, no
 * `tenantId`, no `status`. **Scope is immutable after creation** and status
 * moves only through the approve/reject PATCH endpoints.
 *
 * VERIFIED re-approval rule (L181-186): editing an `APPROVED` template returns
 * it to `PENDING_APPROVAL` **only when its content actually changed** — the
 * `templateText`, or the set of declared variable names. A pure rename, or a
 * description-only edit, leaves the template `APPROVED`. F3 states that precisely
 * rather than warning about a reset that may not happen.
 */
export function updateTtsTemplate(
  templateId: string,
  payload: UpdateTtsTemplatePayload,
): Promise<TtsTemplateResponse> {
  return unwrap(
    api.put<ApiResponse<TtsTemplateResponse>>(
      `/tts-templates/${templateId}`,
      payload,
    ),
  );
}

/** DELETE /api/v1/tts-templates/{id} — soft delete, bare 204. */
export function deleteTtsTemplate(templateId: string): Promise<void> {
  return sendVoid(api.delete(`/tts-templates/${templateId}`));
}

/**
 * PATCH /api/v1/tts-templates/{id}/approve — `TTS_APPROVE`, but the scope
 * target is `manageCheckFor` (L223), so a **GLOBAL** row needs PLATFORM scope.
 *
 * 409 only on a no-op ("TTS template is already APPROVED."); any other target
 * state succeeds, including REJECTED → APPROVED.
 */
export function approveTtsTemplate(
  templateId: string,
): Promise<TtsTemplateResponse> {
  return unwrap(
    api.patch<ApiResponse<TtsTemplateResponse>>(
      `/tts-templates/${templateId}/approve`,
    ),
  );
}

/** PATCH /api/v1/tts-templates/{id}/reject — same rules as approve, target
 *  REJECTED. */
export function rejectTtsTemplate(
  templateId: string,
): Promise<TtsTemplateResponse> {
  return unwrap(
    api.patch<ApiResponse<TtsTemplateResponse>>(
      `/tts-templates/${templateId}/reject`,
    ),
  );
}
