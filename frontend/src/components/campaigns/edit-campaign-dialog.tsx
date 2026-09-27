"use client";

import { useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useQueryClient } from "@tanstack/react-query";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { SelectField } from "@/components/forms/select-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import type { CampaignResponse } from "@/lib/api/contracts";
import { campaignsKeys, updateCampaign, getDidsForCampaign, getContactGroupsForCampaign, getAudioAssetsForCampaign, getTtsTemplatesForCampaign } from "@/lib/api/campaigns";
import type { UpdateCampaignValues } from "@/lib/schemas/campaign-mutation";
import {
  updateCampaignSchema,
  toUpdateCampaignPayload,
} from "@/lib/schemas/campaign-mutation";

const RUN_MODE_OPTIONS = [
  { value: "ONE_TIME", label: "One Time" },
  { value: "RECURRING", label: "Recurring" },
] as const;

const CONTENT_MODE_OPTIONS = [
  { value: "AUDIO", label: "Audio" },
  { value: "TTS", label: "TTS" },
] as const;

const DAY_OPTIONS = [
  { value: "MONDAY", label: "Monday" },
  { value: "TUESDAY", label: "Tuesday" },
  { value: "WEDNESDAY", label: "Wednesday" },
  { value: "THURSDAY", label: "Thursday" },
  { value: "FRIDAY", label: "Friday" },
  { value: "SATURDAY", label: "Saturday" },
  { value: "SUNDAY", label: "Sunday" },
] as const;

import type { Path } from "react-hook-form";

/** Returns the error message for a field, if any. */
function getError<T extends Record<string, unknown>>(
  form: ReturnType<typeof useForm<T>>,
  name: Path<T>,
): string | undefined {
  return form.getFieldState(name).error?.message;
}

/**
 * Campaign update dialog (PUT /api/v1/campaigns/{id}).
 * PUT semantics: omitted optional blocks are cleared.
 * campaignType is immutable.
 */
