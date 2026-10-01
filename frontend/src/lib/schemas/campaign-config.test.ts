import { describe, expect, it } from "vitest";

import {
  connectByAgentTypeConfigSchema,
  campaignTypeConfigSchema,
  campaignTypePlaysMedia,
  missedCallTypeConfigSchema,
  normalizeTypeConfig,
  playfileTypeConfigSchema,
  retryRuleConfigSchema,
  assertUniqueRetryRuleCategories,
  typeConfigMatchesCampaignType,
  webhookConfigSchema,
} from "@/lib/schemas/campaign-config";

/**
 * F1 — campaign configuration validators.
 *
 * These encode backend rules that F0 could not express at all, because the
 * payload was `Record<string, unknown>`:
 *  - `CampaignType` has FOUR values, including MISSED_CALL;
 *  - `playsMedia()` drives the content rule, exhaustively;
 *  - every typeConfig parser calls `rejectUnknownFields`, so a loose record
 *    was not merely imprecise — it was unsafe;
 *  - PLAYFILE rejects a non-empty config outright.
 */

describe("campaignTypePlaysMedia", () => {
  it("matches the backend's exhaustive switch (CampaignType.java:68-75)", () => {
    expect(campaignTypePlaysMedia("PLAYFILE")).toBe(true);
    expect(campaignTypePlaysMedia("DTMF")).toBe(true);
    expect(campaignTypePlaysMedia("CONNECT_BY_AGENT")).toBe(false);
    expect(campaignTypePlaysMedia("MISSED_CALL")).toBe(false);
  });
});

describe("missedCallTypeConfigSchema", () => {
  it("accepts a ring duration inside 10..60", () => {
    expect(
      missedCallTypeConfigSchema.parse({ missedCall: { ringDurationSeconds: 30 } }),
    ).toEqual({ missedCall: { ringDurationSeconds: 30 } });
    expect(
      missedCallTypeConfigSchema.safeParse({ missedCall: { ringDurationSeconds: 10 } }).success,
    ).toBe(true);
    expect(
      missedCallTypeConfigSchema.safeParse({ missedCall: { ringDurationSeconds: 60 } }).success,
    ).toBe(true);
  });

  it("rejects a duration outside the backend's MissedCallRingWindow bounds", () => {
    expect(
      missedCallTypeConfigSchema.safeParse({ missedCall: { ringDurationSeconds: 9 } }).success,
    ).toBe(false);
    expect(
      missedCallTypeConfigSchema.safeParse({ missedCall: { ringDurationSeconds: 61 } }).success,
    ).toBe(false);
  });

  it("rejects unknown fields, matching rejectUnknownFields", () => {
    expect(
      missedCallTypeConfigSchema.safeParse({
        missedCall: { ringDurationSeconds: 30, extra: true },
      }).success,
    ).toBe(false);
  });
});

describe("connectByAgentTypeConfigSchema", () => {
  const valid = {
    connectByAgent: {
      queueId: "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
      selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
      ringDurationSeconds: 60,
    },
  };

  it("accepts the only shape the backend supports", () => {
    expect(connectByAgentTypeConfigSchema.safeParse(valid).success).toBe(true);
  });

  it("enforces the 10..240 ring window", () => {
    expect(
      connectByAgentTypeConfigSchema.safeParse({
        connectByAgent: { ...valid.connectByAgent, ringDurationSeconds: 9 },
      }).success,
    ).toBe(false);
    expect(
      connectByAgentTypeConfigSchema.safeParse({
        connectByAgent: { ...valid.connectByAgent, ringDurationSeconds: 241 },
      }).success,
    ).toBe(false);
  });

  it("rejects any selection strategy but LEAST_ACTIVE_RESERVATIONS", () => {
    expect(
      connectByAgentTypeConfigSchema.safeParse({
        connectByAgent: { ...valid.connectByAgent, selectionStrategy: "ROUND_ROBIN" },
      }).success,
    ).toBe(false);
  });

  it("requires a queue id", () => {
    expect(
      connectByAgentTypeConfigSchema.safeParse({
        connectByAgent: { ...valid.connectByAgent, queueId: "not-a-uuid" },
      }).success,
    ).toBe(false);
  });
});

describe("playfileTypeConfigSchema", () => {
  it("accepts only the empty object", () => {
    // VERIFIED: PlayfileCampaignConfig.toJson() emits {} and fromTypeConfig
    // rejects anything non-empty.
    expect(playfileTypeConfigSchema.safeParse({}).success).toBe(true);
    expect(playfileTypeConfigSchema.safeParse({ anything: 1 }).success).toBe(false);
  });
});

