import { z } from "zod";

import type {
  CreateContactGroupPayload,
  UpdateContactGroupPayload,
} from "@/lib/api/contracts";

/** Backend constraints verified against Contact Group DTOs. */
export const CONTACT_GROUP_NAME_MAX = 150;
export const CONTACT_GROUP_DESCRIPTION_MAX = 5000;

/** POST /api/v1/contact-groups — creation payload. */
export const createContactGroupSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(CONTACT_GROUP_NAME_MAX, `Name must be at most ${CONTACT_GROUP_NAME_MAX} characters.`),
  description: z.string().trim().max(CONTACT_GROUP_DESCRIPTION_MAX).optional().nullable(),
});
export type CreateContactGroupValues = z.infer<typeof createContactGroupSchema>;

export function toCreateContactGroupPayload(
  values: CreateContactGroupValues,
): CreateContactGroupPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
  };
}

/** PUT /api/v1/contact-groups/{id} — PUT semantics: replaces the mutable group representation. */
export const updateContactGroupSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(CONTACT_GROUP_NAME_MAX, `Name must be at most ${CONTACT_GROUP_NAME_MAX} characters.`),
  description: z.string().trim().max(CONTACT_GROUP_DESCRIPTION_MAX).optional().nullable(),
});
export type UpdateContactGroupValues = z.infer<typeof updateContactGroupSchema>;

export function toUpdateContactGroupPayload(
  values: UpdateContactGroupValues,
): UpdateContactGroupPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
  };
}