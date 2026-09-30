/**
 * Backend request/response DTOs for the auth, signup, user, DID, Campaign, Contact Group, Audio, and TTS modules,
 * mirrored 1:1 from the Spring Boot records. Field names and optionality
 * are authoritative — do not rename or invent fields.
 *
 * @see com.shivang.obd.security.*, com.shivang.obd.{tenant,reseller}.dto.*,
 *      com.shivang.obd.account.*, com.shivang.obd.did.*, com.shivang.obd.campaign.*,
 *      com.shivang.obd.contact.*, com.shivang.obd.audio.*, com.shivang.obd.tts.*
 */
import type { LifecycleStatus } from "@/lib/api/types";

/* ---------------------------------- Auth --------------------------------- */

/** POST /api/v1/auth/login */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * POST /api/v1/auth/login | /api/v1/auth/refresh
 *
 * The refresh token is intentionally absent: the backend delivers it as an
 * HttpOnly Set-Cookie (obd_rt) that client script can never read.
 */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

/** GET /api/v1/auth/me */
export interface AuthenticatedUserResponse {
  id: string;
  email: string;
  status: LifecycleStatus;
  homeType: OrganizationalHomeType | null;
  organizationId: string | null;
  capabilities: string[];
}

/** POST /api/v1/auth/change-password */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/* --------------------------------- Signup -------------------------------- */

/** com.shivang.obd.tenant.dto.TenantAdminInput */
export interface TenantAdminInput {
  email: string;
  password: string;
  displayName?: string;
}

/** POST /api/v1/account/signup/tenant */
export interface TenantSignupPayload {
  name: string;
  slug: string;
  admin: TenantAdminInput;
}

