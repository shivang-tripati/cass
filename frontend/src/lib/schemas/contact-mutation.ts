import { z } from "zod";

import type {
  CreateContactPayload,
  UpdateContactPayload,
} from "@/lib/api/contracts";

/** Backend constraints verified against Contact DTOs. */
export const CONTACT_NAME_MAX = 100;
export const CONTACT_EMAIL_MAX = 255;
export const CONTACT_E164_REGEX = /^\+[1-9][0-9]{6,14}$/;

/** POST /api/v1/contact-groups/{contactGroupId}/contacts — creation payload. */
export const createContactSchema = z.object({
  firstName: z.string().trim().max(CONTACT_NAME_MAX).optional().nullable(),
  lastName: z.string().trim().max(CONTACT_NAME_MAX).optional().nullable(),
  phoneNumber: z
    .string()
    .trim()
    .min(1, "Phone number is required.")
    .regex(/^\+[1-9][0-9]{6,14}$/, "Must be a valid E.164 number, e.g. +918012345678."),
  email: z
    .string()
    .trim()
    .max(CONTACT_EMAIL_MAX, `Email must be at most ${CONTACT_EMAIL_MAX} characters.`)
    .email("Enter a valid email address.")
    .optional()
    .nullable(),
  attributes: z.record(z.string(), z.unknown()).optional().nullable(),
});
export type CreateContactValues = z.infer<typeof createContactSchema>;

export function toCreateContactPayload(values: CreateContactValues): CreateContactPayload {
  return {
    firstName: values.firstName ?? undefined,
    lastName: values.lastName ?? undefined,
    phoneNumber: values.phoneNumber,
    email: values.email ?? undefined,
    attributes: values.attributes ?? undefined,
  };
}

/** PUT /api/v1/contact-groups/{contactGroupId}/contacts/{contactId} — PUT semantics: replaces the mutable contact representation. */
export const updateContactSchema = z.object({
  firstName: z.string().trim().max(CONTACT_NAME_MAX).optional().nullable(),
  lastName: z.string().trim().max(CONTACT_NAME_MAX).optional().nullable(),
  phoneNumber: z
    .string()
    .trim()
    .min(1, "Phone number is required.")
    .regex(/^\+[1-9][0-9]{6,14}$/, "Must be a valid E.164 number, e.g. +918012345678."),
  email: z
    .string()
    .trim()
    .max(CONTACT_EMAIL_MAX, `Email must be at most ${CONTACT_EMAIL_MAX} characters.`)
    .email("Enter a valid email address.")
    .optional()
    .nullable(),
  attributes: z.record(z.string(), z.unknown()).optional().nullable(),
});
export type UpdateContactValues = z.infer<typeof updateContactSchema>;

export function toUpdateContactPayload(values: UpdateContactValues): UpdateContactPayload {
  return {
    firstName: values.firstName ?? undefined,
    lastName: values.lastName ?? undefined,
    phoneNumber: values.phoneNumber,
    email: values.email ?? undefined,
    attributes: values.attributes ?? undefined,
  };
}