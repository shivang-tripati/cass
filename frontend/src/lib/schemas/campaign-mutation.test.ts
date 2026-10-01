import { describe, expect, it } from "vitest";

import {
  createCampaignSchema,
  scheduleConfigSchema,
  toCreateCampaignPayload,
  toUpdateCampaignPayload,
  updateCampaignSchema,
} from "@/lib/schemas/campaign-mutation";
import { campaignTypePlaysMedia } from "@/lib/schemas/campaign-config";

/**
 * F4 — contract tests for the campaign create/update payloads.
 *
 * ## The finding these tests exist for
 *
 * TTS content is refused by the server for every campaign type that can have
 * content. VERIFIED `CampaignService.validateContent` L435-439:
 *
 * ```java
 * if (type.playsMedia() && mode == ContentMode.TTS) {
 *     throw business(type + " campaigns do not support TTS content yet; …");
 * }
 * ```
 *
 * `playsMedia()` is true for PLAYFILE and DTMF — which are also the only two
 * types that REQUIRE content — while CONNECT_BY_AGENT and MISSED_CALL play no
 * media and take no content at all. So no campaign type can use a TTS template.
 *
 * The F1 create and edit dialogs both offered a working TTS content mode with a
 * template picker behind it, and every submission of that combination returned
 * 400. The schema now refuses it before the request is sent, and these tests
 * hold that line.
 */

/** A minimal valid PLAYFILE create, used as the base for mutations. */
const VALID_PLAYFILE = {
  name: "October reminder",
  campaignType: "PLAYFILE",
  contentMode: "AUDIO",
  audioAssetId: "33333333-3333-4333-8333-333333333333",
} as const;

describe("createCampaignSchema — the TTS impossibility", () => {
  it("accepts AUDIO content for a media-playing type", () => {
    const result = createCampaignSchema.safeParse(VALID_PLAYFILE);
    expect(result.success, JSON.stringify(result.success ? "" : result.error.issues)).toBe(
      true,
    );
  });

  it("REFUSES TTS content for PLAYFILE — the server returns 400", () => {
    const result = createCampaignSchema.safeParse({
      name: "Reminder",
      campaignType: "PLAYFILE",
      contentMode: "TTS",
      ttsTemplateId: "44444444-4444-4444-8444-444444444444",
    });
    expect(result.success).toBe(false);
    if (result.success) return;
    const messages = result.error.issues.map((issue) => issue.message).join(" ");
    expect(messages).toMatch(/cannot play TTS|TTS content/i);
  });

  it("REFUSES TTS content for DTMF too, not just PLAYFILE", () => {
    // The F1 guard was `type == PLAYFILE && mode == TTS` — a per-type deny that
    // protected one type and left DTMF exposed. VERIFIED the backend's rule is
    // capability-shaped (`playsMedia()`), so it covers both.
    const result = createCampaignSchema.safeParse({
      name: "Press one",
      campaignType: "DTMF",
      contentMode: "TTS",
      ttsTemplateId: "44444444-4444-4444-8444-444444444444",
      typeConfig: { dtmf: { expected: "1", action: "TERMINATE" } },
    });
    expect(result.success).toBe(false);
  });

  it("refuses ANY content for a non-media type", () => {
    // VERIFIED: CONNECT_BY_AGENT and MISSED_CALL take no content; their content
    // mode is "inert, not broken" per the backend comment.
    for (const campaignType of ["CONNECT_BY_AGENT", "MISSED_CALL"] as const) {
      const withAudio = createCampaignSchema.safeParse({
        name: "No content",
        campaignType,
        audioAssetId: "33333333-3333-4333-8333-333333333333",
      });
      expect(withAudio.success, campaignType).toBe(false);

      const withTts = createCampaignSchema.safeParse({
        name: "No content",
        campaignType,
        ttsTemplateId: "44444444-4444-4444-8444-444444444444",
      });
      expect(withTts.success, campaignType).toBe(false);
    }
  });

  it("requires content for a media-playing type", () => {
    const result = createCampaignSchema.safeParse({
      name: "No content",
      campaignType: "PLAYFILE",
    });
    expect(result.success).toBe(false);
  });

  it("requires exactly one reference, never two", () => {
    const both = createCampaignSchema.safeParse({
      ...VALID_PLAYFILE,
      ttsTemplateId: "44444444-4444-4444-8444-444444444444",
    });
    expect(both.success).toBe(false);
  });
});

