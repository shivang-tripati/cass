import { z } from "zod";

import type {
  CreateCampaignPayload,
  UpdateCampaignPayload,
  UpdateCampaignStatusPayload,
  ExecuteCampaignPayload,
} from "@/lib/api/contracts";

/** Backend constraints verified against Campaign DTOs. */
export const CAMPAIGN_NAME_MAX = 200;
export const CAMPAIGN_DESCRIPTION_MAX = 5000;

/** ScheduleConfig schema matching backend validation rules. */
export const scheduleConfigSchema = z
  .object({
    startDate: z.string().date("Invalid start date format (YYYY-MM-DD).").optional().nullable(),
    endDate: z.string().date("Invalid end date format (YYYY-MM-DD).").optional().nullable(),
    startTime: z.string().time("Invalid start time format (HH:MM).").optional().nullable(),
    endTime: z.string().time("Invalid end time format (HH:MM).").optional().nullable(),
    timezone: z.string().max(64).optional().nullable(),
    allowedDaysOfWeek: z.array(z.string()).optional().nullable(),
    holidayCalendarId: z.string().uuid().optional().nullable(),
  })
  .refine(
    (data) => {
      if (data.startDate && data.endDate && data.endDate < data.startDate) {
        return false;
      }
      return true;
    },
    { message: "End date must not be before start date.", path: ["endDate"] },
  )
  .refine(
    (data) => {
      if (data.startTime && data.endTime && data.endTime <= data.startTime) {
        return false;
      }
      return true;
    },
    { message: "Daily end time must be after daily start time.", path: ["endTime"] },
  )
  .refine(
    (data) => {
      const windowConfigured =
        data.startDate || data.endDate || data.startTime || data.endTime;
      if (windowConfigured && (!data.timezone || data.timezone.trim() === "")) {
        return false;
      }
      return true;
    },
    { message: "Timezone is required when a schedule window is configured.", path: ["timezone"] },
  )
  .refine(
    (data) => {
      if (data.timezone && data.timezone.trim() !== "") {
        try {
          // Basic IANA timezone validation - just check it's a known format
          Intl.DateTimeFormat(undefined, { timeZone: data.timezone.trim() });
        } catch {
          return false;
        }
      }
      return true;
    },
    { message: "Timezone must be a valid IANA identifier.", path: ["timezone"] },
  );

export type ScheduleConfigValues = z.infer<typeof scheduleConfigSchema>;

/** RetryPolicyConfig schema matching backend validation rules. */
export const retryPolicyConfigSchema = z
  .object({
    maxAttempts: z
      .number()
      .int()
      .min(0, "Max attempts must be at least 0.")
      .max(10, "Max attempts must be at most 10."),
    intervalSeconds: z
      .number()
      .int()
      .min(1, "Interval must be at least 1 second.")
      .max(604800, "Interval must be at most 604800 seconds (7 days).")
      .optional()
      .nullable(),
    strategy: z.enum(["FIXED"]),
  })
  .refine(
    (data) => {
      if (data.maxAttempts > 0 && (!data.intervalSeconds || data.intervalSeconds <= 0)) {
        return false;
      }
      return true;
    },
    {
      message: "A positive retry interval is required when retry attempts is greater than zero.",
      path: ["intervalSeconds"],
    },
  );

export type RetryPolicyConfigValues = z.infer<typeof retryPolicyConfigSchema>;

