"use client";

import { useId } from "react";
import { Controller, useFormContext } from "react-hook-form";

import { FieldDescription, FieldLabel, FieldSet, FieldLegend } from "@/components/ui/field";
import { Label } from "@/components/ui/label";
import { SelectField } from "@/components/forms/select-field";
import { TextField } from "@/components/forms/text-field";
import {
  CONNECT_BY_AGENT_RING_SECONDS_MAX,
  CONNECT_BY_AGENT_RING_SECONDS_MIN,
  DTMF_MAX_SEQUENCE_LENGTH,
  DTMF_TIMEOUT_SECONDS_MAX,
  DTMF_TIMEOUT_SECONDS_MIN,
  MISSED_CALL_RING_SECONDS_MAX,
  MISSED_CALL_RING_SECONDS_MIN,
} from "@/lib/schemas/campaign-config";

/**
 * Typed editors for the three type-specific configuration payloads.
 *
 * ## Why this component exists
 *
 * F1 wrote exact Zod schemas for `typeConfig` and `integrationConfig`
 * (`campaign-config.ts`) and then never used them in a form. Both were rendered
 * as free-text JSON textareas, which is not merely a worse UX — it is a
 * correctness defect, because **every backend parser for these payloads calls
 * `rejectUnknownFields` and returns 400 for anything it does not recognise.**
 *
 * The practical consequence: the old textarea could produce a payload that was
 * guaranteed to fail, and nothing in the form said so. Worse, the edit dialog
 * round-tripped the stored JSON through the textarea as raw text, so a stored
 * `{"connectByAgent": {...}}` was re-submitted as a string rather than an
 * object and could not satisfy the schema at all. A `CONNECT_BY_AGENT`,
 * `MISSED_CALL` or `DTMF` campaign could not be edited through the UI without
 * hand-writing canonical JSON.
 *
 * These components render the exact shapes instead. There is no free-text path
 * and no JSON textarea.
 *
 * ## Each field's bound is the backend's own
 *
 *  - MISSED_CALL — `MissedCallRingWindow` MIN 10, MAX 60
 *  - CONNECT_BY_AGENT — `AgentRingWindow` MIN 10, MAX 240; and
 *    `AgentSelectionStrategy` has exactly ONE constant, so it is rendered as a
 *    fixed read-only value rather than a one-option dropdown.
 *  - DTMF — `DtmfConfig`: `expected` is at most 16 collectable digits,
 *    `timeoutSecs` is 1..120, `terminator` is a single DTMF key.
 *
 * ## PLAYFILE and IVR are deliberately absent
 *
 * PLAYFILE's valid `typeConfig` is the empty object — `PlayfileCampaignConfig
 * .toJson()` emits `{}` and `fromTypeConfig` rejects anything else — so there
 * is nothing to render.
 *
 * The IVR variant of DTMF is not offered for creation. VERIFIED
 * `CampaignConfigurationService.freezeIvrIfSelected`: a campaign only acquires
 * an `ivr` payload by way of `POST /api/v1/campaigns/{id}/ivr-tree`, and that
 * endpoint requires `IVR_MANAGE`, which **no migration seeds** — the F1 and F3
 * blocker, still unresolved. `IvrSnapshotCodec` then has to decode the frozen
 * node set on the way back in. Both halves of the round-trip are unavailable, so
 * an editable IVR field would be a control that cannot produce a valid value.
 */

interface TypeConfigFieldProps {
  /** Registerable path for the whole `typeConfig` object. */
  name: string;
  error?: string;
}

/** MISSED_CALL — `{"missedCall":{"ringDurationSeconds":N}}`. */
export function MissedCallTypeConfigField({ name, error }: TypeConfigFieldProps) {
  const {
    register,
    formState: { errors },
  } = useFormContext();
  const fieldError = getNestedError(errors, name);

  return (
    <FieldSet>
      <FieldLegend>Missed call configuration</FieldLegend>
      <FieldDescription>
        The whole time budget, in seconds. Before answer it is the maximum ringing
        time; after answer the deadline is rebased onto the answer instant. The
        platform ends the call itself when the budget elapses, and that counts as
        a successful delivery — it is not a failure and is not retried.
      </FieldDescription>
      <TextField
        label="Ring duration (seconds)"
        type="number"
        inputMode="numeric"
        min={MISSED_CALL_RING_SECONDS_MIN}
        max={MISSED_CALL_RING_SECONDS_MAX}
        description={`${MISSED_CALL_RING_SECONDS_MIN}–${MISSED_CALL_RING_SECONDS_MAX} seconds.`}
        registration={register(`${name}.missedCall.ringDurationSeconds`, {
          valueAsNumber: true,
        })}
        error={getNestedFieldError(errors, `${name}.missedCall.ringDurationSeconds`)}
      />
      {error ? <FieldErrorText message={error} /> : null}
      {fieldError ? <FieldErrorText message={fieldError} /> : null}
    </FieldSet>
  );
}