describe("campaignTypeConfigSchema", () => {
  it("accepts each supported variant", () => {
    expect(
      campaignTypeConfigSchema.safeParse({ missedCall: { ringDurationSeconds: 30 } }).success,
    ).toBe(true);
    expect(
      campaignTypeConfigSchema.safeParse({
        connectByAgent: {
          queueId: "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      }).success,
    ).toBe(true);
    expect(
      campaignTypeConfigSchema.safeParse({
        dtmf: { expected: "1", action: "TERMINATE" },
      }).success,
    ).toBe(true);
    expect(campaignTypeConfigSchema.safeParse({}).success).toBe(true);
  });
});

describe("typeConfigMatchesCampaignType", () => {
  it("pairs each type with its own shape", () => {
    expect(typeConfigMatchesCampaignType("MISSED_CALL", { missedCall: { ringDurationSeconds: 30 } })).toBe(true);
    expect(
      typeConfigMatchesCampaignType("CONNECT_BY_AGENT", {
        connectByAgent: {
          queueId: "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      }),
    ).toBe(true);
    expect(typeConfigMatchesCampaignType("PLAYFILE", {})).toBe(true);
  });

  it("rejects a config that belongs to a different type", () => {
    expect(typeConfigMatchesCampaignType("PLAYFILE", { missedCall: { ringDurationSeconds: 30 } })).toBe(false);
    expect(typeConfigMatchesCampaignType("MISSED_CALL", {})).toBe(false);
  });
});

describe("normalizeTypeConfig", () => {
  it("applies the DTMF defaults the backend applies on read", () => {
    // VERIFIED: maxDigits defaults to expected.length(), timeoutSecs to 10.
    const result = normalizeTypeConfig({
      dtmf: { expected: "123", action: "TERMINATE" },
    });
    expect(result).toEqual({
      dtmf: { expected: "123", maxDigits: 3, timeoutSecs: 10, terminator: null, action: "TERMINATE" },
    });
  });

  it("preserves explicit values", () => {
    const result = normalizeTypeConfig({
      dtmf: { expected: "12", maxDigits: 4, timeoutSecs: 30, terminator: "#", action: "TERMINATE" },
    });
    expect(result).toEqual({
      dtmf: { expected: "12", maxDigits: 4, timeoutSecs: 30, terminator: "#", action: "TERMINATE" },
    });
  });

  it("passes a non-DTMF config through untouched", () => {
    const config = { missedCall: { ringDurationSeconds: 45 } };
    expect(normalizeTypeConfig(config)).toBe(config);
  });
});

describe("webhookConfigSchema", () => {
  it("requires an endpoint and at least one event when enabled", () => {
    // VERIFIED: WebhookConfig's canonical constructor requires both when
    // enabled, and an endpoint alone never enables a webhook.
    expect(webhookConfigSchema.safeParse({ enabled: true, events: [] }).success).toBe(false);
    expect(
      webhookConfigSchema.safeParse({ enabled: true, endpoint: "https://x.test/h" }).success,
    ).toBe(false);
    expect(
      webhookConfigSchema.safeParse({
        enabled: true,
        endpoint: "https://x.test/h",
        events: ["campaign.attempt.completed"],
      }).success,
    ).toBe(true);
  });

  it("allows a disabled webhook with no endpoint", () => {
    expect(webhookConfigSchema.safeParse({ enabled: false, events: [] }).success).toBe(true);
  });

  it("rejects a non-http endpoint and an endpoint with embedded credentials", () => {
    expect(
      webhookConfigSchema.safeParse({
        enabled: true,
        endpoint: "ftp://x.test/h",
        events: ["campaign.attempt.completed"],
      }).success,
    ).toBe(false);
    expect(
      webhookConfigSchema.safeParse({
        enabled: true,
        endpoint: "https://user:pass@x.test/h",
        events: ["campaign.attempt.completed"],
      }).success,
    ).toBe(false);
  });

  it("rejects an event outside the public vocabulary", () => {
    expect(
      webhookConfigSchema.safeParse({
        enabled: true,
        endpoint: "https://x.test/h",
        events: ["internal.domain.event"],
      }).success,
    ).toBe(false);
  });

  it("rejects a duplicated event", () => {
    expect(
      webhookConfigSchema.safeParse({
        enabled: true,
        endpoint: "https://x.test/h",
        events: ["campaign.attempt.completed", "campaign.attempt.completed"],
      }).success,
    ).toBe(false);
  });
});

describe("retryRuleConfigSchema", () => {
  it("requires a delay only for an enabled rule that permits a retry", () => {
    expect(
      retryRuleConfigSchema.safeParse({ category: "NO_ANSWER", enabled: true, maxRetries: 2 }).success,
    ).toBe(false);
    expect(
      retryRuleConfigSchema.safeParse({
        category: "NO_ANSWER",
        enabled: true,
        maxRetries: 2,
        retryDelay: "05:00",
      }).success,
    ).toBe(true);
    expect(
      retryRuleConfigSchema.safeParse({ category: "NO_ANSWER", enabled: false, maxRetries: 0 }).success,
    ).toBe(true);
  });

  it("enforces the MM:SS delay format", () => {
    expect(
      retryRuleConfigSchema.safeParse({
        category: "BUSY",
        enabled: true,
        maxRetries: 1,
        retryDelay: "5:00",
      }).success,
    ).toBe(false);
  });

  it("caps retries at 10", () => {
    expect(
      retryRuleConfigSchema.safeParse({
        category: "BUSY",
        enabled: true,
        maxRetries: 11,
        retryDelay: "05:00",
      }).success,
    ).toBe(false);
  });
});

describe("assertUniqueRetryRuleCategories", () => {
  it("accepts one rule per category", () => {
    expect(
      assertUniqueRetryRuleCategories([
        { category: "NO_ANSWER", maxRetries: 1 },
        { category: "BUSY", maxRetries: 1 },
      ]),
    ).toBeNull();
  });

  it("rejects a duplicated category, matching the backend", () => {
    expect(
      assertUniqueRetryRuleCategories([
        { category: "NO_ANSWER", maxRetries: 1 },
        { category: "NO_ANSWER", maxRetries: 2 },
      ]),
    ).toContain("NO_ANSWER");
  });
});
