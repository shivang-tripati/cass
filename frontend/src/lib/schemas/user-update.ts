import { z } from "zod";

import type { UpdateUserRequest } from "@/lib/api/contracts";
import type { LifecycleStatus } from "@/lib/api/types";

/**
 * PUT /api/v1/users/{id} — UpdateUserRequest {displayName ≤120, status}.
 * Backend semantics: a blank displayName is IGNORED (no clear), so the
 * form treats an emptied field as "no change" and omits it from the payload.
 */
export const USER_DISPLAY_NAME_MAX = 120;

const displayName = z
  .string()
  .trim()
  .max(
    USER_DISPLAY_NAME_MAX,
    `Display name must be at most ${USER_DISPLAY_NAME_MAX} characters.`,
  );

export const editUserSchema = z.object({
  displayName,
  status: z.enum(["ACTIVE", "SUSPENDED"]),
});
export type EditUserValues = z.infer<typeof editUserSchema>;

/** Builds the API payload, omitting unchanged/ignored fields. */
export function toUpdateUserRequest(
  values: EditUserValues,
  original: { displayName: string | null },
): UpdateUserRequest {
  const request: UpdateUserRequest = { status: values.status as LifecycleStatus };
  if (values.displayName !== "" && values.displayName !== (original.displayName ?? "")) {
    request.displayName = values.displayName;
  }
  return request;
}
