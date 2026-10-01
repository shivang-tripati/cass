import { z } from "zod";

import type {
  CreateContactGroupPayload,
  UpdateContactGroupPayload,
} from "@/lib/api/contracts";

/**
 * Contact group form schemas.
 *
 * VERIFIED (F2) against `contact/dto/CreateContactGroupRequest` and
 * `contact/dto/UpdateContactGroupRequest`:
 *
 *   record CreateContactGroupRequest(@NotBlank @Size(max=150) String name,
 *                                   @Size(max=5000) String description)
 *
 * Both records are structurally IDENTICAL, so create and update share one field
 * set. There is no `tenantId` and no `memberCount` on either: ownership is
 * always the caller's context tenant (`ContactGroupService.createGroup` derives
 * it from the authenticated scope, never from the request), and membership is
 * mutated only through the `/members` API.
 *
 * `name` is stored verbatim by `ContactGroupMapper.applyCommon`
 * (`entity.setName(name)` — no trim, no blank-to-null), so the client trims
 * before sending. `description` is also verbatim, and because the mapper assigns
 * it unconditionally, an OMITTED description CLEARS the stored value. The
 * transform below maps "" to `undefined`, which is omitted from the JSON body
 * and therefore clears the field — the same outcome, without sending an empty
 * string that would be persisted as an empty string.
 */
export const CONTACT_GROUP_NAME_MAX = 150;
export const CONTACT_GROUP_DESCRIPTION_MAX = 5000;

const groupFields = {
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(CONTACT_GROUP_NAME_MAX, `Name must be at most ${CONTACT_GROUP_NAME_MAX} characters.`),
  description: z
    .string()
    .trim()
    .max(
      CONTACT_GROUP_DESCRIPTION_MAX,
      `Description must be at most ${CONTACT_GROUP_DESCRIPTION_MAX} characters.`,
    )
    .transform((value) => (value.length === 0 ? undefined : value))
    .optional()
    .nullable(),
};

/** POST /api/v1/contact-groups */
export const createContactGroupSchema = z.object(groupFields);
export type CreateContactGroupValues = z.infer<typeof createContactGroupSchema>;

export function toCreateContactGroupPayload(
  values: CreateContactGroupValues,
): CreateContactGroupPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
  };
}

/** PUT /api/v1/contact-groups/{id} — same field set, same DTO shape. */
export const updateContactGroupSchema = z.object(groupFields);
export type UpdateContactGroupValues = z.infer<typeof updateContactGroupSchema>;

export function toUpdateContactGroupPayload(
  values: UpdateContactGroupValues,
): UpdateContactGroupPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
  };
}