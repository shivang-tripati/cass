import { z } from "zod";

import type {
  CreateTenantPayload,
  UpdateTenantPayload,
} from "@/lib/api/contracts";
import { PASSWORD_MAX, PASSWORD_MIN } from "@/lib/schemas/auth";

/** Backend constraints verified against tenant DTOs. */
export const TENANT_NAME_MAX = 150;
export const TENANT_SLUG_MAX = 100;
export const TENANT_SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

const name = z
  .string()
  .trim()
  .min(1, "Name is required.")
  .max(TENANT_NAME_MAX, `Name must be at most ${TENANT_NAME_MAX} characters.`);

const slug = z
  .string()
  .trim()
  .min(1, "Slug is required.")
  .max(TENANT_SLUG_MAX, `Slug must be at most ${TENANT_SLUG_MAX} characters.`)
  .regex(
    TENANT_SLUG_PATTERN,
    "Use lowercase letters, digits and hyphens.",
  );

/** PUT /api/v1/tenants/{id} — name only; blank is ignored by the backend. */
export const editTenantSchema = z.object({
  name,
});
export type EditTenantValues = z.infer<typeof editTenantSchema>;

/** Builds the PUT payload; unchanged/blank names are omitted. */
export function toUpdateTenantPayload(
  values: EditTenantValues,
  original: { name: string },
): UpdateTenantPayload {
  if (values.name === "" || values.name === original.name) {
    return {};
  }
  return { name: values.name };
}

/**
 * POST /api/v1/tenants — provisioning with mandatory initial admin.
 * resellerId intentionally absent: scope is server-derived for
 * reseller-bound callers and direct tenants need none.
 */
export const createTenantSchema = z.object({
  name,
  slug,
  adminEmail: z.email("Enter a valid email address.").max(255),
  adminPassword: z
    .string()
    .min(PASSWORD_MIN, `Password must be at least ${PASSWORD_MIN} characters.`)
    .max(PASSWORD_MAX, `Password must be at most ${PASSWORD_MAX} characters.`),
  adminDisplayName: z.string().trim().max(120),
});
export type CreateTenantValues = z.infer<typeof createTenantSchema>;

export function toCreateTenantPayload(
  values: CreateTenantValues,
): CreateTenantPayload {
  return {
    name: values.name,
    slug: values.slug,
    resellerId: null,
    admin: {
      email: values.adminEmail,
      password: values.adminPassword,
      displayName: values.adminDisplayName || undefined,
    },
  };
}
