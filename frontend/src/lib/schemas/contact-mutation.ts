import { z } from "zod";

import type {
  CreateContactPayload,
  UpdateContactPayload,
} from "@/lib/api/contracts";

/**
 * Contact form schemas.
 *
 * VERIFIED (F2) against `contact/ContactValidation`, `contact/dto/CreateContactRequest`
 * and `contact/dto/UpdateContactRequest`.
 */

/** `ContactValidation.E164_REGEX` — the single shared authority. */
export const CONTACT_E164_REGEX = /^\+[1-9][0-9]{6,14}$/;
export const CONTACT_E164_HINT = "Must be a valid E.164 number, e.g. +918012345678.";

/** `ContactValidation.canonicalizePhoneNumber` strips these before matching, so
 * the API tolerates them even though the regex does not. The client accepts the
 * same formatting rather than making the user normalise by hand. */
const PHONE_SEPARATORS = /[\s().-]/g;

export const CONTACT_NAME_MAX = 100;
export const CONTACT_EMAIL_MAX = 255;

/** Strips the formatting the backend tolerates, leaving the canonical E.164
 * form the backend will persist. Exported so a form can preview what will be
 * stored and the tests can pin the behaviour. */
export function canonicalizePhoneNumber(raw: string): string | null {
  const candidate = raw.trim().replace(PHONE_SEPARATORS, "");
  return CONTACT_E164_REGEX.test(candidate) ? candidate : null;
}

/**
 * `attributes` is a `JsonNode` on the backend — a free-form JSON OBJECT, not a
 * string. The previous schema typed it `z.record(z.string(), z.unknown())`
 * while the form bound a `<TextareaField>`, so the textarea's STRING was fed to
 * a record schema: the field could never validate, and any value that did
 * validate could not be typed.
 *
 * F2 fixes the boundary properly: the form holds a JSON *string* (that is what
 * a textarea produces) and this parses it into the object the API expects. A
 * blank string means "no attributes" and becomes `undefined`, so the field is
 * never sent as an empty object.
 */
const attributesJsonSchema = z
  .string()
  .trim()
  .max(20000, "Attributes must be at most 20000 characters.")
  .transform((value, ctx) => {
    if (value.length === 0) return undefined;
    let parsed: unknown;
    try {
      parsed = JSON.parse(value);
    } catch {
      ctx.addIssue({
        code: "custom",
        message: "Attributes must be valid JSON.",
      });
      return z.NEVER;
    }
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      ctx.addIssue({
        code: "custom",
        message: "Attributes must be a JSON object, for example {\"city\": \"Pune\"}.",
      });
      return z.NEVER;
    }
    return parsed as Record<string, unknown>;
  });

const optionalText = (max: number) =>
  z
    .string()
    .trim()
    .max(max, `Must be at most ${max} characters.`)
    .transform((value) => (value.length === 0 ? undefined : value))
    .optional();

/** POST /api/v1/contact-groups/{groupId}/contacts
 *
 * VERIFIED annotations: `firstName`/`lastName` `@Size(max=100)`, `phoneNumber`
 * `@Pattern(E164)`, `email` `@Email @Size(max=255)`, `attributes` unvalidated
 * JsonNode. The backend does NOT mark `phoneNumber` `@NotBlank`, but
 * `ContactIdentityService.canonicalPhoneNumber` throws a VALIDATION_ERROR for
 * anything that cannot reach canonical form, so it is required in practice and
 * the client requires it too.
 */
export const createContactSchema = z.object({
  firstName: optionalText(CONTACT_NAME_MAX).nullable(),
  lastName: optionalText(CONTACT_NAME_MAX).nullable(),
  phoneNumber: z
    .string()
    .trim()
    .min(1, "Phone number is required.")
    .transform(canonicalizePhoneNumber)
    .refine((value): value is string => value !== null, {
      message: CONTACT_E164_HINT,
    }),
  email: z
    .string()
    .trim()
    .max(CONTACT_EMAIL_MAX, `Email must be at most ${CONTACT_EMAIL_MAX} characters.`)
    .transform((value) => (value.length === 0 ? undefined : value))
    .refine(
      (value) => value === undefined || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value),
      { message: "Enter a valid email address." },
    )
    .optional()
    .nullable(),
  attributes: attributesJsonSchema.optional().nullable(),
});
export type CreateContactValues = z.infer<typeof createContactSchema>;
export type CreateContactFormValues = z.input<typeof createContactSchema>;

export function toCreateContactPayload(
  values: CreateContactValues,
): CreateContactPayload {
  return {
    firstName: values.firstName ?? undefined,
    lastName: values.lastName ?? undefined,
    phoneNumber: values.phoneNumber,
    email: values.email ?? undefined,
    attributes: values.attributes ?? undefined,
  };
}

/** PUT /api/v1/contact-groups/{groupId}/contacts/{contactId}
 *
 * VERIFIED: the same field set as create, and the update is applied as a
 * PARTIAL update by `ContactMapper.applyCommon` — `firstName`/`lastName`/
 * `email` are blank-to-null, and `attributes` is set only when the field is
 * present on the request. */
export const updateContactSchema = createContactSchema;
export type UpdateContactValues = z.infer<typeof updateContactSchema>;
export type UpdateContactFormValues = z.input<typeof updateContactSchema>;

export function toUpdateContactPayload(
  values: UpdateContactValues,
): UpdateContactPayload {
  return {
    firstName: values.firstName ?? undefined,
    lastName: values.lastName ?? undefined,
    phoneNumber: values.phoneNumber,
    email: values.email ?? undefined,
    attributes: values.attributes ?? undefined,
  };
}

/** Serialises a contact's `attributes` for display in a textarea. Returns an
 * empty string when there is nothing, which the schema maps back to
 * `undefined`. Never throws on a malformed value. */
export function formatAttributesForInput(
  attributes: Record<string, unknown> | null | undefined,
): string {
  if (!attributes || Object.keys(attributes).length === 0) return "";
  try {
    return JSON.stringify(attributes, null, 2);
  } catch {
    return "";
  }
}