export function EditCampaignDialog({
  campaign,
  onOpenChange,
}: {
  campaign: CampaignResponse | null;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);
  const [showSchedule, setShowSchedule] = useState(false);
  const [showRetry, setShowRetry] = useState(false);

  const form = useForm<UpdateCampaignValues>({
    resolver: zodResolver(updateCampaignSchema),
    defaultValues: {
      name: campaign?.name ?? "",
      description: campaign?.description ?? "",
      runMode: campaign?.runMode ?? "ONE_TIME",
      contactGroupId: campaign?.contactGroupId ?? "",
      didId: campaign?.didId ?? "",
      contentMode: campaign?.contentMode ?? undefined,
      audioAssetId: campaign?.audioAssetId ?? "",
      ttsTemplateId: campaign?.ttsTemplateId ?? "",
      schedule: campaign?.schedule ?? undefined,
      retryPolicy: campaign?.retryPolicy ?? undefined,
      typeConfig: campaign?.typeConfig ?? undefined,
      integrationConfig: campaign?.integrationConfig ?? undefined,
    },
  });

  const campaignType = campaign?.campaignType ?? "PLAYFILE";
  const contentMode = form.watch("contentMode");
  // const runMode = form.watch("runMode"); // unused

  // Fetch reference data for selection dropdowns (only when campaign exists)
  const { data: dids } = useQuery({
    queryKey: ["dids", "for-campaign"],
    queryFn: getDidsForCampaign,
    staleTime: 5 * 60 * 1000,
    enabled: !!campaign,
  });

  const { data: contactGroups } = useQuery({
    queryKey: ["contact-groups", "for-campaign"],
    queryFn: getContactGroupsForCampaign,
    staleTime: 5 * 60 * 1000,
    enabled: !!campaign,
  });

  const { data: audioAssets } = useQuery({
    queryKey: ["audio-assets", "for-campaign"],
    queryFn: getAudioAssetsForCampaign,
    staleTime: 5 * 60 * 1000,
    enabled: !!campaign,
  });

  const { data: ttsTemplates } = useQuery({
    queryKey: ["tts-templates", "for-campaign"],
    queryFn: getTtsTemplatesForCampaign,
    staleTime: 5 * 60 * 1000,
    enabled: !!campaign,
  });

  if (!campaign) return null;

  async function onSubmit(values: UpdateCampaignValues) {
    if (!campaign) return;
    setAlert(null);
    try {
      await updateCampaign(campaign.id, toUpdateCampaignPayload(values));
      toast.success("Campaign updated", {
        description: `${campaign.name} was saved.`,
      });
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      for (const fieldError of apiError.fieldErrors) {
        if (fieldError.field in updateCampaignSchema.shape) {
          form.setError(fieldError.field as keyof UpdateCampaignValues, {
            message: fieldError.message,
          });
          return;
        }
      }
      setAlert(apiError.message);
    }
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto max-w-4xl">
        <DialogHeader>
          <DialogTitle>Edit Campaign</DialogTitle>
          <DialogDescription>
            Campaign type is immutable. Omitted optional sections will be cleared.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive mb-4">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <div className="space-y-4">
              <TextField
                label="Name"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={3}
              />
              <SelectField
                label="Run Mode"
                name="runMode"
                options={RUN_MODE_OPTIONS}
                error={form.formState.errors.runMode?.message}
                control={form.control}
              />

              <div className="flex items-center gap-2">
                <Label className="text-sm font-medium text-muted-foreground">Campaign Type</Label>
                <span className="px-3 py-1.5 text-sm text-muted-foreground bg-muted rounded-md font-mono">
                  {campaignType}
                </span>
                <span className="text-xs text-muted-foreground">(immutable)</span>
              </div>

              <SelectField
                label="Contact Group"
                name="contactGroupId"
                options={[
                  { value: "", label: "— None —" },
                  ...(contactGroups?.map((g) => ({ value: g.id, label: g.name })) ?? []),
                ]}
                error={form.formState.errors.contactGroupId?.message}
                control={form.control}
              />
              <SelectField
                label="DID"
                name="didId"
                options={[
                  { value: "", label: "— None —" },
                  ...(dids?.map((d) => ({ value: d.id, label: d.e164Number })) ?? []),
                ]}
                error={form.formState.errors.didId?.message}
                control={form.control}
              />

              <div className="space-y-2">
                <Label className="text-sm font-medium">Content Mode</Label>
                <div className="flex flex-wrap gap-2">
                  {CONTENT_MODE_OPTIONS.map((mode) => (
                    <label
                      key={mode.value}
                      className="inline-flex items-center gap-1.5 rounded border p-2 text-sm hover:bg-accent cursor-pointer"
                    >
                      <input
                        type="radio"
                        {...form.register("contentMode")}
                        value={mode.value}
                        className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                      />
                      <span>{mode.label}</span>
                    </label>
                  ))}
                </div>
                {form.formState.errors.contentMode?.message && (
                  <p className="text-sm text-destructive" role="alert">
                    {form.formState.errors.contentMode.message}
                  </p>
                )}
              </div>

              {contentMode === "AUDIO" && (
                <SelectField
                  label="Audio Asset"
                  name="audioAssetId"
                  options={[
                    { value: "", label: "— Select audio asset —" },
                    ...(audioAssets?.map((a) => ({ value: a.id, label: a.name })) ?? []),
                  ]}
                  error={form.formState.errors.audioAssetId?.message}
                  control={form.control}
                />
              )}
              {contentMode === "TTS" && (
                <SelectField
                  label="TTS Template"
                  name="ttsTemplateId"
                  options={[
                    { value: "", label: "— Select TTS template —" },
                    ...(ttsTemplates?.map((t) => ({ value: t.id, label: t.name })) ?? []),
                  ]}
                  error={form.formState.errors.ttsTemplateId?.message}
                  control={form.control}
                />
              )}

              <div className="space-y-2">
                <TextareaField
                  label="Type Config (JSON)"
                  registration={form.register("typeConfig")}
                  error={getError(form, "typeConfig")}
                  rows={6}
                  placeholder='{"key": "value"}'
                  readOnly={campaignType !== "DTMF" && campaignType !== "CONNECT_BY_AGENT"}
                />
                {(campaignType === "DTMF" || campaignType === "CONNECT_BY_AGENT") && (
                  <p className="text-sm text-muted-foreground">
                    Required for {campaignType} campaigns. Non-empty JSON object.
                  </p>
                )}
              </div>

              <TextareaField
                label="Integration Config (JSON, optional)"
                registration={form.register("integrationConfig")}
                error={getError(form, "integrationConfig")}
                rows={4}
                placeholder='{"webhookUrl": "https://..."}'
              />

              <div className="flex items-center gap-2 mb-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowSchedule(!showSchedule)}
                >
                  {showSchedule ? "Hide Schedule" : "Configure Schedule"}
                </Button>
              </div>
              {showSchedule && (
                <ScheduleForm form={form} />
              )}

              <div className="flex items-center gap-2 mb-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowRetry(!showRetry)}
                >
                  {showRetry ? "Hide Retry Policy" : "Configure Retry Policy"}
                </Button>
              </div>
              {showRetry && (
                <RetryPolicyForm form={form} />
              )}
            </div>

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Saving…
                  </>
                ) : (
                  "Save changes"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}

interface ScheduleFormProps {
  form: ReturnType<typeof useForm<UpdateCampaignValues>>;
}

function ScheduleForm({ form }: ScheduleFormProps) {
  const getError = (name: Path<UpdateCampaignValues>) => form.getFieldState(name).error?.message;
  return (
    <div className="space-y-4 p-4 border rounded-lg bg-muted/30">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Start Date"
          type="date"
          registration={form.register("schedule.startDate")}
          error={getError("schedule.startDate")}
        />
        <TextField
          label="End Date"
          type="date"
          registration={form.register("schedule.endDate")}
          error={getError("schedule.endDate")}
        />
        <TextField
          label="Start Time"
          type="time"
          registration={form.register("schedule.startTime")}
          error={getError("schedule.startTime")}
        />
        <TextField
          label="End Time"
          type="time"
          registration={form.register("schedule.endTime")}
          error={getError("schedule.endTime")}
        />
      </div>
      <TextField
        label="Timezone (IANA)"
        placeholder="America/New_York"
        registration={form.register("schedule.timezone")}
        error={getError("schedule.timezone")}
      />
      <div className="space-y-2">
        <Label className="text-sm font-medium">Allowed Days of Week</Label>
        <div className="flex flex-wrap gap-2">
          {DAY_OPTIONS.map((day) => (
            <label
              key={day.value}
              className="inline-flex items-center gap-1.5 rounded border p-2 text-sm hover:bg-accent cursor-pointer"
            >
              <input
                type="checkbox"
                {...form.register("schedule.allowedDaysOfWeek")}
                value={day.value}
                className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
              />
              <span>{day.label}</span>
            </label>
          ))}
        </div>
      </div>
    </div>
  );
}

interface RetryPolicyFormProps {
  form: ReturnType<typeof useForm<UpdateCampaignValues>>;
}

function RetryPolicyForm({ form }: RetryPolicyFormProps) {
  const getError = (name: Path<UpdateCampaignValues>) => form.getFieldState(name).error?.message;
  return (
    <div className="space-y-4 p-4 border rounded-lg bg-muted/30">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Max Attempts"
          type="number"
          min="0"
          max="10"
          registration={form.register("retryPolicy.maxAttempts", { valueAsNumber: true })}
          error={getError("retryPolicy.maxAttempts")}
        />
        <TextField
          label="Interval (seconds)"
          type="number"
          min="1"
          max="604800"
          registration={form.register("retryPolicy.intervalSeconds", { valueAsNumber: true })}
          error={getError("retryPolicy.intervalSeconds")}
        />
      </div>
    </div>
  );
}