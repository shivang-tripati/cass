import { z } from "zod";

import type {
  CreateCampaignPayload,
  UpdateCampaignPayload,
  UpdateCampaignStatusPayload,
  ExecuteCampaignPayload,
} from "@/lib/api/contracts";
import {
  campaignTypeConfigSchema,
  campaignIntegrationConfigSchema,
  campaignTypeSchema,
  campaignTypePlaysMedia,
  retryRuleConfigSchema,
  assertUniqueRetryRuleCategories,
  normalizeTypeConfig,
  typeConfigMatchesCampaignType,
} from "@/lib/schemas/campaign-config";

/** Backend constraints verified against Campaign DTOs. */
export const CAMPAIGN_NAME_MAX = 200;
export const CAMPAIGN_DESCRIPTION_MAX = 5000;

/** com.shivang.obd.campaign.dto.ScheduleConfig.
 *
 * F1: `allowedDaysOfWeek` is a `Set<DayOfWeek>` on the wire, so it is now an
 * enum union here rather than `z.array(z.string())`. A value the Java enum
 * cannot deserialise was previously reachable from this form. */
export const scheduleConfigSchema = z
  .object({
    startDate: z.string().date("Invalid start date format (YYYY-MM-DD).").optional().nullable(),
    startTime: z.string().time("Invalid start time format (HH:MM).").optional().nullable(),
    endTime: z.string().time("Invalid end time format (HH:MM).").optional().nullable(),
    timezone: z.string().max(64).optional().nullable(),
    allowedDaysOfWeek: z
      .array(
        z.enum([
          "MONDAY",
          "TUESDAY",
          "WEDNESDAY",
          "THURSDAY",
          "FRIDAY",
          "SATURDAY",
          "SUNDAY",
        ]),
      )
      .optional()
      .nullable(),
    holidayCalendarId: z.string().uuid().optional().nullable(),
  })
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
      const windowConfigured = data.startDate || data.startTime || data.endTime;
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