describe("createCampaignSchema — per-type typeConfig", () => {
  it("requires a typeConfig for MISSED_CALL, and accepts a valid one", () => {
    const missing = createCampaignSchema.safeParse({
      name: "Ring out",
      campaignType: "MISSED_CALL",
    });
    expect(missing.success).toBe(false);

    const valid = createCampaignSchema.safeParse({
      name: "Ring out",
      campaignType: "MISSED_CALL",
      typeConfig: { missedCall: { ringDurationSeconds: 30 } },
    });
    expect(
      valid.success,
      JSON.stringify(valid.success ? "" : valid.error.issues),
    ).toBe(true);
  });

  it("rejects a MISSED_CALL ring duration outside 10..60", () => {
    // VERIFIED `MissedCallRingWindow` MIN_RING_SECONDS 10, MAX_RING_SECONDS 60.
    for (const seconds of [9, 61, 0, -1]) {
      const result = createCampaignSchema.safeParse({
        name: "Ring out",
        campaignType: "MISSED_CALL",
        typeConfig: { missedCall: { ringDurationSeconds: seconds } },
      });
      expect(result.success, `${seconds}s`).toBe(false);
    }
  });

  it("requires a typeConfig for CONNECT_BY_AGENT with a real queue id", () => {
    const valid = createCampaignSchema.safeParse({
      name: "Route to an agent",
      campaignType: "CONNECT_BY_AGENT",
      typeConfig: {
        connectByAgent: {
          queueId: "55555555-5555-4555-8555-555555555555",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      },
    });
    expect(
      valid.success,
      JSON.stringify(valid.success ? "" : valid.error.issues),
    ).toBe(true);
  });

  it("rejects a CONNECT_BY_AGENT ring duration outside 10..240", () => {
    // VERIFIED `AgentRingWindow` MIN 10, MAX 240.
    for (const seconds of [9, 241]) {
      const result = createCampaignSchema.safeParse({
        name: "Route",
        campaignType: "CONNECT_BY_AGENT",
        typeConfig: {
          connectByAgent: {
            queueId: "55555555-5555-4555-8555-555555555555",
            selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
            ringDurationSeconds: seconds,
          },
        },
      });
      expect(result.success, `${seconds}s`).toBe(false);
    }
  });

  it("rejects a selection strategy that is not the only supported one", () => {
    // VERIFIED `AgentSelectionStrategy` has exactly one constant.
    const result = createCampaignSchema.safeParse({
      name: "Route",
      campaignType: "CONNECT_BY_AGENT",
      typeConfig: {
        connectByAgent: {
          queueId: "55555555-5555-4555-8555-555555555555",
          selectionStrategy: "ROUND_ROBIN",
          ringDurationSeconds: 60,
        },
      },
    });
    expect(result.success).toBe(false);
  });

  it("rejects a typeConfig whose shape belongs to a different type", () => {
    // The server's parsers all call rejectUnknownFields, so a mismatched key is a
    // 400. The client refuses it before sending.
    const result = createCampaignSchema.safeParse({
      name: "Ring out",
      campaignType: "MISSED_CALL",
      typeConfig: {
        connectByAgent: {
          queueId: "55555555-5555-4555-8555-555555555555",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      },
    });
    expect(result.success).toBe(false);
  });

  it("rejects any typeConfig for PLAYFILE — its only valid payload is {}", () => {
    // VERIFIED `PlayfileCampaignConfig.toJson()` emits `{}` and `fromTypeConfig`
    // rejects a non-empty object.
    const result = createCampaignSchema.safeParse({
      ...VALID_PLAYFILE,
      typeConfig: { dtmf: { expected: "1", action: "TERMINATE" } },
    });
    expect(result.success).toBe(false);
  });
});

describe("createCampaignSchema — run mode and schedule", () => {
  it("requires a schedule for RECURRING", () => {
    // VERIFIED `validateRunMode`.
    const result = createCampaignSchema.safeParse({
      ...VALID_PLAYFILE,
      runMode: "RECURRING",
    });
    expect(result.success).toBe(false);
  });

  it("accepts RECURRING with a schedule", () => {
    const result = createCampaignSchema.safeParse({
      ...VALID_PLAYFILE,
      runMode: "RECURRING",
      schedule: { timezone: "Asia/Kolkata" },
    });
    expect(
      result.success,
      JSON.stringify(result.success ? "" : result.error.issues),
    ).toBe(true);
  });
});

describe("scheduleConfigSchema", () => {
  it("requires a timezone once any window field is set", () => {
    // VERIFIED `validateScheduleWindow`: "Timezone is required when a schedule
    // window is configured."
    const result = scheduleConfigSchema.safeParse({ startTime: "09:00" });
    expect(result.success).toBe(false);
  });

  it("accepts a bare timezone with no window — the server allows it, readiness does not", () => {
    // Write-time validation passes this. `checkExecutionTimezone` will still
    // report SCHEDULE_TIMEZONE_REQUIRED only when the timezone is MISSING, so a
    // bare timezone is the minimum viable schedule. The UI states the readiness
    // requirement separately.
    const result = scheduleConfigSchema.safeParse({ timezone: "Asia/Kolkata" });
    expect(result.success).toBe(true);
  });

  it("has no end date — a schedule never expires", () => {
    // VB-8J: campaign scheduling has no final end date. A campaign becomes
    // eligible at its start date and stays eligible across future calling
    // windows until its work is exhausted, so `endDate` is not part of the
    // contract and an unknown key must not be smuggled through.
    const result = scheduleConfigSchema.safeParse({
      startDate: "2026-10-02",
      timezone: "UTC",
    });
    expect(result.success).toBe(true);
    expect(result.success && "endDate" in result.data).toBe(false);
  });

  it("rejects a daily end time at or before the start time", () => {
    // VERIFIED `!endTime.isAfter(startTime)`.
    for (const endTime of ["08:00", "09:00"]) {
      const result = scheduleConfigSchema.safeParse({
        startTime: "09:00",
        endTime,
        timezone: "UTC",
      });
      expect(result.success, endTime).toBe(false);
    }
  });

  it("rejects an invalid IANA identifier", () => {
    const result = scheduleConfigSchema.safeParse({ timezone: "Not/AZone" });
    expect(result.success).toBe(false);
  });

  });

describe("toCreateCampaignPayload", () => {
  it("omits targetTenantId — it is a query parameter, not a body field", () => {
    // VERIFIED `CampaignController.create` takes `@RequestParam UUID tenantId`.
    const payload = toCreateCampaignPayload({
      ...VALID_PLAYFILE,
      targetTenantId: "66666666-6666-4666-8666-666666666666",
    });
    expect("targetTenantId" in payload).toBe(false);
  });

  it("carries callOnWhitelistNumbers — it IS a create field", () => {
    const payload = toCreateCampaignPayload({
      ...VALID_PLAYFILE,
      callOnWhitelistNumbers: true,
    });
    expect(payload.callOnWhitelistNumbers).toBe(true);
  });

  it("normalises schedule nulls rather than dropping them", () => {
    // The server DTO uses nullable components, so an all-null schedule is the
    // honest representation of "no window".
    const payload = toCreateCampaignPayload({
      ...VALID_PLAYFILE,
      schedule: { timezone: "UTC" },
    });
    expect(payload.schedule).toEqual({
      startDate: null,
      startTime: null,
      endTime: null,
      timezone: "UTC",
      allowedDaysOfWeek: null,
      holidayCalendarId: null,
    });
  });
});

describe("toUpdateCampaignPayload", () => {
  it("does NOT send callOnWhitelistNumbers — it is not an update field", () => {
    // VERIFIED `UpdateCampaignRequest` has 15 components and this is not one;
    // `CampaignMapper.updateEntity` never reads it. The F1 mapper sent it on
    // every save and the server silently dropped it.
    const values = updateCampaignSchema.parse({
      name: "October reminder",
      contentMode: "AUDIO",
      audioAssetId: "33333333-3333-4333-8333-333333333333",
    });
    const payload = toUpdateCampaignPayload(values);
    expect("callOnWhitelistNumbers" in payload).toBe(false);
  });

  it("has no callOnWhitelistNumbers field to set in the first place", () => {
    expect(Object.keys(updateCampaignSchema.shape)).not.toContain(
      "callOnWhitelistNumbers",
    );
  });

  it("has no campaignType field — the type is immutable", () => {
    // VERIFIED `UpdateCampaignRequest` has no `campaignType` component and the
    // controller describes it as immutable.
    expect(Object.keys(updateCampaignSchema.shape)).not.toContain("campaignType");
  });

  it("carries the three execution limits", () => {
    const payload = toUpdateCampaignPayload(
      updateCampaignSchema.parse({
        name: "October reminder",
        contentMode: "AUDIO",
        audioAssetId: "33333333-3333-4333-8333-333333333333",
        dailyDialLimit: 2,
        maxDailyAttempts: 4,
        maxCallDurationSeconds: 180,
      }),
    );
    expect(payload.dailyDialLimit).toBe(2);
    expect(payload.maxDailyAttempts).toBe(4);
    expect(payload.maxCallDurationSeconds).toBe(180);
  });

  it("clears a limit when the form omits it — PUT semantics, not merge", () => {
    // VERIFIED the controller: "omitted optional blocks are cleared".
    const payload = toUpdateCampaignPayload(
      updateCampaignSchema.parse({
        name: "October reminder",
        contentMode: "AUDIO",
        audioAssetId: "33333333-3333-4333-8333-333333333333",
      }),
    );
    expect(payload.dailyDialLimit).toBeUndefined();
    expect(payload.maxDailyAttempts).toBeUndefined();
  });
});

describe("execution limit bounds", () => {
  it("enforces the server's own ranges", () => {
    // VERIFIED constraint validators: DailyDialLimit 1..3,
    // CampaignDailyAttempts 1..10, MaxCallDurationSeconds 1..3600.
    const cases: { extra: Record<string, number>; expected: boolean }[] = [
      { extra: { dailyDialLimit: 0 }, expected: false },
      { extra: { dailyDialLimit: 1 }, expected: true },
      { extra: { dailyDialLimit: 3 }, expected: true },
      { extra: { dailyDialLimit: 4 }, expected: false },
      { extra: { maxDailyAttempts: 1 }, expected: true },
      { extra: { maxDailyAttempts: 10 }, expected: true },
      { extra: { maxDailyAttempts: 11 }, expected: false },
      { extra: { maxCallDurationSeconds: 1 }, expected: true },
      { extra: { maxCallDurationSeconds: 3600 }, expected: true },
      { extra: { maxCallDurationSeconds: 3601 }, expected: false },
    ];
    for (const { extra, expected } of cases) {
      const result = createCampaignSchema.safeParse({ ...VALID_PLAYFILE, ...extra });
      expect(result.success, JSON.stringify(extra)).toBe(expected);
    }
  });
});

describe("campaignTypePlaysMedia stays in step with the enum", () => {
  it("is true for exactly PLAYFILE and DTMF", () => {
    // VERIFIED `CampaignType.playsMedia()` — the backend derives BOTH the
    // content-required and TTS-refused rules from this single switch, which has
    // no `default` arm so a new type cannot be added without stating it.
    expect(campaignTypePlaysMedia("PLAYFILE")).toBe(true);
    expect(campaignTypePlaysMedia("DTMF")).toBe(true);
    expect(campaignTypePlaysMedia("CONNECT_BY_AGENT")).toBe(false);
    expect(campaignTypePlaysMedia("MISSED_CALL")).toBe(false);
  });

  it("implies that no type can both play media and use TTS", () => {
    // The two halves of the impossibility, stated as a property rather than as
    // four separate cases.
    const types = ["PLAYFILE", "DTMF", "CONNECT_BY_AGENT", "MISSED_CALL"] as const;
    const canUseTts = types.filter(
      (type) => !campaignTypePlaysMedia(type),
    );
    // Only non-media types are not *refused* TTS — and they take no content at
    // all, so the set is still empty in practice.
    expect(canUseTts.every((type) => !campaignTypePlaysMedia(type))).toBe(true);
  });
});

/* -------------------------------------------------------------------------- */
/* F5.1 — optional references: "" means absent, and must reach the wire as such */
/* -------------------------------------------------------------------------- */

/**
 * The defect these tests exist for, proven against the shipped schemas before
 * the fix.
 *
 * Every reference control is a `<Select>` whose first option is an empty
 * sentinel, and both dialogs seed an unset reference with `""`:
 *
 *  - `create-campaign-dialog`'s `EMPTY_VALUES`: `contactGroupId: ""`,
 *    `didId: ""`, `audioAssetId: ""`
 *  - `edit-campaign-dialog`'s `values`: `campaign.contactGroupId ?? ""`,
 *    `campaign.didId ?? ""`, `campaign.audioAssetId ?? ""`
 *
 * Against `z.string().uuid().optional().nullable()` that empty state FAILED:
 *
 *  - a CONNECT_BY_AGENT or MISSED_CALL campaign has no audio asset, so its seed
 *    is `audioAssetId: ""` -> "Invalid UUID" -> **the edit form could never be
 *    submitted at all**, for exactly the campaign types the F4/F5 pickers were
 *    built for;
 *  - a PLAYFILE campaign with no DID and no contact group failed the same way;
 *  - and "no reference" could never be selected, because the "— None —" option
 *    writes the same `""` the schema rejected.
 *
 * The backend has always supported all three. VERIFIED `UpdateCampaignRequest`:
 * "PUT semantics: the configuration blocks are replaced wholesale - omitting an
 * optional block clears it", and `CampaignMapper.applyCommon` assigns the
 * references unconditionally (`entity.setDidId(didId)`), so an omitted reference
 * deserialises to null and clears.
 *
 * The frontend was refusing a state the backend explicitly permits.
 */

const CBA_TYPE_CONFIG = {
  connectByAgent: {
    queueId: "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
    selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
    ringDurationSeconds: 60,
  },
} as const;

/** VERIFIED AgentSelectionStrategy has exactly one constant. */
const CONNECT_BY_AGENT_CREATE = {
  name: "Weekday outbound",
  campaignType: "CONNECT_BY_AGENT",
  runMode: "ONE_TIME",
  typeConfig: CBA_TYPE_CONFIG,
} as const;

describe("optional references may be absent", () => {
  it("create accepts a CONNECT_BY_AGENT campaign with no audience, DID or audio", () => {
    // The realistic shape of this campaign: it plays no media and the operator
    // chose neither an audience nor a number. Rejected before the fix.
    const result = createCampaignSchema.safeParse({
      ...CONNECT_BY_AGENT_CREATE,
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      callOnWhitelistNumbers: false,
    });
    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error.issues),
    ).toBe(true);
  });

  it("create accepts a MISSED_CALL campaign, which has no audio by definition", () => {
    const result = createCampaignSchema.safeParse({
      name: "Missed call",
      campaignType: "MISSED_CALL",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      typeConfig: { missedCall: { ringDurationSeconds: 30 } },
      callOnWhitelistNumbers: false,
    });
    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error.issues),
    ).toBe(true);
  });

  it("update accepts a CONNECT_BY_AGENT campaign carrying no audio reference", () => {
    // The exact regression: `campaign.audioAssetId ?? ""` seeds "" for a type
    // that never has one, which made the edit form unsubmittable.
    const result = updateCampaignSchema.safeParse({
      name: "Weekday outbound",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      typeConfig: CBA_TYPE_CONFIG,
    });
    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error.issues),
    ).toBe(true);
  });

  it("update accepts a PLAYFILE campaign that has a DID but no contact group", () => {
    const result = updateCampaignSchema.safeParse({
      name: "Reminder",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "22222222-2222-4222-8222-222222222222",
      audioAssetId: "33333333-3333-4333-8333-333333333333",
      contentMode: "AUDIO",
      typeConfig: undefined,
    });
    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error.issues),
    ).toBe(true);
  });

  it("treats null and undefined as absent too, not just the empty string", () => {
    // The edit dialog seeds `?? ""`, but a programmatic caller may send null.
    for (const absent of [null, undefined]) {
      const result = updateCampaignSchema.safeParse({
        name: "Weekday outbound",
        runMode: "ONE_TIME",
        contactGroupId: absent,
        didId: absent,
        audioAssetId: absent,
        typeConfig: CBA_TYPE_CONFIG,
      });
      expect(result.success, String(absent)).toBe(true);
    }
  });
});

