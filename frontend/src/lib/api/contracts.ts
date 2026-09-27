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

/* --------------------------------- Contact Groups ------------------------ */

/** com.shivang.obd.contact.dto.ContactGroupResponse */
export interface ContactGroupResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
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
  errors: { rowNumber: number; field: string; code: string; message: string }[];
}

/** Contact import row-level error. */
export interface ContactImportError {
  rowNumber: number;
  field: string;
  code: string;
  message: string;
}

/* ------------------------------------ Contacts --------------------------- */

/** GET /api/v1/contact-groups/{contactGroupId}/contacts, /api/v1/contact-groups/{contactGroupId}/contacts/{contactId} */
export interface ContactResponse {
  id: string;
  tenantId: string;
  contactGroupId: string;
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

/** POST /api/v1/contact-groups/{contactGroupId}/contacts/import — bulk import contacts. */
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

/* ---------------------------------- Audio Assets ------------------------- */

/** com.shivang.obd.audio.AudioAssetStatus */
export type AudioAssetStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

/** com.shivang.obd.audio.dto.AudioAssetResponse */
export interface AudioAssetResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  fileName: string;
  contentType: string;
  fileSize: number | null;
  durationSeconds: number | null;
  checksum: string | null;
  storageReference: string | null;
  status: AudioAssetStatus;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/audio-assets — verified CreateAudioAssetRequest */
export interface CreateAudioAssetPayload {
  name: string;
  description?: string | null;
  fileName: string;
  contentType: string;
  fileSize: number;
  durationSeconds?: number | null;
  checksum?: string | null;
  storageReference?: string | null;
}

/** PUT /api/v1/audio-assets/{id} — verified UpdateAudioAssetRequest */
export interface UpdateAudioAssetPayload {
  name: string;
  description?: string | null;
}

/* ----------------------------------- TTS Templates ----------------------- */

/** com.shivang.obd.tts.TtsTemplateStatus */
export type TtsTemplateStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";

/** com.shivang.obd.tts.TtsTemplateVariable */
export interface TtsTemplateVariable {
  name: string;
  type?: string | null;
  required?: boolean | null;
}

/** com.shivang.obd.tts.dto.TtsTemplateResponse */
export interface TtsTemplateResponse {
  id: string;
  tenantId: string;
  name: string;
  description: string | null;
  templateText: string;
  variables: TtsTemplateVariable[] | null;
  status: TtsTemplateStatus;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/tts-templates — verified CreateTtsTemplateRequest */
export interface CreateTtsTemplatePayload {
  name: string;
  description?: string | null;
  templateText: string;
  variables: TtsTemplateVariable[];
  tenantId?: string | null;
}

/** PUT /api/v1/tts-templates/{id} — verified UpdateTtsTemplateRequest */
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

/** com.shivang.obd.campaign.CampaignType */
export type CampaignType = "PLAYFILE" | "DTMF" | "CONNECT_BY_AGENT";

/** com.shivang.obd.campaign.CampaignRunMode */
export type CampaignRunMode = "ONE_TIME" | "RECURRING";

/** com.shivang.obd.campaign.ContentMode */
export type ContentMode = "AUDIO" | "TTS";

/** com.shivang.obd.campaign.RetryStrategy */
export type RetryStrategy = "FIXED";

/** com.shivang.obd.campaign.dto.ScheduleConfig */
export interface ScheduleConfig {
  startDate: string | null;
  endDate: string | null;
  startTime: string | null;
  endTime: string | null;
  timezone: string | null;
  allowedDaysOfWeek: string[] | null;
  holidayCalendarId: string | null;
}

/** com.shivang.obd.campaign.dto.RetryPolicyConfig */
export interface RetryPolicyConfig {
  maxAttempts: number;
  intervalSeconds: number | null;
  strategy: RetryStrategy;
}

/** GET /api/v1/campaigns, /api/v1/campaigns/{id} */
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
  typeConfig: Record<string, unknown> | null;
  integrationConfig: Record<string, unknown> | null;
  createdAt: string;
  updatedAt: string;
}

/** POST /api/v1/campaigns — creation payload (verified CreateCampaignRequest). */
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
  typeConfig?: Record<string, unknown> | null;
  integrationConfig?: Record<string, unknown> | null;
}

/** PUT /api/v1/campaigns/{id} — PUT semantics: omitted optional blocks are cleared.
 * campaignType is immutable and absent. */
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
  typeConfig?: Record<string, unknown> | null;
  integrationConfig?: Record<string, unknown> | null;
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
export type CampaignExecutionStatus = "REQUESTED" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";

/** GET /api/v1/campaigns/{campaignId}/executions */
export interface CampaignExecutionResponse {
  id: string;
  campaignId: string;
  tenantId: string;
  status: CampaignExecutionStatus;
  idempotencyKey: string | null;
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

/** PATCH /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts/{attemptId}/failed */
export interface MarkAttemptFailedPayload {
  failureCode?: string | null;
  failureReason?: string | null;
}
