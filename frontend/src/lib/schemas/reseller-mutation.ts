import { z } from "zod";

import type {
  CreateResellerPayload,
  UpdateResellerPayload,
} from "@/lib/api/contracts";
import { PASSWORD_MAX, PASSWORD_MIN } from "@/lib/schemas/auth";

/** Backend constraints verified against reseller DTOs. */
export const RESELLER_NAME_MAX = 150;
export const RESELLER_SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

const name = z
  .string()
  .trim()
  .min(1, "Name is required.")
  .max(RESELLER_NAME_MAX, `Name must be at most ${RESELLER_NAME_MAX} characters.`);

const optionalText = (max: number) =>
  z.string().trim().max(max, `Must be at most ${max} characters.`);

const supportEmail = z.union([
  z.literal(""),
  z.email("Enter a valid email address.").max(255),
]);

/** Shared editable profile fields (PUT body minus immutables). */
const editableProfile = {
  displayName: optionalText(150),
  supportEmail,
  logoUrl: optionalText(500),
  primaryColor: optionalText(20),
};

/**
 * PUT /api/v1/resellers/{id}. Backend applies any non-null field, so a
 * cleared field is transmitted as "" (explicit clear) while unchanged
 * fields are omitted entirely.
 */
export const editResellerSchema = z.object({
  name,
  ...editableProfile,
});
export type EditResellerValues = z.infer<typeof editResellerSchema>;

function changedOrCleared(
  value: string,
  original: string | null,
): string | undefined {
  if (value === (original ?? "")) return undefined;
  return value;
}

export function toUpdateResellerPayload(
  values: EditResellerValues,
  original: Pick<
    ResellerResponseLike,
    "name" | "displayName" | "supportEmail" | "logoUrl" | "primaryColor"
  >,
): UpdateResellerPayload {
  const payload: UpdateResellerPayload = {};
  const nextName = changedOrCleared(values.name, original.name);
  if (nextName) payload.name = nextName;
  const displayName = changedOrCleared(values.displayName, original.displayName);
  if (displayName !== undefined) payload.displayName = displayName;
  const supportEmailValue = changedOrCleared(
    values.supportEmail,
    original.supportEmail,
  );
  if (supportEmailValue !== undefined) payload.supportEmail = supportEmailValue;
  const logoUrl = changedOrCleared(values.logoUrl, original.logoUrl);
  if (logoUrl !== undefined) payload.logoUrl = logoUrl;
  const primaryColor = changedOrCleared(values.primaryColor, original.primaryColor);
  if (primaryColor !== undefined) payload.primaryColor = primaryColor;
  return payload;
}

interface ResellerResponseLike {
  name: string;
  displayName: string | null;
  supportEmail: string | null;
  logoUrl: string | null;
  primaryColor: string | null;
}

const adminEmail = z.email("Enter a valid email address.").max(255);

const adminPassword = z
  .string()
  .min(PASSWORD_MIN, `Password must be at least ${PASSWORD_MIN} characters.`)
  .max(PASSWORD_MAX, `Password must be at most ${PASSWORD_MAX} characters.`);

/**
 * POST /api/v1/resellers — the embedded administrator is OPTIONAL; when
 * the checkbox is off, admin fields are ignored entirely.
 */
export const createResellerSchema = z
  .object({
    name,
    slug: z
      .string()
      .trim()
      .min(1, "Slug is required.")
      .max(100, "Slug must be at most 100 characters.")
      .regex(RESELLER_SLUG_PATTERN, "Use lowercase letters, digits and hyphens."),
    customDomain: optionalText(255),
    createAdmin: z.boolean(),
    adminEmail: z.string(),
    adminPassword: z.string(),
    adminDisplayName: optionalText(120),
    ...editableProfile,
  })
  .superRefine((v, ctx) => {
    if (!v.createAdmin) return;

    if (!adminEmail.safeParse(v.adminEmail).success) {
      ctx.addIssue({
        code: "custom",
        path: ["adminEmail"],
        message:
          v.adminEmail.trim() === ""
            ? "Administrator email is required."
            : "Enter a valid email address.",
      });
    }
    if (!adminPassword.safeParse(v.adminPassword).success) {
      ctx.addIssue({
        code: "custom",
        path: ["adminPassword"],
        message:
          v.adminPassword === ""
            ? "Administrator password is required."
            : `Password must be between ${PASSWORD_MIN} and ${PASSWORD_MAX} characters.`,
      });
    }
  });
export type CreateResellerValues = z.infer<typeof createResellerSchema>;

export function toCreateResellerPayload(
  values: CreateResellerValues,
): CreateResellerPayload {
  return {
    name: values.name,
    slug: values.slug,
    displayName: values.displayName || undefined,
    supportEmail: values.supportEmail || undefined,
    customDomain: values.customDomain || undefined,
    logoUrl: values.logoUrl || undefined,
    primaryColor: values.primaryColor || undefined,
    admin: values.createAdmin
      ? {
          email: values.adminEmail,
          password: values.adminPassword,
          displayName: values.adminDisplayName || undefined,
        }
      : null,
  };
}
