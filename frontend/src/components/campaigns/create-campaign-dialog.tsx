"use client";

import { useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useQueryClient } from "@tanstack/react-query";
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
import {
  FieldDescription,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { SelectField } from "@/components/forms/select-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { createCampaign, campaignsKeys, getDidsForCampaign, getContactGroupsForCampaign, getAudioAssetsForCampaign, getTtsTemplatesForCampaign } from "@/lib/api/campaigns";
import type { CreateCampaignValues } from "@/lib/schemas/campaign-mutation";
import {
  createCampaignSchema,
  toCreateCampaignPayload,
} from "@/lib/schemas/campaign-mutation";
import { useQuery } from "@tanstack/react-query";

const EMPTY_VALUES: CreateCampaignValues = {
  name: "",
  description: "",
  campaignType: "PLAYFILE",
  runMode: "ONE_TIME",
  contactGroupId: "",
  didId: "",
  contentMode: undefined,
  audioAssetId: "",
  ttsTemplateId: "",
  schedule: undefined,
  retryPolicy: undefined,
  typeConfig: undefined,
  integrationConfig: undefined,
};

interface CreateCampaignDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const CAMPAIGN_TYPE_OPTIONS = [
  { value: "PLAYFILE", label: "Play File" },
  { value: "DTMF", label: "DTMF" },
  { value: "CONNECT_BY_AGENT", label: "Connect by Agent" },
] as const;

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
 * Campaign creation dialog (POST /api/v1/campaigns).
 */
export function CreateCampaignDialog({ open, onOpenChange }: CreateCampaignDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);
  const [showSchedule, setShowSchedule] = useState(false);
  const [showRetry, setShowRetry] = useState(false);

  const form = useForm<CreateCampaignValues>({
    resolver: zodResolver(createCampaignSchema),
    defaultValues: EMPTY_VALUES,
  });

  const campaignType = form.watch("campaignType");
  const contentMode = form.watch("contentMode");
  // const runMode = form.watch("runMode"); // unused

  // Fetch reference data for selection dropdowns
  const { data: dids } = useQuery({
    queryKey: ["dids", "for-campaign"],
    queryFn: getDidsForCampaign,
    staleTime: 5 * 60 * 1000, // 5 minutes
  });

  const { data: contactGroups } = useQuery({
    queryKey: ["contact-groups", "for-campaign"],
    queryFn: getContactGroupsForCampaign,
    staleTime: 5 * 60 * 1000,
  });

  const { data: audioAssets } = useQuery({
    queryKey: ["audio-assets", "for-campaign"],
    queryFn: getAudioAssetsForCampaign,
    staleTime: 5 * 60 * 1000,
  });

  const { data: ttsTemplates } = useQuery({
    queryKey: ["tts-templates", "for-campaign"],
    queryFn: getTtsTemplatesForCampaign,
    staleTime: 5 * 60 * 1000,
  });

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
      setShowSchedule(false);
      setShowRetry(false);
    }
    onOpenChange(nextOpen);
  }

  async function onSubmit(values: CreateCampaignValues) {
    setAlert(null);
    try {
      const campaign = await createCampaign(toCreateCampaignPayload(values));
      toast.success("Campaign created", {
        description: `${campaign.name} was created as a draft.`,
      });
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      handleOpenChange(false);
      // Navigate to detail view
      window.location.href = `/campaigns/${campaign.id}`;
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      if (fieldError.field in createCampaignSchema.shape) {
        form.setError(fieldError.field as keyof CreateCampaignValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto max-w-4xl">
        <DialogHeader>
          <DialogTitle>Create Campaign</DialogTitle>
          <DialogDescription>
            Configure a new outbound campaign. Type determines required fields.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive mb-4">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Basic Information</FieldLegend>
              <TextField
                label="Name"
                placeholder="Q4 Promotional Campaign"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                placeholder="Optional description of this campaign"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={3}
              />
              <SelectField
                label="Campaign Type"
                name="campaignType"
                options={CAMPAIGN_TYPE_OPTIONS}
                error={form.formState.errors.campaignType?.message}
                control={form.control}
              />
              <SelectField
                label="Run Mode"
                name="runMode"
                options={RUN_MODE_OPTIONS}
                error={form.formState.errors.runMode?.message}
                control={form.control}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>References</FieldLegend>
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
            </FieldSet>

            <FieldSet>
              <FieldLegend>Content</FieldLegend>
              <FieldDescription>
                Select how the campaign delivers content. AUDIO requires an approved audio asset.
                TTS requires an approved TTS template. CONNECT_BY_AGENT does not require content.
              </FieldDescription>
              <SelectField
                label="Content Mode"
                name="contentMode"
                options={[{ value: "", label: "— Select mode —" }, ...CONTENT_MODE_OPTIONS]}
                error={form.formState.errors.contentMode?.message}
                control={form.control}
              />
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
            </FieldSet>

            <FieldSet>
              <FieldLegend>Type-Specific Configuration</FieldLegend>
              {(campaignType === "DTMF" || campaignType === "CONNECT_BY_AGENT") && (
                <div className="space-y-2">
                  <p className="text-sm text-muted-foreground">
                    {campaignType} campaigns require a non-empty JSON configuration object.
                  </p>
                  <TextareaField
                    label="Type Config (JSON)"
                    placeholder='{"key": "value"}'
                    registration={form.register("typeConfig")}
                    error={getError(form, "typeConfig")}
                    rows={6}
                  />
                </div>
              )}
              <TextareaField
                label="Integration Config (JSON, optional)"
                placeholder='{"webhookUrl": "https://..."}'
                registration={form.register("integrationConfig")}
                error={getError(form, "integrationConfig")}
                rows={4}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Schedule</FieldLegend>
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
            </FieldSet>

            <FieldSet>
              <FieldLegend>Retry Policy</FieldLegend>
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
            </FieldSet>

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => handleOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Creating…
                  </>
                ) : (
                  "Create Campaign"
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
  form: ReturnType<typeof useForm<CreateCampaignValues>>;
}

function ScheduleForm({ form }: ScheduleFormProps) {
  return (
    <div className="space-y-4 p-4 border rounded-lg bg-muted/30">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Start Date"
          type="date"
          registration={form.register("schedule.startDate")}
          error={getError(form, "schedule.startDate")}
        />
        <TextField
          label="End Date"
          type="date"
          registration={form.register("schedule.endDate")}
          error={getError(form, "schedule.endDate")}
        />
        <TextField
          label="Start Time"
          type="time"
          registration={form.register("schedule.startTime")}
          error={getError(form, "schedule.startTime")}
        />
        <TextField
          label="End Time"
          type="time"
          registration={form.register("schedule.endTime")}
          error={getError(form, "schedule.endTime")}
        />
      </div>
      <TextField
        label="Timezone (IANA)"
        placeholder="America/New_York"
        description="Required when any schedule field is set. Must be a valid IANA identifier."
        registration={form.register("schedule.timezone")}
        error={getError(form, "schedule.timezone")}
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
  form: ReturnType<typeof useForm<CreateCampaignValues>>;
}

function RetryPolicyForm({ form }: RetryPolicyFormProps) {
  return (
    <div className="space-y-4 p-4 border rounded-lg bg-muted/30">
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Max Attempts"
          type="number"
          min="0"
          max="10"
          registration={form.register("retryPolicy.maxAttempts", { valueAsNumber: true })}
          error={getError(form, "retryPolicy.maxAttempts")}
        />
        <TextField
          label="Interval (seconds)"
          type="number"
          min="1"
          max="604800"
          registration={form.register("retryPolicy.intervalSeconds", { valueAsNumber: true })}
          error={getError(form, "retryPolicy.intervalSeconds")}
        />
      </div>
    </div>
  );
}