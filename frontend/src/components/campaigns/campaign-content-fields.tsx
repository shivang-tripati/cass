"use client";

import { useFormContext } from "react-hook-form";

import { FieldDescription, FieldSet, FieldLegend } from "@/components/ui/field";
import { SelectField } from "@/components/forms/select-field";
import { campaignTypePlaysMedia } from "@/lib/schemas/campaign-config";
import type { CampaignType } from "@/lib/api/contracts";

/**
 * The audience / number / content block of the campaign form.
 *
 * ## TTS IS NOT OFFERABLE — and the F1 form offered it
 *
 * This is the most consequential correction in F4, so the reasoning is spelled
 * out rather than implied.
 *
 * VERIFIED `CampaignService.validateContent` L435-439:
 *
 * ```java
 * if (type.playsMedia() && mode == ContentMode.TTS) {
 *     throw business(type + " campaigns do not support TTS content yet; "
 *         + "configure an approved audio asset with content mode AUDIO. "
 *         + "TTS playback is not implemented.");
 * }
 * ```
 *
 * `playsMedia()` is true for exactly PLAYFILE and DTMF (L68-75) — which are
 * also the only two types that REQUIRE content (L461-463). So:
 *
 *  - a media-playing type cannot use TTS, and
 *  - a non-media-playing type (CONNECT_BY_AGENT, MISSED_CALL) takes no content
 *    at all — the backend comment calls its content mode "inert, not broken",
 *    and the Zod schema refuses it outright.
 *
 * **There is therefore no campaign type that can use a TTS template.** The F1
 * create and edit dialogs both rendered a working "TTS" content-mode option with
 * a TTS template picker behind it, and every submission of that combination
 * returned 400. Offering a control whose only possible outcome is a rejection
 * is the exact failure F3 refused to ship for audio playback and TTS
 * synthesis, so F4 does not ship it for TTS content either.
 *
 * The TTS domain itself is untouched: templates remain fully manageable, and a
 * future backend that can render them will make this option real. Until then the
 * field states why it is absent, rather than silently omitting it.
 *
 * ## Content is per-campaign, not per-execution
 *
 * VERIFIED `CampaignConfigurationSnapshot`: `contentMode`, `audioAssetId` and
 * `ttsTemplateId` are frozen into the immutable per-execution snapshot at
 * execution-creation time. Changing the content on the campaign afterwards does
 * not change an execution that already exists.
 */

interface ContentFieldProps {
  campaignType: CampaignType;
  audioOptions: readonly { value: string; label: string }[];
  audioTruncated: boolean;
  contactGroupOptions: readonly { value: string; label: string }[];
  contactGroupsTruncated: boolean;
  didOptions: readonly { value: string; label: string }[];
  didsTruncated: boolean;
  /**
   * Read-only contact group id, shown when the campaign is not editable or the
   * loaded list no longer contains the referenced group.
   */
  detachedContactGroup?: { id: string; label: string } | null;
  detachedDid?: { id: string; label: string } | null;
}

export function CampaignContentFields({
  campaignType,
  audioOptions,
  audioTruncated,
  contactGroupOptions,
  contactGroupsTruncated,
  didOptions,
  didsTruncated,
  detachedContactGroup,
  detachedDid,
}: ContentFieldProps) {
  const {
    control,
    register,
    formState: { errors },
  } = useFormContext();
  const playsMedia = campaignTypePlaysMedia(campaignType);
  const errorFor = (field: string): string | undefined => fieldMessage(errors, field);

  return (
    <>
      <FieldSet>
        <FieldLegend>Audience and number</FieldLegend>
        <FieldDescription>
          A contact group is the audience and a DID is the number the platform
          dials from. Both are optional — no rule makes either mandatory — and
          both must belong to the same tenant as the campaign, and an unassigned
          or inactive DID is refused.
        </FieldDescription>

        {detachedContactGroup ? (
          <ReadOnlyReference
            label="Contact group"
            reference={detachedContactGroup}
            note="The group this campaign points at is not in the loaded list, so it is shown by id rather than selectable."
          />
        ) : (
          <SelectField
            label="Contact group"
            name="contactGroupId"
            options={[{ value: "", label: "— None —" }, ...contactGroupOptions]}
            error={errorFor("contactGroupId")}
            control={control}
          />
        )}
        {contactGroupsTruncated ? <CapNotice /> : null}

        {detachedDid ? (
          <ReadOnlyReference
            label="DID"
            reference={detachedDid}
            note="The number this campaign points at is not in the loaded list, so it is shown by id rather than selectable."
          />
        ) : (
          <SelectField
            label="DID"
            name="didId"
            options={[{ value: "", label: "— None —" }, ...didOptions]}
            error={errorFor("didId")}
            control={control}
          />
        )}
        {didsTruncated ? <CapNotice /> : null}
      </FieldSet>

      <FieldSet>
        <FieldLegend>Content</FieldLegend>
        {playsMedia ? (
          <>
            <FieldDescription>
              {campaignType} calls play an approved audio recording. Pick one from
              your approved catalogue.
            </FieldDescription>
            <SelectField
              label="Audio asset"
              name="audioAssetId"
              options={audioOptions}
              error={errorFor("audioAssetId")}
              control={control}
            />
            {audioTruncated ? <CapNotice /> : null}
            <p className="text-muted-foreground text-sm">
              Text-to-speech is not available for this campaign type. The platform
              has no TTS playback runtime, so the server rejects a TTS content
              mode for every type that plays media.
            </p>
          </>
        ) : (
          <FieldDescription>
            {campaignType} calls play no media, so this campaign takes no content.
          </FieldDescription>
        )}
        {/* `contentMode` is submitted explicitly rather than inferred, because
            VERIFIED the backend refuses a content reference with no mode:
            "Content references require an explicit content mode." */}
        {playsMedia ? (
          <input type="hidden" {...register("contentMode")} value="AUDIO" readOnly />
        ) : null}
      </FieldSet>
    </>
  );
}

function ReadOnlyReference({
  label,
  reference,
  note,
}: {
  label: string;
  reference: { id: string; label: string };
  note: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <p className="text-sm font-medium">{label}</p>
      <p className="text-sm">
        {reference.label}{" "}
        <span className="text-muted-foreground font-mono text-xs">{reference.id}</span>
      </p>
      <p className="text-muted-foreground text-sm">{note}</p>
    </div>
  );
}

function CapNotice() {
  return (
    <p className="text-muted-foreground text-sm">
      Only the first matching records are listed. The server caps every list at
      100 items.
    </p>
  );
}

/**
 * Reads a react-hook-form error message by dotted path.
 *
 * `formState.errors` is a recursive `FieldErrors` structure, so a direct
 * `errors.name?.message` is typed as `string | FieldError | ...` and does not
 * narrow to a string without a cast. This walks the structure and returns only
 * a real message, so no `any` and no assertion is needed.
 */
function fieldMessage(
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