/** com.shivang.obd.tenant.dto.TenantResponse */
export interface TenantResponse {
  id: string;
  name: string;
  slug: string;
  status: LifecycleStatus;
  resellerId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/account/signup/reseller */
export interface ResellerSignupPayload {
  name: string;
  slug: string;
  displayName?: string;
  supportEmail?: string;
  customDomain?: string;
  logoUrl?: string;
  primaryColor?: string;
  admin?: TenantAdminInput | null;
}

/** com.shivang.obd.reseller.dto.ResellerResponse */
export interface ResellerResponse {
  id: string;
  name: string;
  slug: string;
  displayName: string | null;
  status: LifecycleStatus;
  supportEmail: string | null;
  logoUrl: string | null;
  primaryColor: string | null;
  createdAt: string;
  updatedAt: string;
}

/* ---------------------------------- Users -------------------------------- */

/** com.shivang.obd.authz.home.OrganizationalHomeType */
export type OrganizationalHomeType = "TENANT" | "RESELLER";

/** GET /api/v1/users, /api/v1/users/{id} */
export interface UserResponse {
  id: string;
  email: string;
  displayName: string | null;
  status: LifecycleStatus;
  homeType: OrganizationalHomeType | null;
  organizationId: string | null;
  createdAt: string;
}

/** PUT /api/v1/users/{id} — partial update; omitted fields unchanged. */
export interface UpdateUserRequest {
  displayName?: string;
  status?: LifecycleStatus;
}

/* --------------------------------- Tenants ------------------------------- */

/** POST /api/v1/tenants — provisioning payload (verified CreateTenantRequest). */
export interface CreateTenantPayload {
  name: string;
  slug: string;
  /** Honored only for platform-scope callers; otherwise server-derived. */
  resellerId?: string | null;
  admin: TenantAdminInput;
}

/** PUT /api/v1/tenants/{id} — name is the only editable field. */
export interface UpdateTenantPayload {
  name?: string;
}

/* -------------------------------- Resellers ------------------------------ */

/**
 * POST /api/v1/resellers — SUPER_ADMIN-only provisioning. The embedded
 * administrator is OPTIONAL on the backend (a reseller may exist without
 * its RESELLER_ADMIN user).
 */
export interface CreateResellerPayload {
  name: string;
  slug: string;
  displayName?: string;
  supportEmail?: string;
  customDomain?: string;
  logoUrl?: string;
  primaryColor?: string;
  admin?: TenantAdminInput | null;
}

/**
 * PUT /api/v1/resellers/{id} — slug/customDomain are immutable and absent.
 * Backend applies any non-null field, so cleared values are sent as "".
 */
export interface UpdateResellerPayload {
  name?: string;
  displayName?: string;
  supportEmail?: string;
  logoUrl?: string;
  primaryColor?: string;
}

/* ------------------------------------ DIDs ------------------------------- */

/** com.shivang.obd.did.DidStatus */
export type DidStatus = "ACTIVE" | "INACTIVE";

/** com.shivang.obd.did.AllocationState */
export type AllocationState = "AVAILABLE" | "ASSIGNED";

/** com.shivang.obd.did.NumberType */
export type NumberType = "LANDLINE" | "MOBILE" | "PROMOTIONAL_140";

/** com.shivang.obd.did.AllocationSource — where an allocated DID came from. */
export type AllocationSource = "PLATFORM" | "RESELLER";

/** com.shivang.obd.did.DidCapability */
export type DidCapability = "VOICE_OUTBOUND";

/** GET /api/v1/dids, /api/v1/dids/{id} */
export interface DidResponse {
  id: string;
  tenantId: string;
  resellerId: string;
  e164Number: string;
  countryCode: string;
  areaCode: string | null;
  circle: string | null;
  numberType: NumberType;
  provider: string;
  status: DidStatus;
  capabilities: DidCapability[];
  allocationState: AllocationState;
  /** VERIFIED (F1): always sent by DidResponse; was absent from this type. */
  allocationSource: AllocationSource | null;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/dids — registration payload (verified CreateDidRequest). */
export interface CreateDidPayload {
  e164Number: string;
  countryCode: string;
  areaCode?: string | null;
  circle?: string | null;
  numberType: NumberType;
  provider: string;
  capabilities?: DidCapability[];
  /** Optional; defaults to ACTIVE. */
  status?: DidStatus;
  /** Optional; defaults to AVAILABLE. ASSIGNED requires a target tenant in scope. */
  allocationState?: AllocationState;
  tenantId?: string | null;
  resellerId?: string | null;
}

/** PUT /api/v1/dids/{id} — PUT semantics: omitted optional fields are cleared.
 * E.164 number and ownership are immutable; ASSIGNED requires an assigned tenant. */
export interface UpdateDidPayload {
  countryCode: string;
  areaCode?: string | null;
  circle?: string | null;
  numberType: NumberType;
  provider: string;
  capabilities?: DidCapability[];
  status: DidStatus;
  allocationState: AllocationState;
}

/** POST /api/v1/dids/{id}/assign — verified AssignDidRequest.
 * A single required UUID that the backend resolves as a reseller first and a
 * tenant second. A tenant caller is refused with 400, not 403. */
export interface AssignDidPayload {
  targetId: string;
}

/** POST /api/v1/dids/{id}/assign and /revoke — verified AssignDidResponse. */
export interface AssignDidResponse {
  didId: string;
  allocationState: AllocationState;
  allocationSource: AllocationSource | null;
  tenantId: string | null;
  resellerId: string | null;
}

/* --------------------------------- Contact Groups ------------------------ */

/** com.shivang.obd.contact.dto.ContactGroupResponse */
export interface ContactGroupResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  /** VERIFIED: added by F1. The backend DTO has always sent this; the frontend
   * type omitted it. `long` on the wire, so a JSON number here. */
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/contact-groups — creation payload (verified CreateContactGroupRequest). */
export interface CreateContactGroupPayload {
  name: string;
  description?: string | null;
}

/** PUT /api/v1/contact-groups/{id} — PUT semantics: replaces the mutable group representation. */
export interface UpdateContactGroupPayload {
  name: string;
  description?: string | null;
}

/** POST /api/v1/contact-groups/{id}/contacts/import — bulk import contacts. */
export interface ContactImportResponse {
  totalRows: number;
  created: number;
  skipped: number;
  duplicateCount: number;
  errorCount: number;
  errors: ContactImportError[];
}

/** Contact import row-level error. */
export interface ContactImportError {
  rowNumber: number;
  field: string;
  code: string;
  message: string;
}

/** com.shivang.obd.contact.dto.ContactGroupMemberResponse — the roster shape
 * returned by GET/POST /api/v1/contact-groups/{id}/members. Distinct from
 * ContactResponse: the member is the JOIN ROW, not the contact.
 *
 * VERIFIED (F2): the membership is a physical, immutable relationship row with
 * no update semantics and no soft delete. It carries its OWN `memberId`, and
 * the group is `groupId` — deliberately NOT `contactGroupId`. The live contact
 * payload is embedded for roster convenience and may be null (a soft-deleted
 * contact is excluded from the roster, but the schema permits null). */
export interface ContactGroupMemberResponse {
  memberId: string;
  groupId: string;
  contactId: string;
  tenantId: string;
  contact: ContactResponse | null;
  createdAt: string;
  createdBy: string | null;
}

/** com.shivang.obd.contact.dto.AddMemberRequest */
export interface AddMemberPayload {
  contactId: string;
}

/** com.shivang.obd.contact.dto.BatchMemberRequest — max 500 ids. */
export interface BatchMemberPayload {
  contactIds: string[];
}

/** com.shivang.obd.contact.dto.BatchMemberResponse */
export interface BatchMemberResponse {
  results: BatchMemberResult[];
  processed: number;
  failed: number;
}

/** com.shivang.obd.contact.dto.BatchMemberResult */
export interface BatchMemberResult {
  contactId: string;
  status: MemberBatchStatus;
  errorDetail: string | null;
}

/** com.shivang.obd.contact.dto.MemberBatchStatus */
export type MemberBatchStatus =
  | "CREATED"
  | "EXISTS"
  | "NOT_FOUND"
  | "NOT_FOUND_CONTACT"
  | "ERROR";

/* ------------------------------------ Contacts --------------------------- */

/** GET /api/v1/contact-groups/{contactGroupId}/contacts, /api/v1/contact-groups/{contactGroupId}/contacts/{contactId} */
export interface ContactResponse {
  id: string;
  tenantId: string;
  // VERIFIED (F1): `contactGroupId` was REMOVED. The backend
  // ContactResponse record (contact/dto/ContactResponse.java) has exactly nine
  // components and none of them is the group id — the group is already the
  // parent of the REST route. The field was always `undefined` at runtime.
  firstName: string | null;
  lastName: string | null;
  phoneNumber: string;
  email: string | null;
  attributes: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/contact-groups/{contactGroupId}/contacts — creation payload (verified CreateContactRequest). */
export interface CreateContactPayload {
  firstName?: string | null;
  lastName?: string | null;
  phoneNumber: string;
  email?: string | null;
  attributes?: Record<string, unknown> | null;
}

/** PUT /api/v1/contact-groups/{contactGroupId}/contacts/{contactId} — PUT semantics: replaces the mutable contact representation. */
export interface UpdateContactPayload {
  firstName?: string | null;
  lastName?: string | null;
  phoneNumber: string;
  email?: string | null;
  attributes?: Record<string, unknown> | null;
}

/* ---------------------------------- Audio Assets ------------------------- */

/** com.shivang.obd.audio.AudioAssetStatus
 *
 * F3: the same three-value lifecycle as `TtsTemplateStatus`, and both services
 * implement an identical transition rule. The shared model lives in
 * `lib/domain/approval.ts`; these two aliases stay so each module reads in its
 * own domain's vocabulary. */
export type AudioAssetStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

/** com.shivang.obd.audio.dto.AudioAssetResponse */
export interface AudioAssetResponse {
  id: string;
  /** Owning tenant. Always present — audio assets have no platform-owned form. */
  tenantId: string;
  name: string;
  description: string | null;
  fileName: string;
  contentType: string;
  /**
   * F3: was typed `number | null`. VERIFIED `nullable = false` on
   * `AudioAssetEntity.fileSize` and `file_size BIGINT NOT NULL CHECK (> 0)` in
   * V20, and both creation paths set it. The nullable type rendered
   * "Size: null bytes" on the detail page for a value that cannot be null.
   */
  fileSize: number;
  /**
   * Nullable. `AudioUploadValidator` only derives a duration for WAV and never
   * fails an upload over it — an MP3, or a WAV header it cannot parse, leaves
   * this null. `duration_seconds` is nullable in V20.
   */
  durationSeconds: number | null;
  /** Nullable. Backend-computed SHA-256 of the uploaded bytes. */
  checksum: string | null;
  /**
   * Nullable, and **infrastructure**: a logical
   * `audio/{tenant}/{asset}/{file}` locator that `MediaUriResolver` turns into a
   * FreeSWITCH filesystem path.
   *
   * F3: typed faithfully but never rendered. It is not a URL, it means nothing
   * to a user, and it reveals the deployment's storage layout. There is also no
   * download or playback endpoint, so this string is the only place that layout
   * is visible — and it does not need to be.
   */
  storageReference: string | null;
  status: AudioAssetStatus;
  createdAt: string;
  /**
   * F3: was typed `string`. VERIFIED `updated_at TIMESTAMPTZ` — nullable — and
   * `AuditableEntity.updatedAt` is a `@LastModifiedDate` that stays null until
   * the row is first modified. A freshly uploaded asset has never been updated.
   */
  updatedAt: string | null;
}

/**
 * F3 REMOVED: `CreateAudioAssetPayload`.
 *
 * `POST /api/v1/audio-assets` still exists on the backend — a metadata-only
 * registration endpoint for externally provisioned assets, whose DTO makes the
 * caller author `fileName`, `contentType`, `fileSize` and optionally `checksum`
 * and `storageReference` by hand. The pre-F3 create dialog exposed exactly that,
 * and it was wrong three times over: it asked a user to type a 64-character
 * SHA-256 they cannot compute, it accepted a `storageReference` that nothing
 * validates on the way in, and it let a user register an asset that has no
 * stored bytes at all — which `MediaUriResolver` will later refuse to resolve.
 *
 * F3 removes the type, the API function and the form, and uses the upload path
 * instead. The endpoint is NOT removed from the backend; it simply has no UI. If
 * a real provisioning workflow needs it later it should get its own deliberate
 * screen, rather than being revived as a general-purpose form.
 */

/** PUT /api/v1/audio-assets/{id} — verified UpdateAudioAssetRequest
 *
 * NOTE what is absent: `fileName`, `contentType`, `fileSize`, `durationSeconds`,
 * `checksum` and `storageReference` are NOT updatable. The record has exactly two
 * components. Changing a file's content is a re-upload, and the frontend must
 * not offer fields the DTO does not carry. */
export interface UpdateAudioAssetPayload {
  name: string;
  description?: string | null;
}

/** POST /api/v1/audio-assets/upload — the ONLY audio creation path the UI uses.
 *
 * VERIFIED against `AudioAssetController.upload` (L86-100): a multipart request
 * with `@RequestParam` parts `name` (required), `description` (optional) and
 * `file` (required MultipartFile).
 *
 * Every technical field is DERIVED SERVER-SIDE (`AudioAssetService.upload`
 * L201-212): `fileName` from the multipart part, `contentType` from magic bytes,
 * `fileSize` and `checksum` from the bytes, `durationSeconds` best-effort for
 * WAV, `storageReference` from the storage backend. A client must never author
 * them, and F3 deletes the form that asked for them.
 *
 * ### Error behaviour — CORRECTED IN F3
 *
 * F1's comment here claimed `AudioStorageException` surfaces as **503**. It does
 * not. `AudioStorageException extends RuntimeException` and there is **no**
 * `@ExceptionHandler` for it anywhere in `GlobalExceptionHandler`, so it falls
 * through to the `@ExceptionHandler(Exception.class)` catch-all and the response
 * is a **500** with a generic message. The controller's
 * `409 "Audio storage is disabled"` annotation is stale.
 *
 * This matters in practice: `audio.storage.enabled` defaults to **false**, so an
 * unconfigured deployment 500s on every upload. The UI must therefore report it
 * as a server failure, not as a conflict it can explain away. See the F3
 * document §10.
 *
 * Rejections that DO carry a useful message are `InvalidAudioUploadException`,
 * which extends `BusinessException(VALIDATION_ERROR)` → **400**: unsupported
 * content type, empty file, oversized file, unrecognised stream, or a declared
 * type that disagrees with the file's magic bytes. */
export interface AudioAssetUploadForm {
  name: string;
  description?: string | null;
  file: File;
}

/* ----------------------------------- TTS Templates ----------------------- */

/** com.shivang.obd.tts.TtsTemplateStatus */
export type TtsTemplateStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

/** com.shivang.obd.tts.TtsTemplateScope
 *
 * VERIFIED (F1) — the two scopes are DIFFERENT RESOURCES, not one list with a
 * filter. From TtsTemplateService:
 *  - `GLOBAL`  → `tenantId` is `null` (platform-owned). Platform callers only; a
 *                `tenantId` in the body is rejected 400. Created APPROVED, and
 *                manage/update resolves to `AccessCheck.platformWide()`.
 *  - `TENANT`  → owned by the caller's tenant. Created PENDING_APPROVAL, and
 *                manage/update resolves to `forTenant(entity.getTenantId())`.
 * A tenant's list shows its own rows (any status) plus APPROVED GLOBAL rows
 * only. Editing an APPROVED row of either scope reverts it to PENDING_APPROVAL. */
export type TtsTemplateScope = "GLOBAL" | "TENANT";

/** com.shivang.obd.tts.TtsTemplateValidation.ALLOWED_TYPES */
export type TtsTemplateVariableType = "STRING" | "NUMBER" | "BOOLEAN" | "DATE";

/** com.shivang.obd.tts.TtsTemplateVariable */
export interface TtsTemplateVariable {
  name: string;
  type?: TtsTemplateVariableType | null;
  required?: boolean | null;
}

/** com.shivang.obd.tts.dto.TtsTemplateResponse */
export interface TtsTemplateResponse {
  id: string;
  /** `null` for GLOBAL templates; the owning tenant for TENANT templates. */
  tenantId: string | null;
  name: string;
  description: string | null;
  templateText: string;
  variables: TtsTemplateVariable[] | null;
  status: TtsTemplateStatus;
  /** VERIFIED (F1): always sent by the backend; was absent from this type.
   * `TtsTemplateMapper.effectiveScope` derives it for legacy rows whose column
   * is null: null tenant ⇒ GLOBAL, otherwise TENANT. Never null on the wire. */
  scope: TtsTemplateScope;
  createdAt: string;
  /** F3: was typed `string`. VERIFIED `updated_at TIMESTAMPTZ` — nullable — and
   *  `AuditableEntity.updatedAt` is a `@LastModifiedDate` that stays null until
   *  the row is first modified. */
  updatedAt: string | null;
}

/** POST /api/v1/tts-templates — verified CreateTtsTemplateRequest
 *
 * VERIFIED (F1) — `scope` was missing from the frontend payload, which made
 * platform-owned GLOBAL templates unreachable. Defaults server-side to TENANT
 * (TtsTemplateService.create L73-74). Sending `scope: "GLOBAL"` together with a
 * `tenantId` is a 400: "GLOBAL templates are platform-owned and must not
 * reference a tenant." `tenantId` is honoured only for platform callers
 * seeding a template into a specific ACTIVE tenant. */
export interface CreateTtsTemplatePayload {
  name: string;
  description?: string | null;
  templateText: string;
  variables: TtsTemplateVariable[];
  tenantId?: string | null;
  scope?: TtsTemplateScope;
}

/** PUT /api/v1/tts-templates/{id} — verified UpdateTtsTemplateRequest
 *
 * Scope, tenantId and status are deliberately ABSENT: the backend's
 * UpdateTtsTemplateRequest has only these four fields, so ownership is
 * immutable. Status changes go through the approve/reject PATCH endpoints. */
export interface UpdateTtsTemplatePayload {
  name: string;
  description?: string | null;
  templateText: string;
  variables: TtsTemplateVariable[];
}

/* ---------------------------------- Campaigns ---------------------------- */

/** com.shivang.obd.campaign.CampaignStatus */
export type CampaignStatus =
  | "DRAFT"
  | "SCHEDULED"
  | "RUNNING"
  | "PAUSED"
  | "COMPLETED"
  | "FAILED"
  | "ARCHIVED";

/** com.shivang.obd.campaign.CampaignType
 *
 * VERIFIED (F1): `MISSED_CALL` was missing. The backend enum has four
 * constants (CampaignType.java:11-33) and MISSED_CALL is fully implemented
 * (migration V54, MissedCallCampaignConfig, MissedCallExecutionService, its own
 * readiness rules). Omitting it made `z.enum` reject a valid type, hid the type
 * from the filter, and broke the badge.
 *
 * The backend derives exactly two rules from `playsMedia()` rather than from
 * per-type membership lists (CampaignType.java:68-75):
 *   PLAYFILE, DTMF                  -> playsMedia() === true  -> content required, TTS rejected
 *   CONNECT_BY_AGENT, MISSED_CALL   -> playsMedia() === false -> no content
 * `playsMedia` is exhaustive by construction (no default arm). */
export type CampaignType = "PLAYFILE" | "DTMF" | "CONNECT_BY_AGENT" | "MISSED_CALL";

/** com.shivang.obd.campaign.CampaignRunMode */
export type CampaignRunMode = "ONE_TIME" | "RECURRING";

/** com.shivang.obd.campaign.ContentMode */
export type ContentMode = "AUDIO" | "TTS";

/** com.shivang.obd.campaign.RetryStrategy */
export type RetryStrategy = "FIXED";

/** java.time.DayOfWeek — ScheduleConfig.allowedDaysOfWeek is a Set<DayOfWeek>,
 * not a free string list. F1 narrowed this from `string[]`; a non-`MONDAY`…
 * `SUNDAY` value fails backend deserialization. */
export type DayOfWeek =
  | "MONDAY"
  | "TUESDAY"
  | "WEDNESDAY"
  | "THURSDAY"
  | "FRIDAY"
  | "SATURDAY"
  | "SUNDAY";

/** com.shivang.obd.campaign.RetryRuleCategory
 *
 * SWITCHED_OFF and NOT_REACHABLE are accepted by the backend for a future
 * reliable provider mapping, but no provider outcome feeds them today: such
 * causes arrive as HANGUP and are governed by the HANGUP rule
 * (RetryRuleConfig Javadoc). The UI should not present them as selectable. */
export type RetryRuleCategory =
  | "NO_ANSWER"
  | "BUSY"
  | "HANGUP"
  | "FAILED"
  | "SWITCHED_OFF"
  | "NOT_REACHABLE";

/** Retry categories a campaign can meaningfully configure today. */
export const CONFIGURABLE_RETRY_CATEGORIES = [
  "NO_ANSWER",
  "BUSY",
  "HANGUP",
  "FAILED",
] as const satisfies readonly RetryRuleCategory[];

/** com.shivang.obd.campaign.dto.RetryRuleConfig
 *
 * `maxRetries` counts retries BEYOND the initial attempt, so maxRetries = 2
 * yields at most 3 attempts. `retryDelay` is the MM:SS string format
 * ("05:00"); minutes 00-99, seconds 00-59 (RetryDelay.MM_SS). It is required
 * only when the rule is enabled and permits at least one retry. A rule can
 * only RESTRICT retries — it can never make a permanent failure retryable. */
export interface RetryRuleConfig {
  category: RetryRuleCategory;
  enabled?: boolean | null;
  maxRetries: number;
  retryDelay?: string | null;
}

/** com.shivang.obd.campaign.dto.ScheduleConfig */
export interface ScheduleConfig {
  startDate: string | null;
  startTime: string | null;
  endTime: string | null;
  timezone: string | null;
  allowedDaysOfWeek: DayOfWeek[] | null;
  holidayCalendarId: string | null;
}

/** com.shivang.obd.campaign.dto.RetryPolicyConfig
 *
 * VERIFIED (F1): `rules` was missing. The flat fields are the default
 * allowance applied to every category without its own rule; `rules` overrides
 * that per category, at most one rule per category.
 *
 * `intervalSeconds` is bounded 1..5999 by the backend (@Min(1) @Max(5999)) — the
 * F0 frontend schema allowed 604800, which the server rejects. Prefer `rules`
 * for new configuration; this field is the original flat model. */
export interface RetryPolicyConfig {
  maxAttempts: number;
  intervalSeconds: number | null;
  strategy: RetryStrategy;
  rules?: RetryRuleConfig[] | null;
}

/* --------------------- Campaign type configuration ----------------------- */

/** com.shivang.obd.campaign.config.AgentSelectionStrategy
 * LEAST_ACTIVE_RESERVATIONS is the only implemented strategy; the enum has a
 * single constant and `isImplemented()` returns true. No round-robin, weighted,
 * skills or AI routing exists. */
export type AgentSelectionStrategy = "LEAST_ACTIVE_RESERVATIONS";

/** typeConfig for a CONNECT_BY_AGENT campaign.
 * VERIFIED against ConnectByAgentCampaignConfig. `ringDurationSeconds` is
 * bounded 10..240 (AgentRingWindow.MIN/MAX_RING_SECONDS). Unknown fields are
 * rejected 400, so this shape must be exact. */
export interface ConnectByAgentTypeConfig {
  connectByAgent: {
    queueId: string;
    selectionStrategy: AgentSelectionStrategy;
    ringDurationSeconds: number;
  };
}

/** typeConfig for a MISSED_CALL campaign.
 * VERIFIED against MissedCallCampaignConfig: `ringDurationSeconds` is required
 * and bounded 10..60 (MissedCallRingWindow). It is the whole time budget —
 * max ringing before answer, rebased onto answer afterwards. */
export interface MissedCallTypeConfig {
  missedCall: {
    ringDurationSeconds: number;
  };
}

/** typeConfig for a DTMF campaign.
 * VERIFIED against DtmfConfig.fromTypeConfig: `expected` is a required digit
 * sequence of at most 16 collectable digits; `maxDigits` defaults to
 * `expected.length()` and must be between that and 16; `terminator` is a single
 * digit key; `timeoutSecs` is 1..120 (default 10). `action` is one of the
 * DtmfActions constants below. */
export interface DtmfTypeConfig {
  dtmf: {
    expected: string;
    maxDigits: number;
    terminator?: string | null;
    timeoutSecs: number;
    action: DtmfAction;
  };
}

/** com.shivang.obd.voice.dtmf.DtmfActions */
export type DtmfAction = "TERMINATE" | "CONNECT_BY_AGENT";

/** typeConfig for a DTMF campaign that references an IVR tree instead of a
 * flat DTMF sequence.
 *
 * DELIBERATELY LOOSE, and currently UNREACHABLE: the backend selects this
 * variant when `type_config.ivr` is present (CampaignTypeConfig.fromTypeConfig
 * L18-20). The inner object is a FROZEN EXECUTION SNAPSHOT (IvrSnapshotCodec)
 * plus a `treeId`, so its full field set is not modelled here. It can only be
 * produced by POST /api/v1/campaigns/{id}/ivr-tree, which is currently blocked
 * by the missing IVR_VIEW/IVR_MANAGE capability seed (see F1 doc §8). */
export interface IvrTypeConfig {
  ivr: {
    treeId: string;
    [snapshotField: string]: unknown;
  };
}

/** typeConfig for a PLAYFILE campaign.
 * VERIFIED (F1): PlayfileCampaignConfig.toJson() emits an EMPTY object and
 * fromTypeConfig REJECTS any non-empty object ("PLAYFILE campaigns do not
 * support type-specific configuration; content is selected via contentMode and
 * audioAssetId"). A client must send null/absent or `{}`, never a populated
 * object. */
export type PlayfileTypeConfig = Record<string, never>;

/** The polymorphic `typeConfig` payload, keyed by the campaign type it belongs
 * to. Replaces the previous `Record<string, unknown>`.
 *
 * VERIFIED against the sealed interface `CampaignTypeConfig`, which permits
 * exactly PlayfileCampaignConfig, DtmfCampaignConfig, IvrCampaignConfig,
 * ConnectByAgentCampaignConfig and MissedCallCampaignConfig. Every one of those
 * parsers calls rejectUnknownFields, so a wrong key or an extra field is a 400
 * — which is exactly why a loose record was not safe here. */
export type CampaignTypeConfig =
  | PlayfileTypeConfig
  | DtmfTypeConfig
  | IvrTypeConfig
  | ConnectByAgentTypeConfig
  | MissedCallTypeConfig;

/* -------------------- Campaign integration configuration ------------------ */

/** com.shivang.obd.campaign.config.WebhookEvent — the PUBLIC event vocabulary.
 * Selecting an event records configured intent only: the backend implements no
 * webhook transport, signing, retry, queue or worker, and generates no report. */
export type WebhookEvent =
  | "campaign.attempt.completed"
  | "campaign.attempt.failed"
  | "campaign.attempt.cancelled";

/** com.shivang.obd.campaign.config.WebhookConfig
 * `enabled` is the only switch — an endpoint never enables a webhook by itself.
 * When enabled, `endpoint` must be an absolute http(s) URL and `events` must
 * select at least one event, each at most once. This object stores NO secrets:
 * there is no credential field and endpoints with embedded credentials are
 * refused. Unknown fields are rejected. */
export interface WebhookConfig {
  enabled: boolean;
  endpoint?: string | null;
  events: WebhookEvent[];
}

/** com.shivang.obd.campaign.config.ReportPrivacy
 * FULL is the default and matches current platform behaviour; MASKED shows only
 * the last four digits. It describes what a future reporting subsystem should
 * show and does NOT change the existing attempt-listing APIs, which continue to
 * return contact data unchanged. */
export type ReportPrivacyPolicy = "FULL" | "MASKED";

/** com.shivang.obd.campaign.config.ReportPrivacyConfig */
export interface ReportPrivacyConfig {
  policy: ReportPrivacyPolicy;
}

/** com.shivang.obd.campaign.config.CampaignIntegrationConfig
 * VERIFIED (F1): this is a TYPED record in the backend, not free-form JSON.
 * `validateAndCanonicalize` returns null for a null or empty node, so null
 * ("never configured") is preserved verbatim and is deliberately distinct from
 * an explicit defaults block. Both keys are optional. */
export interface CampaignIntegrationConfig {
  webhook?: WebhookConfig | null;
  reportPrivacy?: ReportPrivacyConfig | null;
}

/** GET /api/v1/campaigns, /api/v1/campaigns/{id}
 *
 * VERIFIED (F1): four fields were missing. The backend CampaignResponse record
 * has 24 components (CampaignResponse.java:13-102); the frontend type had 20.
 *
 * Snapshot semantics a UI must respect: `typeConfig` and `integrationConfig`
 * are stored CANONICALLY by the backend (config.toJson()), not echoed back as
 * the client sent them. And every execution-affecting field here is frozen into
 * an immutable per-execution configuration snapshot at execution-creation time,
 * so editing a campaign does NOT change a running execution. */
export interface CampaignResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  campaignType: CampaignType;
  runMode: CampaignRunMode;
  status: CampaignStatus;
  version: number;
  clonedFromCampaignId: string | null;
  contactGroupId: string | null;
  didId: string | null;
  contentMode: ContentMode | null;
  audioAssetId: string | null;
  ttsTemplateId: string | null;
  schedule: ScheduleConfig | null;
  retryPolicy: RetryPolicyConfig | null;
  typeConfig: CampaignTypeConfig | null;
  integrationConfig: CampaignIntegrationConfig | null;
  createdAt: string;
  updatedAt: string;
  /** When true, only numbers on the tenant whitelist may be dialed. */
  /**
   * F4 VERIFIED: CREATE-ONLY, and therefore immutable for the campaign's
   * lifetime. `CampaignEntity.callOnWhitelistNumbers` defaults to `false` and is
   * set from `CreateCampaignRequest` by `CampaignMapper.toEntity`; neither
   * `UpdateCampaignRequest` nor `CampaignMapper.updateEntity` can change it.
   * The edit form therefore displays this read-only rather than offering a
   * control that cannot be saved.
   */
  callOnWhitelistNumbers: boolean | null;
  /** Campaign-specific Voice Blast daily DIAL limit, 1..3. Null uses the
   * platform maximum. Distinct from maxDailyAttempts below. */
  dailyDialLimit: number | null;
  /** Campaign-specific daily ATTEMPT ceiling per contact, 1..10. Null uses the
   * platform default. Not the DNID-scoped provider-accepted dial limit. */
  maxDailyAttempts: number | null;
  /** Maximum lifetime of an ESTABLISHED call in seconds, 1..3600. Null uses the
   * platform default of 300s. Not a ring timeout, not a playback length. */
  maxCallDurationSeconds: number | null;
}

/** POST /api/v1/campaigns — creation payload (verified CreateCampaignRequest).
 * `runMode` defaults to ONE_TIME when omitted. Lineage fields (version,
 * clonedFromCampaignId) are server-generated and deliberately absent. */
export interface CreateCampaignPayload {
  name: string;
  description?: string | null;
  campaignType: CampaignType;
  runMode?: CampaignRunMode;
  contactGroupId?: string | null;
  didId?: string | null;
  contentMode?: ContentMode;
  audioAssetId?: string | null;
  ttsTemplateId?: string | null;
  schedule?: ScheduleConfig | null;
  retryPolicy?: RetryPolicyConfig | null;
  typeConfig?: CampaignTypeConfig | null;
  integrationConfig?: CampaignIntegrationConfig | null;
  callOnWhitelistNumbers?: boolean | null;
  dailyDialLimit?: number | null;
  maxDailyAttempts?: number | null;
  maxCallDurationSeconds?: number | null;
}

/** PUT /api/v1/campaigns/{id} — PUT semantics: omitted optional blocks are cleared.
 * `campaignType` is immutable and absent.
 *
 * F4 CORRECTION: `callOnWhitelistNumbers` was present here and on
 * `toUpdateCampaignPayload`, which sent it on every save. VERIFIED
 * `UpdateCampaignRequest` (L5-25) has **15 components** and `callOnWhitelistNumbers`
 * is not one of them — it exists only on `CreateCampaignRequest` (L117) and on
 * `CampaignEntity`. `CampaignMapper.updateEntity` never reads it either.
 *
 * So the field was a phantom: the UI presented a control, sent it, and the
 * server discarded it, because a whitelist flag silently reverting to its
 * stored value is exactly the kind of thing nobody notices until an outage.
 * Spring Boot leaves `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so there was not
 * even a 400 to notice. It is now create-only and the edit form shows it
 * read-only. */
export interface UpdateCampaignPayload {
  name: string;
  description?: string | null;
  runMode?: CampaignRunMode;
  contactGroupId?: string | null;
  didId?: string | null;
  contentMode?: ContentMode;
  audioAssetId?: string | null;
  ttsTemplateId?: string | null;
  schedule?: ScheduleConfig | null;
  retryPolicy?: RetryPolicyConfig | null;
  typeConfig?: CampaignTypeConfig | null;
  integrationConfig?: CampaignIntegrationConfig | null;
  dailyDialLimit?: number | null;
  maxDailyAttempts?: number | null;
  maxCallDurationSeconds?: number | null;
}

/** PATCH /api/v1/campaigns/{id}/status — lifecycle transition. */
export interface UpdateCampaignStatusPayload {
  status: CampaignStatus;
}

/** POST /api/v1/campaigns/{id}/executions — execute campaign. */
export interface ExecuteCampaignPayload {
  idempotencyKey?: string | null;
}

/** GET /api/v1/campaigns/{id}/readiness */
export interface CampaignReadinessResponse {
  campaignId: string;
  ready: boolean;
  reasons: CampaignReadinessReason[];
}
export interface CampaignReadinessReason { code: string; message: string; }

/** com.shivang.obd.campaign.CampaignExecutionStatus */
/* -------------------------------------------------------------------------- */
/* Queues — F4 ADDED.                                                          */
/* -------------------------------------------------------------------------- */

/**
 * com.shivang.obd.voice.queue.QueueStatus
 *
 * VERIFIED three constants. `DISABLED` is terminal: the controller describes
 * the transition as `ACTIVE ↔ INACTIVE` and `→ DISABLED`, and "DISABLED is
 * terminal".
 */
export type QueueStatus = "ACTIVE" | "INACTIVE" | "DISABLED";

/**
 * com.shivang.obd.voice.queue.dto.QueueResponse
 *
 * F4 ADDED this type. It did not exist before, and its absence is why F4 could
 * not build a real queue picker for `CONNECT_BY_AGENT` even though the Queue
 * API has been fully REST-exposed since `V37__create_queue_foundation.sql`.
 *
 * VERIFIED field-by-field from the record's ten components. `maxWaitingCalls`,
 * `maxWaitSeconds` and `overflowEnabled` are PERSISTED CONFIGURATION ONLY —
 * the controller's own tag says "capacity/timeout/overflow — persisted, not
 * executed" and "VB-4B only — no ACD selection, no dispatch, no inbound/outbound
 * calling". So this UI must present them as stored settings, never as live
 * behaviour.
 */
export interface QueueResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  status: QueueStatus;
  maxWaitingCalls: number;
  maxWaitSeconds: number;
  overflowEnabled: boolean;
  /** Overflow target queue; VERIFIED re-validated on write to be same-tenant and never self. */
  overflowQueueId: string | null;
  createdAt: string;
  updatedAt: string | null;
}

/** com.shivang.obd.campaign.CampaignExecutionStatus */
export type CampaignExecutionStatus = "REQUESTED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
/** GET /api/v1/campaigns/{campaignId}/executions/{executionId} */
export interface CampaignExecutionResponse {
  id: string;
  campaignId: string;
  tenantId: string;
  status: CampaignExecutionStatus;
  idempotencyKey: string | null;
  /** VERIFIED (F1): identity of the immutable configuration snapshot this
   * execution runs against. It is the user-visible proof that later campaign
   * edits do NOT affect this execution. */
  configurationSnapshotId: string;
  requestedAt: string;
  requestedBy: string;
  startedAt: string | null;
  completedAt: string | null;
  failureReason: string | null;
}

/** com.shivang.obd.campaign.CallAttemptStatus */
export type CallAttemptStatus = "QUEUED" | "IN_PROGRESS" | "COMPLETED" | "FAILED" | "CANCELLED";

/** GET /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts */
export interface CallAttemptResponse {
  id: string;
  executionId: string;
  campaignId: string;
  tenantId: string;
  contactId: string;
  didId: string;
  attemptNumber: number;
  status: CallAttemptStatus;
  scheduledAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failureCode: string | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts */
export interface CreateCallAttemptPayload {
  contactId: string;
  didId: string;
  attemptNumber: number;
  scheduledAt?: string | null;
}

/** PATCH .../attempts/{attemptId}/failed — request values.
 *
 * VERIFIED (F1) — THIS IS A QUERY STRING, NOT A BODY. CampaignController
 * declares `@RequestParam(required = false) String failureCode` and
 * `failureReason` (L377-378); the method takes no `@RequestBody` at all. The
 * previous frontend sent a JSON body, so both diagnostics were silently
 * discarded and every failed attempt looked diagnostic-free.
 *
 * The backend also canonises any unrecognised code to HANGUP_UNKNOWN
 * (CallFailureCode.canonicalize), so an unknown value is not rejected. */
export interface MarkAttemptFailedParams {
  failureCode?: string | null;
  failureReason?: string | null;
}