describe("absent references must not weaken the content rules", () => {
  it("still refuses a media-playing campaign with no audio asset", () => {
    // `!data.audioAssetId` is true for "", so the refine still fires. Allowing
    // the empty sentinel must not open a hole in the AUDIO requirement.
    const result = updateCampaignSchema.safeParse({
      name: "Reminder",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      contentMode: "AUDIO",
      typeConfig: undefined,
    });
    expect(result.success).toBe(false);
    expect(
      result.success ? [] : result.error.issues.map((i) => i.message),
    ).toContain("AUDIO content requires exactly one audio asset reference.");
  });

  it("still refuses a non-empty value that is not a UUID", () => {
    // The sentinel is exactly "". Anything else must still be validated.
    const result = updateCampaignSchema.safeParse({
      name: "Weekday outbound",
      runMode: "ONE_TIME",
      contactGroupId: "not-a-uuid",
      didId: "",
      audioAssetId: "",
      typeConfig: CBA_TYPE_CONFIG,
    });
    expect(result.success).toBe(false);
    expect(result.success ? [] : result.error.issues.map((i) => i.message)).toContain(
      "Must be a UUID.",
    );
  });

  it("still requires a UUID for the queue inside CONNECT_BY_AGENT typeConfig", () => {
    // The queue is validated by `campaignTypeConfigSchema`, which was not
    // loosened — a bad queue id must still fail before the request is sent.
    const result = createCampaignSchema.safeParse({
      ...CONNECT_BY_AGENT_CREATE,
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      callOnWhitelistNumbers: false,
      typeConfig: {
        connectByAgent: {
          queueId: "not-a-uuid",
          selectionStrategy: "LEAST_ACTIVE_RESERVATIONS",
          ringDurationSeconds: 60,
        },
      },
    });
    expect(result.success).toBe(false);
  });
});

