import { z } from "zod";

import { PASSWORD_MAX, PASSWORD_MIN } from "@/lib/schemas/auth";

/**
 * Client-side schemas for the anonymous self-signup contracts.
 *
 * @see com.shivang.obd.tenant.dto.TenantSignupRequest
 * @see com.shivang.obd.reseller.dto.CreateResellerRequest
 */

/** Backend @Pattern: lowercase letters, digits and hyphens. */
export const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

const requiredName = (max: number) =>
  z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(max, `Name must be at most ${max} characters.`);

const slug = z
  .string()
  .trim()
  .min(1, "Slug is required.")
  .max(100, "Slug must be at most 100 characters.")
  .regex(SLUG_PATTERN, "Use lowercase letters, digits and hyphens.");

const optionalText = (max: number) =>
  z.string().trim().max(max, `Must be at most ${max} characters.`);

const adminEmail = z.email("Enter a valid email address.").max(255);

const adminPassword = z
  .string()
  .min(PASSWORD_MIN, `Password must be at least ${PASSWORD_MIN} characters.`)
  .max(PASSWORD_MAX, `Password must be at most ${PASSWORD_MAX} characters.`);

/** POST /api/v1/account/signup/tenant — admin is mandatory. */
export const tenantSignupSchema = z.object({
  name: requiredName(150),
  slug,
  adminEmail,
  adminPassword,
  adminDisplayName: optionalText(120),
});
export type TenantSignupValues = z.infer<typeof tenantSignupSchema>;

/**
 * POST /api/v1/account/signup/reseller — the whole request is anonymous and
 * the embedded administrator account is OPTIONAL on the backend; when the
 * checkbox is off, admin fields are ignored entirely.
 */
export const resellerSignupSchema = z
  .object({
    name: requiredName(150),
    slug,
    displayName: optionalText(150),
    supportEmail: z.union([z.literal(""), adminEmail]),
    customDomain: optionalText(255),
    logoUrl: optionalText(500),
    primaryColor: optionalText(20),
    createAdmin: z.boolean(),
    adminEmail: z.string(),
    adminPassword: z.string(),
    adminDisplayName: optionalText(120),
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
export type ResellerSignupValues = z.infer<typeof resellerSignupSchema>;
