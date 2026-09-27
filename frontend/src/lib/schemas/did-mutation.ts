import { z } from "zod";

import type {
  CreateDidPayload,
  UpdateDidPayload,
  DidCapability,
  DidStatus,
  AllocationState,
  NumberType,
} from "@/lib/api/contracts";

/** Backend constraints verified against DID DTOs. */
export const DID_E164_PATTERN = /^\+[1-9][0-9]{6,14}$/;
export const DID_COUNTRY_CODE_PATTERN = /^[0-9]{1,3}$/;
export const DID_PROVIDER_MAX = 50;
export const DID_CIRCLE_MAX = 100;
export const DID_AREA_CODE_MAX = 10;

/** PUT /api/v1/dids/{id} — all configuration fields are mutable (PUT semantics). */
export const editDidSchema = z.object({
  countryCode: z
    .string()
    .trim()
    .min(1, "Country code is required.")
    .regex(DID_COUNTRY_CODE_PATTERN, "Country code must be 1-3 digits without a leading plus."),
  areaCode: z.string().trim().max(DID_AREA_CODE_MAX).optional().nullable(),
  circle: z.string().trim().max(DID_CIRCLE_MAX).optional().nullable(),
  numberType: z.enum(["LANDLINE", "MOBILE", "PROMOTIONAL_140"] as [NumberType, ...NumberType[]]),
  provider: z
    .string()
    .trim()
    .min(1, "Provider is required.")
    .max(DID_PROVIDER_MAX, `Provider must be at most ${DID_PROVIDER_MAX} characters.`),
  capabilities: z.array(z.enum(["VOICE_OUTBOUND"] as [DidCapability, ...DidCapability[]])).optional(),
  status: z.enum(["ACTIVE", "INACTIVE"] as [DidStatus, ...DidStatus[]]),
  allocationState: z.enum(["AVAILABLE", "ASSIGNED"] as [AllocationState, ...AllocationState[]]),
});
export type EditDidValues = z.infer<typeof editDidSchema>;

/** Builds the PUT payload. Omitted optional fields are cleared (PUT semantics). */
export function toUpdateDidPayload(
  values: EditDidValues,
): UpdateDidPayload {
  return {
    countryCode: values.countryCode,
    areaCode: values.areaCode ?? null,
    circle: values.circle ?? null,
    numberType: values.numberType,
    provider: values.provider,
    capabilities: values.capabilities ?? [],
    status: values.status,
    allocationState: values.allocationState,
  };
}

/**
 * POST /api/v1/dids — DID registration.
 * tenantId/resellerId are honored only within the caller's server-derived scope.
 */
export const createDidSchema = z.object({
  e164Number: z
    .string()
    .trim()
    .min(1, "E.164 number is required.")
    .regex(DID_E164_PATTERN, "Must be a valid E.164 number, e.g. +918012345678."),
  countryCode: z
    .string()
    .trim()
    .min(1, "Country code is required.")
    .regex(DID_COUNTRY_CODE_PATTERN, "Country code must be 1-3 digits without a leading plus."),
  areaCode: z.string().trim().max(DID_AREA_CODE_MAX).optional().nullable(),
  circle: z.string().trim().max(DID_CIRCLE_MAX).optional().nullable(),
  numberType: z.enum(["LANDLINE", "MOBILE", "PROMOTIONAL_140"] as [NumberType, ...NumberType[]]),
  provider: z
    .string()
    .trim()
    .min(1, "Provider is required.")
    .max(DID_PROVIDER_MAX, `Provider must be at most ${DID_PROVIDER_MAX} characters.`),
  capabilities: z.array(z.enum(["VOICE_OUTBOUND"] as [DidCapability, ...DidCapability[]])).optional(),
  status: z.enum(["ACTIVE", "INACTIVE"] as [DidStatus, ...DidStatus[]]).optional(),
  allocationState: z.enum(["AVAILABLE", "ASSIGNED"] as [AllocationState, ...AllocationState[]]).optional(),
  tenantId: z.string().uuid().optional().nullable(),
  resellerId: z.string().uuid().optional().nullable(),
});
export type CreateDidValues = z.infer<typeof createDidSchema>;

export function toCreateDidPayload(
  values: CreateDidValues,
): CreateDidPayload {
  return {
    e164Number: values.e164Number,
    countryCode: values.countryCode,
    areaCode: values.areaCode ?? undefined,
    circle: values.circle ?? undefined,
    numberType: values.numberType,
    provider: values.provider,
    capabilities: values.capabilities,
    status: values.status,
    allocationState: values.allocationState,
    tenantId: values.tenantId ?? undefined,
    resellerId: values.resellerId ?? undefined,
  };
}