describe("an absent reference reaches the wire as absent, never as an empty string", () => {
  // The second half of the defect: `values.didId ?? undefined` is the identity
  // for null/undefined but NOT for "", because "" is not nullish. The builder
  // passed the sentinel straight through, producing `{"didId":""}` for a UUID
  // field — which Jackson rejects with an InvalidFormatException, i.e. a 400
  // about a malformed id rather than about the reference being cleared.
  const wire = (payload: Record<string, unknown>): string => JSON.stringify(payload);

  it("update omits a cleared DID entirely, so the backend clears it", () => {
    const parsed = updateCampaignSchema.parse({
      name: "Reminder",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "",
      audioAssetId: "33333333-3333-4333-8333-333333333333",
      contentMode: "AUDIO",
      typeConfig: undefined,
    });
    const payload = toUpdateCampaignPayload(parsed) as unknown as Record<string, unknown>;
    expect(payload.didId).toBeUndefined();
    // An undefined value is dropped by JSON.stringify, so the key never reaches
    // the server and the backend's PUT semantics clear the reference.
    expect(wire(payload)).not.toMatch(/"didId":""/);
  });

  it("create omits absent references too", () => {
    const parsed = createCampaignSchema.parse({
      ...CONNECT_BY_AGENT_CREATE,
      contactGroupId: "",
      didId: "",
      audioAssetId: "",
      callOnWhitelistNumbers: false,
    });
    const payload = toCreateCampaignPayload(parsed) as unknown as Record<string, unknown>;
    expect(payload.didId).toBeUndefined();
    expect(payload.contactGroupId).toBeUndefined();
    expect(payload.audioAssetId).toBeUndefined();
    expect(wire(payload)).not.toMatch(/:\s*""/);
  });

  it("still sends a real reference unchanged", () => {
    const parsed = updateCampaignSchema.parse({
      name: "Reminder",
      runMode: "ONE_TIME",
      contactGroupId: "11111111-1111-4111-8111-111111111111",
      didId: "22222222-2222-4222-8222-222222222222",
      audioAssetId: "33333333-3333-4333-8333-333333333333",
      contentMode: "AUDIO",
      typeConfig: undefined,
    });
    const payload = toUpdateCampaignPayload(parsed);
    expect(payload.didId).toBe("22222222-2222-4222-8222-222222222222");
    expect(payload.contactGroupId).toBe("11111111-1111-4111-8111-111111111111");
    expect(payload.audioAssetId).toBe("33333333-3333-4333-8333-333333333333");
  });

  it("preserves a reference when only a sibling reference is absent", () => {
    // Preservation and clearing are independent: dropping the contact group must
    // not take the DID with it.
    const parsed = updateCampaignSchema.parse({
      name: "Reminder",
      runMode: "ONE_TIME",
      contactGroupId: "",
      didId: "22222222-2222-4222-8222-222222222222",
      audioAssetId: "33333333-3333-4333-8333-333333333333",
      contentMode: "AUDIO",
      typeConfig: undefined,
    });
    const payload = toUpdateCampaignPayload(parsed);
    expect(payload.contactGroupId).toBeUndefined();
    expect(payload.didId).toBe("22222222-2222-4222-8222-222222222222");
  });

  it("never emits an empty string for any optional reference", () => {
    for (const absent of ["", null, undefined]) {
      const parsed = updateCampaignSchema.parse({
        name: "Reminder",
        runMode: "ONE_TIME",
        contactGroupId: absent,
        didId: absent,
        audioAssetId: "33333333-3333-4333-8333-333333333333",
        contentMode: "AUDIO",
        typeConfig: undefined,
      });
      const payload = toUpdateCampaignPayload(parsed) as unknown as Record<string, unknown>;
      for (const field of ["contactGroupId", "didId", "audioAssetId", "ttsTemplateId"]) {
        expect(payload[field], `${field} for ${String(absent)}`).not.toBe("");
      }
    }
  });
});