/** CONNECT_BY_AGENT — `{"connectByAgent":{queueId, selectionStrategy, ringDurationSeconds}}`. */
export function ConnectByAgentTypeConfigField({
  name,
  queueOptions,
  queuesTruncated,
  currentQueueUnavailable,
  error,
}: TypeConfigFieldProps & {
  queueOptions: readonly { value: string; label: string }[];
  /** The queue list hit the server page cap, so it is probably incomplete. */
  queuesTruncated: boolean;
  /**
   * The campaign already points at a queue that is absent from the option list
   * because it is no longer ACTIVE. The caller injects it back into
   * `queueOptions` so the stored value survives; this flag only drives the
   * explanation shown under the select.
   */
  currentQueueUnavailable?: boolean;
}) {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext();
  const descriptionId = useId();

  return (
    <FieldSet>
      <FieldLegend>Agent connection configuration</FieldLegend>
      <FieldDescription>
        When the call is answered, the platform hands it to an agent from the
        queue you choose. The queue is the source of truth for who is eligible;
        this campaign stores only the reference.
      </FieldDescription>

      <Controller
        control={control}
        name={`${name}.connectByAgent.queueId`}
        render={({ field }) => (
          <div className="flex flex-col gap-1.5">
            <FieldLabel htmlFor={`${name}-queueId`}>Queue</FieldLabel>
            <select
              id={`${name}-queueId`}
              aria-describedby={descriptionId}
              aria-invalid={Boolean(field.value === "")}
              className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm shadow-xs outline-none focus-visible:ring-2"
              value={field.value ?? ""}
              onChange={field.onChange}
              onBlur={field.onBlur}
              ref={field.ref}
            >
              <option value="">— Select a queue —</option>
              {queueOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <p id={descriptionId} className="text-muted-foreground text-sm">
              Only active queues in your own tenant can be selected. An inactive
              or disabled queue makes the campaign unschedulable.
            </p>
            {/* F5: the ACTIVE filter is client-side because GET /queues has no
                status parameter, so a queue that stops being active simply
                disappears from the list. Without this the select would render a
                value matching no option — looking unconfigured — while still
                submitting the stored id. The wording says only "not active":
                the filtered list cannot distinguish inactive from disabled from
                removed, and guessing a status would be inventing one the
                frontend never read. Backend validation is unchanged and still
                refuses the value on submit. */}
            {currentQueueUnavailable ? (
              <p role="alert" className="text-destructive text-sm font-medium">
                This campaign points at a queue that is no longer active, so it
                cannot be used. Choose an active queue to make the campaign
                schedulable.
              </p>
            ) : null}
            {queuesTruncated ? (
              <p className="text-muted-foreground text-sm">
                Only the first matching queues are listed. Narrow the search if
                the queue you need is not shown.
              </p>
            ) : null}
            {getNestedFieldError(errors, `${name}.connectByAgent.queueId`) ? (
              <FieldErrorText
                message={getNestedFieldError(
                  errors,
                  `${name}.connectByAgent.queueId`,
                )}
              />
            ) : null}
          </div>
        )}
      />

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${name}-strategy`}>Selection strategy</Label>
        {/* VERIFIED: `AgentSelectionStrategy` has exactly one constant,
            LEAST_ACTIVE_RESERVATIONS, and `isImplemented()` returns true for it.
            There is no round-robin, weighted, skills or AI routing, so a
            one-option select would imply a choice that does not exist. */}
        <select
          id={`${name}-strategy`}
          className="border-input h-9 w-full rounded-md border bg-transparent px-3 text-sm shadow-xs outline-none"
          value="LEAST_ACTIVE_RESERVATIONS"
          disabled
          {...register(`${name}.connectByAgent.selectionStrategy`)}
        >
          <option value="LEAST_ACTIVE_RESERVATIONS">Least active reservations</option>
        </select>
        <p className="text-muted-foreground text-sm">
          The only supported strategy: fewest active reservations, then agent id.
        </p>
      </div>

      <TextField
        label="Agent ring duration (seconds)"
        type="number"
        inputMode="numeric"
        min={CONNECT_BY_AGENT_RING_SECONDS_MIN}
        max={CONNECT_BY_AGENT_RING_SECONDS_MAX}
        description={`${CONNECT_BY_AGENT_RING_SECONDS_MIN}–${CONNECT_BY_AGENT_RING_SECONDS_MAX} seconds. How long the answered call rings its reserved agent before it is abandoned.`}
        registration={register(`${name}.connectByAgent.ringDurationSeconds`, {
          valueAsNumber: true,
        })}
        error={getNestedFieldError(
          errors,
          `${name}.connectByAgent.ringDurationSeconds`,
        )}
      />
      {error ? <FieldErrorText message={error} /> : null}
      {getNestedError(errors, name) ? (
        <FieldErrorText message={getNestedError(errors, name)} />
      ) : null}
    </FieldSet>
  );
}

/** DTMF — `{"dtmf":{expected, maxDigits, terminator, timeoutSecs, action}}`. */
export function DtmfTypeConfigField({ name, error }: TypeConfigFieldProps) {
  const {
    register,
    control,
    formState: { errors },
  } = useFormContext();

  return (
    <FieldSet>
      <FieldLegend>DTMF configuration</FieldLegend>
      <FieldDescription>
        A single-level digit prompt. The caller hears your approved audio, is
        expected to press these digits, and the call takes the action you choose.
      </FieldDescription>

      <TextField
        label="Expected digits"
        placeholder="1234"
        inputMode="numeric"
        maxLength={DTMF_MAX_SEQUENCE_LENGTH}
        description={`Up to ${DTMF_MAX_SEQUENCE_LENGTH} digits, plus * and #.`}
        registration={register(`${name}.dtmf.expected`)}
        error={getNestedFieldError(errors, `${name}.dtmf.expected`)}
      />

      <TextField
        label="Maximum digits accepted"
        type="number"
        inputMode="numeric"
        min={1}
        max={DTMF_MAX_SEQUENCE_LENGTH}
        description="Defaults to the length of the expected sequence."
        registration={register(`${name}.dtmf.maxDigits`, { valueAsNumber: true })}
        error={getNestedFieldError(errors, `${name}.dtmf.maxDigits`)}
      />

      <TextField
        label="Terminator (optional)"
        placeholder="#"
        maxLength={1}
        description="A single digit that ends collection early."
        registration={register(`${name}.dtmf.terminator`)}
        error={getNestedFieldError(errors, `${name}.dtmf.terminator`)}
      />

      <TextField
        label="Timeout (seconds)"
        type="number"
        inputMode="numeric"
        min={DTMF_TIMEOUT_SECONDS_MIN}
        max={DTMF_TIMEOUT_SECONDS_MAX}
        description={`${DTMF_TIMEOUT_SECONDS_MIN}–${DTMF_TIMEOUT_SECONDS_MAX} seconds before the call gives up. Defaults to 10.`}
        registration={register(`${name}.dtmf.timeoutSecs`, { valueAsNumber: true })}
        error={getNestedFieldError(errors, `${name}.dtmf.timeoutSecs`)}
      />

      {/* VERIFIED `DtmfActions`: exactly two String constants. */}
      <SelectField
        label="On valid input"
        name={`${name}.dtmf.action`}
        options={[
          { value: "TERMINATE", label: "Terminate the call" },
          { value: "CONNECT_BY_AGENT", label: "Connect the caller to an agent" },
        ]}
        error={getNestedFieldError(errors, `${name}.dtmf.action`)}
        control={control}
      />
      {error ? <FieldErrorText message={error} /> : null}
      {getNestedError(errors, name) ? (
        <FieldErrorText message={getNestedError(errors, name)} />
      ) : null}
    </FieldSet>
  );
}

function FieldErrorText({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <p role="alert" className="text-destructive text-sm font-medium">
      {message}
    </p>
  );
}

/** Reads a nested RHF error message by dotted path, without `any`. */
function getNestedFieldError(
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

function getNestedError(
  errors: Record<string, unknown>,
  name: string,
): string | undefined {
  return getNestedFieldError(errors, name);
}
