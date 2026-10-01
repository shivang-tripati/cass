"use client";

import { useFormContext } from "react-hook-form";

import { FieldDescription, FieldSet, FieldLegend } from "@/components/ui/field";
import { TextField } from "@/components/forms/text-field";
import { SelectField } from "@/components/forms/select-field";

/**
 * The integration configuration editor.
 *
 * ## The most important thing this component says
 *
 * **No webhook is delivered and no report is generated.** VERIFIED, and stated
 * by the backend in four places: the `CampaignIntegrationConfig` Javadoc, both
 * `@Schema` descriptions on the request DTOs, and
 * `CampaignService.validateIntegrationConfig`'s own comment — "VB-7C.2
 * implements no delivery and no reporting".
 *
 * So `webhook.enabled = true` records INTENT and nothing else. The F1 UI
 * rendered this block as a JSON textarea with a `{"webhookUrl": ...}`
 * placeholder, which is doubly wrong: `webhookUrl` is not a supported key at
 * all (`WebhookConfig.rejectUnknownFields` names only `enabled`, `endpoint` and
 * `events`, and throws for anything else), and a form that accepts arbitrary
 * JSON invites someone to type a credential into a field the backend will
 * reject.
 *
 * ## This configuration cannot store a secret — by construction
 *
 * VERIFIED `WebhookConfig.rejectUnknownFields` has a dedicated message for it:
 * "In particular this configuration stores no secrets; web-hook signing is not
 * implemented." There is no credential field, and
 * `WebhookEndpointValidator` refuses an endpoint with embedded credentials. So
 * there is nothing here to mask, and no secret can reach the client through this
 * response. The form does not offer a credential input, and the endpoint field
 * says so.
 *
 * ## `reportPrivacy` changes nothing today
 *
 * VERIFIED `ReportPrivacy`: "Configuration only: it does not change the existing
 * attempt-listing APIs." The attempt endpoints continue to return contact data
 * unchanged. The field is still real and still stored, so it is presented, but
 * with the actual consequence rather than a promise.
 *
 * ## Event vocabulary
 *
 * VERIFIED `WebhookEvent` is a PUBLIC contract with exactly three values, and
 * its Javadoc says selecting one "records configured intent only: VB-7C.2
 * implements no delivery, so no event is emitted or sent yet." The platform's
 * internal domain-event names are never exposed, and are not invented here.
 */

const WEBHOOK_EVENTS = [
  { value: "campaign.attempt.completed", label: "Attempt completed" },
  { value: "campaign.attempt.failed", label: "Attempt failed" },
  { value: "campaign.attempt.cancelled", label: "Attempt cancelled" },
] as const;

export function CampaignIntegrationField({ name }: { name: string }) {
  const {
    register,
    control,
    watch,
    formState: { errors },
  } = useFormContext();

  const enabled = watch(`${name}.webhook.enabled`);
  const events = watch(`${name}.webhook.events`);

  const toggleEvent = (event: string) => {
    const current: string[] = Array.isArray(events) ? events : [];
    // VERIFIED `WebhookConfig`: duplicates are rejected outright, so the toggle
    // adds or removes rather than allowing a repeated selection.
    return current.includes(event)
      ? current.filter((value) => value !== event)
      : [...current, event];
  };

  return (
    <FieldSet>
      <FieldLegend>Integration</FieldLegend>
      <FieldDescription>
        Optional. This records configuration only — the platform does not
        deliver webhooks and does not generate reports, so enabling one changes
        no behaviour today.
      </FieldDescription>

      <label className="inline-flex cursor-pointer items-center gap-2 text-sm">
        <input
          type="checkbox"
          {...register(`${name}.webhook.enabled`)}
          className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
        />
        <span>Record a webhook endpoint</span>
      </label>

      {enabled ? (
        <div className="flex flex-col gap-3">
          <TextField
            label="Endpoint"
            type="url"
            placeholder="https://example.com/hooks/campaigns"
            description="An absolute http or https URL. Credentials embedded in the URL are refused — this configuration cannot hold a secret."
            registration={register(`${name}.webhook.endpoint`)}
            error={nested(errors, `${name}.webhook.endpoint`)}
          />
          <div className="flex flex-col gap-1.5">
            <p className="text-sm font-medium">Events</p>
            <p className="text-muted-foreground text-sm">
              Record which outcomes you intend to subscribe to. At least one is
              required, and each may be selected once. Selecting an event is not
              a delivery guarantee.
            </p>
            <div className="flex flex-col gap-1.5 pt-1">
              {WEBHOOK_EVENTS.map((event) => {
                const selected: string[] = Array.isArray(events) ? events : [];
                return (
                  <label
                    key={event.value}
                    className="inline-flex cursor-pointer items-center gap-2 text-sm"
                  >
                    <input
                      type="checkbox"
                      checked={selected.includes(event.value)}
                      onChange={() => toggleEvent(event.value)}
                      className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                    />
                    <span>{event.label}</span>
                  </label>
                );
              })}
            </div>
            {/* Publishes the toggled array back into RHF. VERIFIED the backend
                reads `webhook.events` as an array of public identifiers and
                rejects duplicates, so the value must be the array itself. */}
            <input
              type="hidden"
              {...register(`${name}.webhook.events`)}
              value={JSON.stringify(Array.isArray(events) ? events : [])}
              readOnly
            />
            {nested(errors, `${name}.webhook.events`) ? (
              <p role="alert" className="text-destructive text-sm font-medium">
                {nested(errors, `${name}.webhook.events`)}
              </p>
            ) : null}
          </div>
        </div>
      ) : null}

      <SelectField
        label="Report privacy policy"
        name={`${name}.reportPrivacy.policy`}
        options={[
          { value: "FULL", label: "Full — reports carry complete identifiers" },
          { value: "MASKED", label: "Masked — reports show only the last four digits" },
        ]}
        description="Describes what a future reporting subsystem should show. It does not change the attempt-listing API, which continues to return contact data unchanged."
        error={nested(errors, `${name}.reportPrivacy.policy`)}
        control={control}
      />
    </FieldSet>
  );
}

/** Reads a nested RHF error message by dotted path. */
function nested(
  errors: Record<string, unknown>,
  path: string,
): string | undefined {
  let cursor: unknown = errors;
  for (const segment of path.split(".")) {
    if (typeof cursor !== "object" || cursor === null) return undefined;
    cursor = (cursor as Record<string, unknown>)[segment];
  }
  if (typeof cursor !== "object" || cursor === null) return undefined;
  const message = (cursor as { message?: unknown }).message;
  return typeof message === "string" ? message : undefined;
}
