import { z } from "zod";
import type { CreateCallAttemptPayload, MarkAttemptFailedParams } from "@/lib/api/contracts";
import {
  CALL_ATTEMPT_LEGAL_TRANSITIONS,
  CALL_ATTEMPT_SYSTEM_TRANSITIONS,
} from "@/lib/api/call-attempts";

/** Backend constraints verified against CreateCallAttemptRequest
 * (`@NotNull UUID contactId`, `@NotNull UUID didId`, `@NotNull @Positive Integer
 * attemptNumber`, optional `Instant scheduledAt`). */
export const ATTEMPT_NUMBER_MIN = 1;

export const createCallAttemptSchema = z.object({
  contactId: z.string().uuid("Contact ID must be a valid UUID"),
  didId: z.string().uuid("DID ID must be a valid UUID"),
  attemptNumber: z
    .number()
    .int()
    .min(ATTEMPT_NUMBER_MIN, `Attempt number must be at least ${ATTEMPT_NUMBER_MIN}`),
  scheduledAt: z.string().datetime({ offset: true }).optional().nullable(),
});
export type CreateCallAttemptValues = z.infer<typeof createCallAttemptSchema>;

export function toCreateCallAttemptPayload(values: CreateCallAttemptValues): CreateCallAttemptPayload {
  return {
    contactId: values.contactId,
    didId: values.didId,
    attemptNumber: values.attemptNumber,
    scheduledAt: values.scheduledAt ?? undefined,
  };
}

/** PATCH .../attempts/{attemptId}/failed
 *
 * VERIFIED (F1): the backend declares these as `@RequestParam(required = false)`
 * with no `@RequestBody`, so this schema produces query parameters, not a JSON
 * body. The mapper is renamed to `…Params` to stop implying a body. */
export const markAttemptFailedSchema = z.object({
  failureCode: z.string().max(50).optional().nullable(),
  failureReason: z.string().max(2000).optional().nullable(),
});
export type MarkAttemptFailedValues = z.infer<typeof markAttemptFailedSchema>;

export function toMarkAttemptFailedParams(
  values: MarkAttemptFailedValues,
): MarkAttemptFailedParams {
  return {
    failureCode: values.failureCode ?? undefined,
    failureReason: values.failureReason ?? undefined,
  };
}

/** F1: the transition tables now live in `@/lib/api/call-attempts`, next to the
 * endpoints they describe. They were duplicated here with a weaker
 * `Record<string, string[]>` type; re-exported so existing imports keep
 * working and there is only one copy to keep accurate. */
export { CALL_ATTEMPT_LEGAL_TRANSITIONS, CALL_ATTEMPT_SYSTEM_TRANSITIONS };