/** Campaign creation schema. */
export const createCampaignSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Name is required.")
      .max(CAMPAIGN_NAME_MAX, `Name must be at most ${CAMPAIGN_NAME_MAX} characters.`),
    description: z.string().trim().max(CAMPAIGN_DESCRIPTION_MAX).optional().nullable(),
    campaignType: z.enum(["PLAYFILE", "DTMF", "CONNECT_BY_AGENT"]),
    runMode: z.enum(["ONE_TIME", "RECURRING"]).optional(),
    contactGroupId: z.string().uuid().optional().nullable(),
    didId: z.string().uuid().optional().nullable(),
    contentMode: z.enum(["AUDIO", "TTS"]).optional(),
    audioAssetId: z.string().uuid().optional().nullable(),
    ttsTemplateId: z.string().uuid().optional().nullable(),
    schedule: scheduleConfigSchema.optional().nullable(),
    retryPolicy: retryPolicyConfigSchema.optional().nullable(),
    typeConfig: z.record(z.string(), z.unknown()).optional().nullable(),
    integrationConfig: z.record(z.string(), z.unknown()).optional().nullable(),
  })
  .superRefine((data, ctx) => {
    // Content mode validation: AUDIO requires audioAssetId, TTS requires ttsTemplateId
    if (data.contentMode === "AUDIO") {
      if (!data.audioAssetId || data.ttsTemplateId) {
        ctx.addIssue({
          code: "custom",
          path: ["audioAssetId"],
          message: "AUDIO content requires exactly one audio asset reference.",
        });
      }
    } else if (data.contentMode === "TTS") {
      if (!data.ttsTemplateId || data.audioAssetId) {
        ctx.addIssue({
          code: "custom",
          path: ["ttsTemplateId"],
          message: "TTS content requires exactly one approved TTS template reference.",
        });
      }
    } else {
      if (data.audioAssetId || data.ttsTemplateId) {
        ctx.addIssue({
          code: "custom",
          path: ["contentMode"],
          message: "Content references require an explicit content mode.",
        });
      }
    }

    // Content is required for PLAYFILE and DTMF (CONNECT_BY_AGENT does not require content)
    if (data.campaignType !== "CONNECT_BY_AGENT" && !data.contentMode) {
      ctx.addIssue({
        code: "custom",
        path: ["contentMode"],
        message: `${data.campaignType} campaigns require content (audio or TTS).`,
      });
    }

    // Type config required for DTMF and CONNECT_BY_AGENT
    if ((data.campaignType === "DTMF" || data.campaignType === "CONNECT_BY_AGENT")) {
      if (!data.typeConfig || Object.keys(data.typeConfig).length === 0) {
        ctx.addIssue({
          code: "custom",
          path: ["typeConfig"],
          message: `${data.campaignType} campaigns require type-specific configuration.`,
        });
      }
    }

    // RECURRING requires schedule
    if (data.runMode === "RECURRING" && !data.schedule) {
      ctx.addIssue({
        code: "custom",
        path: ["schedule"],
        message: "RECURRING campaigns require a configured schedule.",
      });
    }
  });

export type CreateCampaignValues = z.infer<typeof createCampaignSchema>;

export function toCreateCampaignPayload(
  values: CreateCampaignValues,
): CreateCampaignPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
    campaignType: values.campaignType,
    runMode: values.runMode,
    contactGroupId: values.contactGroupId ?? undefined,
    didId: values.didId ?? undefined,
    contentMode: values.contentMode,
    audioAssetId: values.audioAssetId ?? undefined,
    ttsTemplateId: values.ttsTemplateId ?? undefined,
    schedule: values.schedule
      ? {
          startDate: values.schedule.startDate ?? null,
          endDate: values.schedule.endDate ?? null,
          startTime: values.schedule.startTime ?? null,
          endTime: values.schedule.endTime ?? null,
          timezone: values.schedule.timezone ?? null,
          allowedDaysOfWeek: values.schedule.allowedDaysOfWeek ?? null,
          holidayCalendarId: values.schedule.holidayCalendarId ?? null,
        }
      : undefined,
    retryPolicy: values.retryPolicy
      ? {
          maxAttempts: values.retryPolicy.maxAttempts,
          intervalSeconds: values.retryPolicy.intervalSeconds ?? null,
          strategy: values.retryPolicy.strategy,
        }
      : undefined,
    typeConfig: values.typeConfig ?? undefined,
    integrationConfig: values.integrationConfig ?? undefined,
  };
}

