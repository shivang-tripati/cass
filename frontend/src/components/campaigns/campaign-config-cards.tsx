"use client";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { TypeIcon } from "lucide-react";
import { CONFIGURABLE_RETRY_CATEGORIES } from "@/lib/api/contracts";
import type {
  CampaignTypeConfig,
  ConnectByAgentTypeConfig,
  ContentMode,
  DtmfTypeConfig,
  MissedCallTypeConfig,
  RetryPolicyConfig,
  CampaignResponse,
  ScheduleConfig,
} from "@/lib/api/contracts";

/**
 * Read-only renderers for the four configuration blocks a campaign carries.
 *
 * The F1 detail page printed `typeConfig` and `integrationConfig` as
 * `JSON.stringify(..., null, 2)` inside a `<pre>`. That is a debug view, not a
 * product surface: it shows the internal key names (`connectByAgent.queueId`)
 * with no labels, no units, and no statement of what the value MEANS or what it
 * is bounded by. `MISSED_CALL.ringDurationSeconds` in particular reads as an
 * integer when it is the entire time budget of a call.
 *
 * These render the same data as labelled values, and say plainly where the
 * backend is the only authority — the schedule timezone, for instance, is a
 * hard executability requirement that a JSON dump never surfaces.
 */

export function CampaignTypeConfigCard({
  campaignType,
  typeConfig,
  contentMode,
  audioAssetId,
  ttsTemplateId,
  callOnWhitelistNumbers,
}: {
  campaignType: string;
  typeConfig: CampaignTypeConfig | null;
  contentMode: ContentMode | null;
  audioAssetId: string | null;
  ttsTemplateId: string | null;
  callOnWhitelistNumbers: boolean | null;
}) {
  const rows = describeTypeConfig(campaignType, typeConfig);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <TypeIcon className="h-4 w-4" />
          Type configuration
        </CardTitle>
        <CardDescription>
          The payload that gives this campaign type its behaviour.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {rows.length === 0 ? (
          <p className="text-muted-foreground text-sm">
            {campaignType === "PLAYFILE"
              ? "Play-a-file campaigns take no type configuration. Their behaviour comes entirely from the selected content."
              : "No type configuration is stored on this campaign."}
          </p>
        ) : (
          <DefinitionList rows={rows} />
        )}

        {contentMode ? (
          <DefinitionList
            rows={[
              ["Content mode", contentMode],
              [
                contentMode === "AUDIO" ? "Audio asset" : "TTS template",
                contentMode === "AUDIO" ? (audioAssetId ?? "—") : (ttsTemplateId ?? "—"),
              ],
            ]}
          />
        ) : null}

        {contentMode === "TTS" ? (
          <p className="text-amber-700 text-sm">
            This campaign stores a TTS template, but the platform has no TTS
            playback runtime. It cannot execute, and the server refuses TTS
            content for any type that plays media.
          </p>
        ) : null}

        {callOnWhitelistNumbers !== null ? (
          <p className="text-muted-foreground text-sm">
            {callOnWhitelistNumbers
              ? "Only numbers on the tenant whitelist are dialled."
              : "Numbers are dialled without a whitelist restriction."}
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

export function CampaignScheduleCard({ schedule }: { schedule: ScheduleConfig }) {
  const rows: [string, string][] = [
    ["Timezone", schedule.timezone ?? "— not set —"],
    ["Start date", schedule.startDate ?? "—"],
    ["Daily window", schedule.startTime && schedule.endTime ? `${schedule.startTime} – ${schedule.endTime}` : "—"],
    [
      "Allowed days",
      schedule.allowedDaysOfWeek && schedule.allowedDaysOfWeek.length > 0
        ? schedule.allowedDaysOfWeek.join(", ")
        : "Every day",
    ],
  ];

  return (
    <Card>
      <CardHeader>
        <CardTitle>Schedule</CardTitle>
        <CardDescription>
          Evaluated in the campaign&apos;s own timezone when an execution is
          requested.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <DefinitionList rows={rows} />
        {!schedule.timezone ? (
          <p className="text-destructive text-sm font-medium">
            No timezone is set. The server applies no fallback, so this campaign
            can never be executed — readiness always reports
            SCHEDULE_TIMEZONE_REQUIRED.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

export function CampaignRetryPolicyCard({ retryPolicy }: { retryPolicy: RetryPolicyConfig }) {
  const rules = retryPolicy.rules ?? [];
  return (
    <Card>
      <CardHeader>
        <CardTitle>Retry policy</CardTitle>
        <CardDescription>
          Retries count beyond the first attempt, so the maximum total attempts
          is one more than the retry count.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <DefinitionList
          rows={[
            ["Strategy", retryPolicy.strategy],
            ["Default retries", String(retryPolicy.maxAttempts)],
            [
              "Default delay",
              retryPolicy.intervalSeconds === null
                ? "— none —"
                : `${retryPolicy.intervalSeconds} seconds`,
            ],
          ]}
        />
        {rules.length > 0 ? (
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium">Per-category rules</p>
            <ul className="ml-1 flex list-disc flex-col gap-1 pl-4 text-sm">
              {rules.map((rule) => {
                const label = (CONFIGURABLE_RETRY_CATEGORIES as readonly string[]).includes(
                  rule.category,
                )
                  ? rule.category
                  : `${rule.category} (not reachable today)`;
                const enabled = rule.enabled !== false;
                return (
                  <li key={rule.category}>
                    <span className="font-medium">{label}</span>
                    {!enabled ? " — disabled" : null}
                    {enabled ? ` — up to ${rule.maxRetries} retries` : null}
                    {enabled && rule.retryDelay ? ` after ${rule.retryDelay}` : null}
                  </li>
                );
              })}
            </ul>
          </div>
        ) : (
          <p className="text-muted-foreground text-sm">
            No per-category rules. Every retryable failure uses the defaults above.
          </p>
        )}
      </CardContent>
    </Card>
  );
}

export function CampaignIntegrationCard({
  integrationConfig,
}: {
  integrationConfig: NonNullable<CampaignResponse["integrationConfig"]>;
}) {
  const webhook = integrationConfig.webhook;
  const privacy = integrationConfig.reportPrivacy;
  return (
    <Card>
      <CardHeader>
        <CardTitle>Integration</CardTitle>
        <CardDescription>
          Configuration only. The platform delivers no webhook and generates no
          report, so a recorded event is intent rather than a guarantee.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <DefinitionList
          rows={[
            ["Webhook recorded", webhook?.enabled ? "Yes" : "No"],
            ["Endpoint", webhook?.endpoint ?? "—"],
            [
              "Events",
              webhook?.events && webhook.events.length > 0
                ? webhook.events.join(", ")
                : "—",
            ],
            ["Report privacy", privacy?.policy ?? "FULL"],
          ]}
        />
        {privacy?.policy === "MASKED" ? (
          <p className="text-muted-foreground text-sm">
            The masked policy describes what a future reporting subsystem should
            show. It does not change the attempt-listing API, which continues to
            return contact data unchanged.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

/** Flattens a type config into labelled rows, or none when the shape is unknown. */
function describeTypeConfig(
  campaignType: string,
  typeConfig: CampaignTypeConfig | null,
): [string, string][] {
  if (!typeConfig || typeof typeConfig !== "object") return [];
  if ("connectByAgent" in typeConfig && typeConfig.connectByAgent) {
    const config = typeConfig as ConnectByAgentTypeConfig;
    return [
      ["Queue", config.connectByAgent.queueId],
      ["Selection strategy", config.connectByAgent.selectionStrategy],
      [
        "Agent ring duration",
        `${config.connectByAgent.ringDurationSeconds} seconds — how long an answered call rings its reserved agent.`,
      ],
    ];
  }
  if ("missedCall" in typeConfig && typeConfig.missedCall) {
    const config = typeConfig as MissedCallTypeConfig;
    return [
      [
        "Ring duration",
        `${config.missedCall.ringDurationSeconds} seconds — the whole time budget, both before answer and rebased onto it.`,
      ],
    ];
  }
  if ("dtmf" in typeConfig && typeConfig.dtmf) {
    const config = typeConfig as DtmfTypeConfig;
    return [
      ["Expected digits", config.dtmf.expected],
      ["Maximum digits", String(config.dtmf.maxDigits)],
      ["Terminator", config.dtmf.terminator ?? "— none —"],
      ["Timeout", `${config.dtmf.timeoutSecs} seconds`],
      ["On valid input", config.dtmf.action],
    ];
  }
  if ("ivr" in typeConfig && typeConfig.ivr) {
    return [
      ["IVR tree", typeConfig.ivr.treeId],
      ["FROZEN_NODES", "present"],
    ];
  }
  // PLAYFILE: `{}` is the only valid payload, so there is nothing to show.
  return [];
}

function DefinitionList({ rows }: { rows: readonly (readonly [string, string])[] }) {
  return (
    <dl className="flex flex-col gap-2">
      {rows.map(([label, value]) => (
        <div key={label} className="flex flex-col gap-0.5 sm:flex-row sm:justify-between sm:gap-4">
          <dt className="text-muted-foreground text-sm">{label}</dt>
          <dd className="font-mono text-sm break-all">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