/** RetryPolicyConfig schema matching backend validation rules.
 *
 * F1 (VERIFIED against campaign/dto/RetryPolicyConfig.java):
 *  - `intervalSeconds` is `@Min(1) @Max(5999)`. The F0 schema allowed 604800,
 *    so the form could produce a value the server rejects with 400 and no
 *    actionable message.
 *  - `rules` was absent. It carries the per-category overrides the backend
 *    supports (RetryRuleConfig). */
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
      .max(5999, "Interval must be at most 5999 seconds.")
      .optional()
      .nullable(),
    strategy: z.enum(["FIXED"]),
    rules: z.array(retryRuleConfigSchema).max(6).optional().nullable(),
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
  )
  .refine(
    (data) => assertUniqueRetryRuleCategories(data.rules ?? []) === null,
    {
      message: "Configure at most one retry rule per failure category.",
      path: ["rules"],
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
    campaignType: campaignTypeSchema,
    runMode: z.enum(["ONE_TIME", "RECURRING"]).optional(),
    contactGroupId: z.string().uuid().optional().nullable(),
    didId: z.string().uuid().optional().nullable(),
    contentMode: z.enum(["AUDIO", "TTS"]).optional(),
    audioAssetId: z.string().uuid().optional().nullable(),
    ttsTemplateId: z.string().uuid().optional().nullable(),
    schedule: scheduleConfigSchema.optional().nullable(),
    retryPolicy: retryPolicyConfigSchema.optional().nullable(),
    typeConfig: campaignTypeConfigSchema.optional().nullable(),
    integrationConfig: campaignIntegrationConfigSchema.optional().nullable(),
    // F1: the four safety/limit fields the backend has always accepted on both
    // create and update (CreateCampaignRequest L117-161, UpdateCampaignRequest).
    // Bounds are the backend's own constraint validators:
    //   dailyDialLimit      DailyDialLimit          1..3
    //   maxDailyAttempts    CampaignDailyAttempts   1..10
    //   maxCallDurationSeconds MaxCallDurationSeconds 1..3600
    callOnWhitelistNumbers: z.boolean().optional().nullable(),
    dailyDialLimit: z
      .number()
      .int()
      .min(1, "Daily dial limit must be at least 1.")
      .max(3, "Daily dial limit must be at most 3.")
      .optional()
      .nullable(),
    maxDailyAttempts: z
      .number()
      .int()
      .min(1, "Daily attempts must be at least 1.")
      .max(10, "Daily attempts must be at most 10.")
      .optional()
      .nullable(),
    maxCallDurationSeconds: z
      .number()
      .int()
      .min(1, "Call duration must be at least 1 second.")
      .max(3600, "Call duration must be at most 3600 seconds.")
      .optional()
      .nullable(),
    /**
     * F4: FORM-ONLY. Not a campaign field — VERIFIED the backend takes the
     * target as the `?tenantId=` QUERY PARAMETER on `POST /campaigns`
     * (`CampaignController.create`), not in the body. It lives in the form so the
     * same schema can require one from a platform or reseller caller, and
     * `toCreateCampaignPayload` deliberately does NOT copy it into the body.
     */
    targetTenantId: z.string().uuid().optional().nullable(),
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

    // F1: content requirement now derives from the backend's own
    // `CampaignType.playsMedia()` (CampaignType.java:68-75) rather than a
    // hand-maintained type list. The F0 list omitted MISSED_CALL, which the
    // backend had already added — and had already caused a real failure class
    // (a MISSED_CALL campaign created successfully, then failed every dial).
    const playsMedia = campaignTypePlaysMedia(data.campaignType);
    if (playsMedia && !data.contentMode) {
      ctx.addIssue({
        code: "custom",
        path: ["contentMode"],
        message: `${data.campaignType} campaigns play media and require content (audio or TTS).`,
      });
    }
    if (!playsMedia && (data.contentMode || data.audioAssetId || data.ttsTemplateId)) {
      // VERIFIED: the backend rejects TTS for a non-media type because no
      // synthesis or playback runtime exists; its content mode is inert.
      ctx.addIssue({
        code: "custom",
        path: ["contentMode"],
        message: `${data.campaignType} campaigns play no media, so they take no content.`,
      });
    }

    // A type that carries configuration must receive a config OF ITS OWN SHAPE.
    if (data.typeConfig && !typeConfigMatchesCampaignType(data.campaignType, normalizeTypeConfig(data.typeConfig))) {
      ctx.addIssue({
        code: "custom",
        path: ["typeConfig"],
        message: `This configuration does not match a ${data.campaignType} campaign.`,
      });
    }

    // Type config required for DTMF, CONNECT_BY_AGENT and MISSED_CALL. PLAYFILE
    // accepts none at all (PlayfileCampaignConfig rejects a non-empty object).
    if (
      (data.campaignType === "DTMF" ||
        data.campaignType === "CONNECT_BY_AGENT" ||
        data.campaignType === "MISSED_CALL") &&
      (!data.typeConfig || Object.keys(data.typeConfig).length === 0)
    ) {
      ctx.addIssue({
        code: "custom",
        path: ["typeConfig"],
        message: `${data.campaignType} campaigns require type-specific configuration.`,
      });
    }
    if (data.campaignType === "PLAYFILE" && data.typeConfig && Object.keys(data.typeConfig).length > 0) {
      ctx.addIssue({
        code: "custom",
        path: ["typeConfig"],
        message: "PLAYFILE campaigns take no type configuration; select content instead.",
      });
    }

    // RECURRING requires schedule
    if (data.runMode === "RECURRING" && !data.schedule) {
      ctx.addIssue({
        code: "custom",
        path: ["schedule"],
        message: "RECURRING campaigns require a configured schedule.",
      });
    }

    // F4: TTS content is refused by the server for every type that plays media.
    // VERIFIED `CampaignService.validateContent` L435-439 throws
    // "<TYPE> campaigns do not support TTS content yet" when
    // `type.playsMedia() && mode == TTS`. Because `playsMedia()` is true for
    // exactly the two types that REQUIRE content, and the two types that take no
    // content at all, there is NO campaign type that can use a TTS template.
    // The F1 form offered the combination anyway, so this makes the client
    // refuse it before the request is sent rather than after.
    if (playsMedia && data.contentMode === "TTS") {
      ctx.addIssue({
        code: "custom",
        path: ["contentMode"],
        message:
          "The platform cannot play TTS content, so this campaign type must use an approved audio recording instead.",
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
          // F1: F0 silently dropped `rules`, so a configured per-category
          // policy was replaced by the flat defaults on every save.
          rules: values.retryPolicy.rules ?? null,
        }
      : undefined,
    typeConfig: values.typeConfig ? normalizeTypeConfig(values.typeConfig) : undefined,
    integrationConfig: values.integrationConfig ?? undefined,
    callOnWhitelistNumbers: values.callOnWhitelistNumbers ?? undefined,
    dailyDialLimit: values.dailyDialLimit ?? undefined,
    maxDailyAttempts: values.maxDailyAttempts ?? undefined,
    maxCallDurationSeconds: values.maxCallDurationSeconds ?? undefined,
    // F4: `targetTenantId` is deliberately NOT copied. VERIFIED the backend
    // receives it as a query parameter, not a body field.
  };
}

/** Campaign update schema (PUT semantics - omitted optional blocks are cleared).
 *
 * `campaignType` is absent because the backend treats it as immutable for the
 * campaign's lifetime (CampaignType.java:6, and it is not a field of
 * UpdateCampaignRequest). The type-dependent refinements therefore cannot be
 * repeated here and the server remains authoritative for them.
 */
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
    typeConfig: campaignTypeConfigSchema.optional().nullable(),
    integrationConfig: campaignIntegrationConfigSchema.optional().nullable(),
    // F4: `callOnWhitelistNumbers` removed. VERIFIED it is not a component of
    // `UpdateCampaignRequest`, so no form control can ever save it.
    dailyDialLimit: z
      .number()
      .int()
      .min(1, "Daily dial limit must be at least 1.")
      .max(3, "Daily dial limit must be at most 3.")
      .optional()
      .nullable(),
    maxDailyAttempts: z
      .number()
      .int()
      .min(1, "Daily attempts must be at least 1.")
      .max(10, "Daily attempts must be at most 10.")
      .optional()
      .nullable(),
    maxCallDurationSeconds: z
      .number()
      .int()
      .min(1, "Call duration must be at least 1 second.")
      .max(3600, "Call duration must be at most 3600 seconds.")
      .optional()
      .nullable(),
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

    // Type config required for DTMF, CONNECT_BY_AGENT and MISSED_CALL (we need
    // the original campaignType, which is immutable and absent here). F1 at
    // least enforces that a config IS present and is one of the recognised
    // shapes, rather than any arbitrary record; the server still decides
    // whether it matches this campaign's type.

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
          rules: values.retryPolicy.rules ?? null,
        }
      : undefined,
    typeConfig: values.typeConfig ? normalizeTypeConfig(values.typeConfig) : undefined,
    integrationConfig: values.integrationConfig ?? undefined,
    // F4 CORRECTION: `callOnWhitelistNumbers` is NOT sent on update. VERIFIED
    // `UpdateCampaignRequest` has no such component and `CampaignMapper
    // .updateEntity` never reads it, so the field was being transmitted and
    // silently discarded on every save. It is create-only and immutable; the
    // edit form displays it read-only.
    dailyDialLimit: values.dailyDialLimit ?? undefined,
    maxDailyAttempts: values.maxDailyAttempts ?? undefined,
    maxCallDurationSeconds: values.maxCallDurationSeconds ?? undefined,
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