/** Campaign update schema (PUT semantics - omitted optional blocks are cleared). */
export const updateCampaignSchema = z
  .object({
    name: z
      .string()
      .trim()
      .min(1, "Name is required.")
      .max(CAMPAIGN_NAME_MAX, `Name must be at most ${CAMPAIGN_NAME_MAX} characters.`),
    description: z.string().trim().max(CAMPAIGN_DESCRIPTION_MAX).optional().nullable(),
    runMode: z.enum(["ONE_TIME", "RECURRING"]).optional(),
    contactGroupId: z.string().uuid().optional().nullable(),
    didId: z.string().uuid().optional().nullable(),
    contentMode: z.enum(["AUDIO", "TTS"]).optional(),
    audioAssetId: z.string().uuid().optional().nullable(),
    ttsTemplateId: z.string().uuid().optional().nullable(),
    schedule: scheduleConfigSchema.optional().nullable(),
    retryPolicy: retryPolicyConfigSchema.optional().nullable(),
    typeConfig: z.record(z.string(), z.unknown()).optional().nullable(),
    integrationConfig: z.record(z.string(), z.unknown()).optional().nullable(),
  })
  .superRefine((data, ctx) => {
    // Content mode validation: AUDIO requires audioAssetId, TTS requires ttsTemplateId
    if (data.contentMode === "AUDIO") {
      if (!data.audioAssetId || data.ttsTemplateId) {
        ctx.addIssue({
          code: "custom",
          path: ["audioAssetId"],
          message: "AUDIO content requires exactly one audio asset reference.",
        });
      }
    } else if (data.contentMode === "TTS") {
      if (!data.ttsTemplateId || data.audioAssetId) {
        ctx.addIssue({
          code: "custom",
          path: ["ttsTemplateId"],
          message: "TTS content requires exactly one approved TTS template reference.",
        });
      }
    } else {
      if (data.audioAssetId || data.ttsTemplateId) {
        ctx.addIssue({
          code: "custom",
          path: ["contentMode"],
          message: "Content references require an explicit content mode.",
        });
      }
    }

    // Type config required for DTMF and CONNECT_BY_AGENT (we need original campaignType)
    // This will be validated server-side since we don't have original type here

    // RECURRING requires schedule
    if (data.runMode === "RECURRING" && !data.schedule) {
      ctx.addIssue({
        code: "custom",
        path: ["schedule"],
        message: "RECURRING campaigns require a configured schedule.",
      });
    }
  });

export type UpdateCampaignValues = z.infer<typeof updateCampaignSchema>;

export function toUpdateCampaignPayload(
  values: UpdateCampaignValues,
): UpdateCampaignPayload {
  return {
    name: values.name,
    description: values.description ?? undefined,
    runMode: values.runMode,
    contactGroupId: values.contactGroupId ?? undefined,
    didId: values.didId ?? undefined,
    contentMode: values.contentMode,
    audioAssetId: values.audioAssetId ?? undefined,
    ttsTemplateId: values.ttsTemplateId ?? undefined,
    schedule: values.schedule
      ? {
          startDate: values.schedule.startDate ?? null,
          endDate: values.schedule.endDate ?? null,
          startTime: values.schedule.startTime ?? null,
          endTime: values.schedule.endTime ?? null,
          timezone: values.schedule.timezone ?? null,
          allowedDaysOfWeek: values.schedule.allowedDaysOfWeek ?? null,
          holidayCalendarId: values.schedule.holidayCalendarId ?? null,
        }
      : undefined,
    retryPolicy: values.retryPolicy
      ? {
          maxAttempts: values.retryPolicy.maxAttempts,
          intervalSeconds: values.retryPolicy.intervalSeconds ?? null,
          strategy: values.retryPolicy.strategy,
        }
      : undefined,
    typeConfig: values.typeConfig ?? undefined,
    integrationConfig: values.integrationConfig ?? undefined,
  };
}

/** Status transition schema. */
export const updateCampaignStatusSchema = z.object({
  status: z.enum([
    "DRAFT",
    "SCHEDULED",
    "RUNNING",
    "PAUSED",
    "COMPLETED",
    "FAILED",
    "ARCHIVED",
  ]),
});

export type UpdateCampaignStatusValues = z.infer<typeof updateCampaignStatusSchema>;

export function toUpdateCampaignStatusPayload(
  values: UpdateCampaignStatusValues,
): UpdateCampaignStatusPayload {
  return { status: values.status };
}

/** Execute campaign schema. */
export const executeCampaignSchema = z.object({
  idempotencyKey: z.string().max(128).optional().nullable(),
});

export type ExecuteCampaignValues = z.infer<typeof executeCampaignSchema>;

export function toExecuteCampaignPayload(
  values: ExecuteCampaignValues,
): ExecuteCampaignPayload {
  return { idempotencyKey: values.idempotencyKey ?? undefined };
}

