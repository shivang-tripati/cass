"use client";

import { useFormContext } from "react-hook-form";

import { FieldDescription, FieldSet, FieldLegend } from "@/components/ui/field";
import { Label } from "@/components/ui/label";
import { TextField } from "@/components/forms/text-field";
import type { DayOfWeek } from "@/lib/api/contracts";

/**
 * The campaign schedule editor.
 *
 * ## The rule the F1 form did not know about
 *
 * `CampaignReadinessService.checkExecutionTimezone` runs on EVERY readiness
 * evaluation and is not conditional on a window being configured:
 *
 * ```java
 * String timezone = schedule == null ? null : schedule.getTimezone();
 * if (timezone == null || timezone.isBlank()) {
 *     reasons.add(new CampaignReadinessReason("SCHEDULE_TIMEZONE_REQUIRED",
 *         "Voice Blast campaigns require a schedule timezone for the daily dial "
 *         + "limit; no fallback zone is applied."));
 * }
 * ```
 *
 * So a campaign with **no schedule block at all** is permanently unready, and a
 * campaign with a schedule but no timezone is permanently unready. That is
 * stricter than the write-time rule, which only requires a timezone when a
 * window is configured (`validateScheduleWindow`), and it is why
 * `POST /executions` rejects such a campaign with `BUSINESS_RULE_VIOLATION`.
 *
 * The F1 form made the timezone field optional with the description "Required
 * when any schedule field is set", which is true of validation and misleading
 * about executability. This one says what the server actually does.
 *
 * ## A schedule is also required to activate at all
 *
 * VERIFIED `CampaignService.validateActivation`: the very first check is
 * `if (schedule == null) throw business("Campaign must have a configured
 * schedule before it can be scheduled.")`. That is the only transition that
 * validates, so this field is the gate on DRAFT -> SCHEDULED and nothing else
 * is. The UI states it rather than letting the user discover it as a 400.
 *
 * ## `holidayCalendarId` is accepted and has no UI
 *
 * VERIFIED: it is a plain nullable UUID on `ScheduleConfig` with no validation
 * annotation, no repository lookup and no readiness check anywhere in the
 * campaign package. There is no holiday-calendar endpoint to validate it
 * against, so offering a free-text UUID field would be a control that cannot be
 * checked. It is preserved on read and left alone; see the F4 doc.
 */

const DAY_OPTIONS: readonly { value: DayOfWeek; label: string }[] = [
  { value: "MONDAY", label: "Mon" },
  { value: "TUESDAY", label: "Tue" },
  { value: "WEDNESDAY", label: "Wed" },
  { value: "THURSDAY", label: "Thu" },
  { value: "FRIDAY", label: "Fri" },
  { value: "SATURDAY", label: "Sat" },
  { value: "SUNDAY", label: "Sun" },
];

interface ScheduleFieldProps {
  name: string;
}

export function CampaignScheduleField({ name }: ScheduleFieldProps) {
  const {
    register,
    formState: { errors },
  } = useFormContext();

  return (
    <FieldSet>
      <FieldLegend>Schedule</FieldLegend>
      <FieldDescription>
        A schedule is required before a campaign can be scheduled, and a timezone
        is required before it can ever execute. A campaign with no schedule is
        permanently unready — the server applies no fallback zone.
      </FieldDescription>

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Start date"
          type="date"
          registration={register(`${name}.startDate`)}
          error={nested(errors, `${name}.startDate`)}
        />
        <TextField
          label="Daily start time"
          type="time"
          registration={register(`${name}.startTime`)}
          error={nested(errors, `${name}.startTime`)}
        />
        <TextField
          label="Daily end time"
          type="time"
          registration={register(`${name}.endTime`)}
          error={nested(errors, `${name}.endTime`)}
        />
      </div>

      <TextField
        label="Timezone (IANA)"
        placeholder="Asia/Kolkata"
        description="Required. Must be a valid IANA identifier, for example Europe/London or Asia/Kolkata. A window without a timezone is rejected, and without one at all the campaign can never be executed."
        registration={register(`${name}.timezone`)}
        error={nested(errors, `${name}.timezone`)}
      />

      <div className="flex flex-col gap-1.5">
        <Label className="text-sm font-medium">Allowed days of week</Label>
        <div className="flex flex-wrap gap-3">
          {DAY_OPTIONS.map((day) => (
            <label
              key={day.value}
              className="inline-flex cursor-pointer items-center gap-1.5 text-sm hover:bg-accent rounded border p-2"
            >
              <input
                type="checkbox"
                value={day.value}
                {...register(`${name}.allowedDaysOfWeek`)}
                className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
              />
              <span>{day.label}</span>
            </label>
          ))}
        </div>
        <p className="text-muted-foreground text-sm">
          Leave all unchecked to allow every day. The check is evaluated in the
          campaign&apos;s own timezone at execution time.
        </p>
        {nested(errors, `${name}.allowedDaysOfWeek`) ? (
          <p role="alert" className="text-destructive text-sm font-medium">
            {nested(errors, `${name}.allowedDaysOfWeek`)}
          </p>
        ) : null}
      </div>
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
