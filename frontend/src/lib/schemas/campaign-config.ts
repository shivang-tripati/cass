import { z } from "zod";

import {
  CONFIGURABLE_RETRY_CATEGORIES,
  type CampaignTypeConfig,
  type RetryRuleConfig,
} from "@/lib/api/contracts";

/**
 * Zod schemas for the polymorphic campaign configuration payloads.
 *
 * F1 rationale. `typeConfig` was `Record<string, unknown>` in the frontend. That
 * was not merely imprecise, it was UNSAFE: every backend parser calls
 * `rejectUnknownFields` and rejects a wrong key with 400, so a loose record let
 * a user compose a payload that could only ever fail. These schemas mirror the
 * backend records exactly.
 *
 * VERIFIED against:
 *  - campaign/config/MissedCallCampaignConfig.java + MissedCallRingWindow
 *  - campaign/config/ConnectByAgentCampaignConfig.java + voice/agent/AgentRingWindow
 *  - campaign/config/DtmfCampaignConfig.java + voice/dtmf/DtmfConfig
 *  - campaign/config/PlayfileCampaignConfig.java
 *  - campaign/CampaignType.java (the type list itself)
 *
 * `IvrCampaignConfig` is intentionally NOT schematised. Its inner object is a
 * frozen execution snapshot, and it can only be produced by
 * POST /api/v1/campaigns/{id}/ivr-tree, which is currently blocked by the
 * missing IVR capability seed. See the F1 doc §8. `campaignTypeConfigSchema`
 * therefore accepts a passthrough `ivr` object rather than pretending to know
 * the snapshot shape.
 */

/** com.shivang.obd.campaign.CampaignType — four constants, not three. */
export const campaignTypeSchema = z.enum([
  "PLAYFILE",
  "DTMF",
  "CONNECT_BY_AGENT",
  "MISSED_CALL",
]);
export type CampaignTypeValues = z.infer<typeof campaignTypeSchema>;

/**
 * `playsMedia()` — VERIFIED as CampaignType.java:68-75. The backend derives
 * BOTH "content is required" and "TTS is rejected" from this single property,
 * and the switch has no `default` arm, so adding a backend type fails the
 * backend build until its capability is stated.
 *
 * The F0 schema reimplemented this as a per-type membership list, which is the
 * fail-open pattern the backend comment (CampaignType.java:41-48) says it
 * replaced. Mirroring the property keeps the client and server derived from the
 * same fact.
 */
export function campaignTypePlaysMedia(type: CampaignTypeValues): boolean {
  return type === "PLAYFILE" || type === "DTMF";
}

/* ------------------------------ type configs ------------------------------ */

/** MissedCallRingWindow: MIN 10, MAX 60 seconds, default 30. */
export const MISSED_CALL_RING_SECONDS_MIN = 10;
export const MISSED_CALL_RING_SECONDS_MAX = 60;
export const MISSED_CALL_RING_SECONDS_DEFAULT = 30;

/**
 * `.strict()` on the inner objects is REQUIRED, not stylistic.
 *
 * Every backend parser calls `rejectUnknownFields` and returns 400 for a field
 * it does not know — `MissedCallCampaignConfig.rejectUnknownFields`,
 * `ConnectByAgentCampaignConfig.rejectUnknownFields`, `WebhookConfig.rejectUnknownFields`
 * all switch on a fixed key set and throw otherwise. Zod objects strip unknown
 * keys by default, which would silently DROP a field the user typed instead of
 * telling them it is not supported.
 */
export const missedCallTypeConfigSchema = z.object({
  missedCall: z
    .object({
      ringDurationSeconds: z
        .number()
        .int()
        .min(
          MISSED_CALL_RING_SECONDS_MIN,
          `Ring duration must be at least ${MISSED_CALL_RING_SECONDS_MIN} seconds.`,
        )
        .max(
          MISSED_CALL_RING_SECONDS_MAX,
          `Ring duration must be at most ${MISSED_CALL_RING_SECONDS_MAX} seconds.`,
        ),
    })
    .strict(),
});

/** AgentRingWindow: MIN 10, MAX 240 seconds, default 60. */
export const CONNECT_BY_AGENT_RING_SECONDS_MIN = 10;
export const CONNECT_BY_AGENT_RING_SECONDS_MAX = 240;

export const connectByAgentTypeConfigSchema = z.object({
  connectByAgent: z
    .object({
      queueId: z.string().uuid("Select a queue owned by this tenant."),
      // VERIFIED: AgentSelectionStrategy has exactly one constant.
      selectionStrategy: z.literal("LEAST_ACTIVE_RESERVATIONS"),
      ringDurationSeconds: z
        .number()
        .int()
        .min(
          CONNECT_BY_AGENT_RING_SECONDS_MIN,
          `Ring duration must be at least ${CONNECT_BY_AGENT_RING_SECONDS_MIN} seconds.`,
        )
        .max(
          CONNECT_BY_AGENT_RING_SECONDS_MAX,
          `Ring duration must be at most ${CONNECT_BY_AGENT_RING_SECONDS_MAX} seconds.`,
        ),
    })
    .strict(),
});

