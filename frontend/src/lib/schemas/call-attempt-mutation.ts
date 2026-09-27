import { z } from "zod";
import type { CreateCallAttemptPayload } from "@/lib/api/contracts";

/** Backend constraints verified against CreateCallAttemptRequest. */
export const ATTEMPT_NUMBER_MIN = 1;

export const createCallAttemptSchema = z.object({
  contactId: z.string().uuid("Contact ID must be a valid UUID"),
  didId: z.string().uuid("DID ID must be a valid UUID"),
  attemptNumber: z.number().int().min(1, "Attempt number must be at least 1"),
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

/** POST /api/v1/campaigns/{campaignId}/executions/{executionId}/attempts/{id}/failed */
export const markAttemptFailedSchema = z.object({
  failureCode: z.string().max(50).optional().nullable(),
  failureReason: z.string().max(2000).optional().nullable(),
});
export type MarkAttemptFailedValues = z.infer<typeof markAttemptFailedSchema>;

export function toMarkAttemptFailedPayload(values: MarkAttemptFailedValues) {
  return {
    failureCode: values.failureCode ?? undefined,
    failureReason: values.failureReason ?? undefined,
  };
}

/** Legal transitions mirroring backend CallAttemptService.transitionStatus. */
export const CALL_ATTEMPT_LEGAL_TRANSITIONS: Record<string, string[]> = {
  QUEUED: ["IN_PROGRESS", "FAILED", "CANCELLED"],
  IN_PROGRESS: ["COMPLETED", "FAILED", "CANCELLED"],
  COMPLETED: [],
  FAILED: [],
  CANCELLED: [],
};

/** System-driven transitions that cannot be invoked manually. */
export const CALL_ATTEMPT_SYSTEM_TRANSITIONS: Array<{ from: string; to: string }> = [
  { from: "QUEUED", to: "IN_PROGRESS" },
  { from: "IN_PROGRESS", to: "COMPLETED" },
  { from: "IN_PROGRESS", to: "FAILED" },
];