/** DtmfConfig: expected is a required digit sequence; MAX_SEQUENCE_LENGTH 16;
 * timeoutSecs 1..120 with default 10; DtmfActions has two constants. */
export const DTMF_MAX_SEQUENCE_LENGTH = 16;
export const DTMF_TIMEOUT_SECONDS_MIN = 1;
export const DTMF_TIMEOUT_SECONDS_MAX = 120;
export const DTMF_TIMEOUT_SECONDS_DEFAULT = 10;

export const dtmfTypeConfigSchema = z.object({
  // NOT .strict(): `maxDigits`, `terminator` and `timeoutSecs` are all optional
  // in DtmfConfig.fromTypeConfig and defaulted server-side, so a partial object
  // is valid input. DtmfConfig is the one typeConfig parser that tolerates
  // extra/absent fields rather than calling rejectUnknownFields.
  dtmf: z.object({
    expected: z
      .string()
      .trim()
      .min(1, "Expected digit sequence is required.")
      .max(
        DTMF_MAX_SEQUENCE_LENGTH,
        `Expected sequence must be at most ${DTMF_MAX_SEQUENCE_LENGTH} digits.`,
      )
      .regex(
        /^[0-9*#]+$/,
        "Only DTMF digits and * or # are supported.",
      ),
    maxDigits: z
      .number()
      .int()
      .min(1)
      .max(DTMF_MAX_SEQUENCE_LENGTH)
      .optional(),
    terminator: z
      .string()
      .trim()
      .regex(/^[0-9*#]$/, "Terminator must be a single DTMF key.")
      .optional()
      .nullable(),
    timeoutSecs: z
      .number()
      .int()
      .min(DTMF_TIMEOUT_SECONDS_MIN)
      .max(DTMF_TIMEOUT_SECONDS_MAX)
      .optional(),
    action: z.enum(["TERMINATE", "CONNECT_BY_AGENT"]),
  }),
});

/** The IVR variant. Deliberately opaque — see the module comment. */
export const ivrTypeConfigSchema = z.object({
  ivr: z.object({ treeId: z.string().uuid() }).loose(),
});

/**
 * PLAYFILE. VERIFIED: `PlayfileCampaignConfig.toJson()` emits an EMPTY object
 * and `fromTypeConfig` REJECTS a non-empty one. So this accepts only `{}` (or
 * null/absent), which is the only shape the backend accepts.
 */
export const playfileTypeConfigSchema = z.object({}).strict();

/**
 * The polymorphic `typeConfig`.
 *
 * `z.union` over the per-type shapes rather than a discriminated union on a
 * literal tag, because the wire format has NO discriminator field: the outer
 * key (`missedCall`, `connectByAgent`, `dtmf`, `ivr`) IS the discriminator, and
 * PLAYFILE has no key at all. F4 adds a `superRefine` that ties the chosen
 * shape to the submitted `campaignType`, which is a validation rule rather than
 * a type.
 */
export const campaignTypeConfigSchema = z.union([
  playfileTypeConfigSchema,
  missedCallTypeConfigSchema,
  connectByAgentTypeConfigSchema,
  dtmfTypeConfigSchema,
  ivrTypeConfigSchema,
]);

/* ---------------------------- integration config --------------------------- */

/** WebhookEndpointValidator requires an absolute http(s) URL with no embedded
 * credentials. VERIFIED: a configured webhook records intent only — no
 * transport, signing, retry or delivery exists. */
export const webhookConfigSchema = z
  .object({
    // .strict() mirrors WebhookConfig.rejectUnknownFields, which names
    // enabled/endpoint/events and throws for anything else.
    enabled: z.boolean(),
    endpoint: z
      .string()
      .trim()
      .url("Endpoint must be an absolute URL.")
      .refine(
        (value) => {
          try {
            const url = new URL(value);
            return (
              (url.protocol === "http:" || url.protocol === "https:") &&
              url.username === "" &&
              url.password === ""
            );
          } catch {
            return false;
          }
        },
        {
          message:
            "Endpoint must be an http(s) URL and must not embed credentials.",
        },
      )
      .optional()
      .nullable(),
    events: z
      .array(
        z.enum([
          "campaign.attempt.completed",
          "campaign.attempt.failed",
          "campaign.attempt.cancelled",
        ]),
      )
      .max(3, "Select each event at most once."),
  })
  .superRefine((data, ctx) => {
    // VERIFIED (WebhookConfig canonical constructor): an endpoint alone never
    // enables a webhook. `enabled` is the only switch, and when true the
    // endpoint and at least one event are both required.
    if (data.enabled) {
      if (!data.endpoint) {
        ctx.addIssue({
          code: "custom",
          path: ["endpoint"],
          message: "An endpoint is required when the webhook is enabled.",
        });
      }
      if (!data.events || data.events.length === 0) {
        ctx.addIssue({
          code: "custom",
          path: ["events"],
          message: "Select at least one event when the webhook is enabled.",
        });
      }
    }
    const seen = new Set(data.events ?? []);
    if (seen.size !== (data.events?.length ?? 0)) {
      ctx.addIssue({
        code: "custom",
        path: ["events"],
        message: "Select each event at most once.",
      });
    }
  });

/** VERIFIED (ReportPrivacyConfig): `policy` is required inside the block, and
 * FULL is the default. MASKED is a description of what a future reporting
 * subsystem should show; it does not change the attempt-listing APIs. */
export const reportPrivacyConfigSchema = z.object({
  policy: z.enum(["FULL", "MASKED"]),
});

export const campaignIntegrationConfigSchema = z.object({
  webhook: webhookConfigSchema.optional().nullable(),
  reportPrivacy: reportPrivacyConfigSchema.optional().nullable(),
});

/* ------------------------------ retry rules ------------------------------- */

/** VERIFIED (RetryDelay.MM_SS): minutes 00-99, seconds 00-59. */
export const RETRY_DELAY_PATTERN = /^([0-9]{2}):([0-9]{2})$/;
export const RETRY_RULE_MAX_RETRIES = 10;

export const retryRuleConfigSchema = z
  .object({
    category: z.enum([
      "NO_ANSWER",
      "BUSY",
      "HANGUP",
      "FAILED",
      "SWITCHED_OFF",
      "NOT_REACHABLE",
    ]),
    enabled: z.boolean().optional().nullable(),
    maxRetries: z
      .number()
      .int()
      .min(0, "Retries cannot be negative.")
      .max(RETRY_RULE_MAX_RETRIES, `At most ${RETRY_RULE_MAX_RETRIES} retries.`),
    retryDelay: z
      .string()
      .regex(RETRY_DELAY_PATTERN, 'Use the MM:SS format, for example "05:00".')
      .optional()
      .nullable(),
  })
  .superRefine((data, ctx) => {
    // VERIFIED (RetryRuleConfig): retryDelay is required only when the rule is
    // enabled AND permits at least one retry. A delay on a rule that can never
    // fire is refused by the backend.
    if (data.enabled && data.maxRetries > 0 && !data.retryDelay) {
      ctx.addIssue({
        code: "custom",
        path: ["retryDelay"],
        message: "A retry delay is required for an enabled rule.",
      });
    }
  });

/** VERIFIED (RetryPolicyValidator): the category keys are unique across rules. */
export function assertUniqueRetryRuleCategories(
  rules: readonly RetryRuleConfig[],
): string | null {
  const seen = new Set<string>();
  for (const rule of rules) {
    if (seen.has(rule.category)) {
      return `Duplicate retry rule for ${rule.category}. Configure at most one rule per category.`;
    }
    seen.add(rule.category);
  }
  return null;
}

/** Narrowing helper: is this a config for the given campaign type? */
export function typeConfigMatchesCampaignType(
  type: CampaignTypeValues,
  config: CampaignTypeConfig,
): boolean {
  switch (type) {
    case "PLAYFILE":
      return Object.keys(config).length === 0;
    case "DTMF":
      return "dtmf" in config || "ivr" in config;
    case "CONNECT_BY_AGENT":
      return "connectByAgent" in config;
    case "MISSED_CALL":
      return "missedCall" in config;
    default:
      return false;
  }
}

/**
 * Fills the DTMF defaults the backend applies on read.
 *
 * VERIFIED asymmetry in `DtmfConfig.fromTypeConfig`: `maxDigits` defaults to
 * `expected.length()` and `timeoutSecs` to 10 when absent from the request, but
 * `DtmfCampaignConfig.toJson()` always WRITES both. So the wire response shape
 * has them required while the request may omit them. The schema therefore
 * accepts them as optional and this normaliser produces the complete object the
 * contract type describes, keeping request and response honest about the same
 * type.
 */
/**
 * The shape a DTMF config may have in FORM INPUT, before defaults are applied.
 *
 * `maxDigits` and `timeoutSecs` are optional here but required in
 * `DtmfTypeConfig`, because the backend defaults them on read and always
 * writes them back out. This type is the honest input shape.
 */
export type DtmfTypeConfigInput = z.input<typeof dtmfTypeConfigSchema>;

/** Accepts either a validated or a not-yet-defaulted config. */
export function normalizeTypeConfig(
  config: CampaignTypeConfig | z.input<typeof campaignTypeConfigSchema>,
): CampaignTypeConfig {
  if (!("dtmf" in config)) return config as CampaignTypeConfig;
  const { dtmf } = config as DtmfTypeConfigInput;
  return {
    dtmf: {
      expected: dtmf.expected,
      maxDigits: dtmf.maxDigits ?? dtmf.expected.length,
      timeoutSecs: dtmf.timeoutSecs ?? DTMF_TIMEOUT_SECONDS_DEFAULT,
      terminator: dtmf.terminator ?? null,
      action: dtmf.action,
    },
  };
}

export { CONFIGURABLE_RETRY_CATEGORIES };
