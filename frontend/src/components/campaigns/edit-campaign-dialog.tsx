"use client";

import { useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
import { FieldGroup, FieldLegend, FieldSet } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { SelectField } from "@/components/forms/select-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { updateCampaign, campaignsKeys } from "@/lib/api/campaigns";
import {
  getCampaignAudioAssets,
  getCampaignContactGroups,
  getCampaignDids,
  buildQueueSelectionOptions,
  filterByTenant,
  getCampaignQueues,
  isReferencePageTruncated,
} from "@/lib/api/campaign-references";
import { Capability } from "@/lib/auth/capabilities";
import { useCan } from "@/lib/auth/use-can";
import { campaignTypePlaysMedia } from "@/lib/schemas/campaign-config";
import { isEditable } from "@/lib/domain/campaign-lifecycle";
import { connectByAgentQueueId } from "@/lib/api/contracts";
import type { CampaignResponse } from "@/lib/api/contracts";
import type { UpdateCampaignValues } from "@/lib/schemas/campaign-mutation";
import {
  updateCampaignSchema,
  toUpdateCampaignPayload,
} from "@/lib/schemas/campaign-mutation";
import { CampaignContentFields } from "@/components/campaigns/campaign-content-fields";
import { CampaignScheduleField } from "@/components/campaigns/campaign-schedule-field";
import { CampaignRetryPolicyField } from "@/components/campaigns/campaign-retry-policy-field";
import { CampaignIntegrationField } from "@/components/campaigns/campaign-integration-field";
import {
  ConnectByAgentTypeConfigField,
  DtmfTypeConfigField,
  MissedCallTypeConfigField,
} from "@/components/campaigns/campaign-type-config-fields";

/**
 * Campaign update (PUT /api/v1/campaigns/{id}).
 *
 * ## The rule that decides whether this dialog can open at all
 *
 * VERIFIED `CampaignService.update` L218: `lifecyclePolicy.assertEditable(entity)`
 * runs before any validation, and `CampaignLifecyclePolicy.EDITABLE_STATUSES` is
 * `Set.of(DRAFT)`. Anything else is a 409
 * "Campaign configuration can only be modified while the campaign is in DRAFT
 * state".
 *
 * The F1 UI offered Edit on every campaign in the list and on the detail page.
 * For a SCHEDULED, RUNNING, PAUSED, COMPLETED, FAILED or ARCHIVED campaign that
 * was a form the user could fill in completely and then be rejected by. The
 * list and detail now gate the control, and this dialog refuses to render
 * otherwise — see the F4 doc §12, drift item D6.
 *
 * ## PUT semantics, stated plainly
 *
 * VERIFIED the controller: "Replaces the mutable configuration (PUT semantics:
 * omitted optional blocks are cleared)." So clearing a field in this form really
 * does clear it on the server. The dialog says so rather than implying a merge.
 *
 * ## `callOnWhitelistNumbers` is displayed, not editable
 *
 * VERIFIED: `UpdateCampaignRequest` has no such field and `CampaignMapper
 * .updateEntity` never reads it, so it is create-only and immutable. The F1 form
 * offered a control that the server silently discarded.
 *
 * ## Updates do NOT affect existing executions
 *
 * VERIFIED `CampaignConfigurationService.createExecutionSnapshot`: every
 * execution-affecting field is frozen into an immutable
 * `CampaignExecutionConfiguration` row when the execution is created, and that
 * entity is `@Immutable`. So editing a campaign afterwards cannot change what an
 * existing execution dials — which is also why only DRAFT is editable, since a
 * running campaign is already being executed against its own snapshot.
 */

const RUN_MODE_OPTIONS = [
  { value: "ONE_TIME", label: "One time" },
  { value: "RECURRING", label: "Recurring" },
] as const;

export function EditCampaignDialog({
  campaign,
  open,
  onOpenChange,
}: {
  campaign: CampaignResponse | null;
  /**
   * F4: F1 hard-coded `<Dialog open …>` and relied on the parent mounting the
   * component only while editing. The detail view mounts it unconditionally
   * alongside the other dialogs, so the open state has to be a real prop —
   * otherwise the editor is permanently on screen.
   */
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);
  const [showSchedule, setShowSchedule] = useState(true);
  const [showRetry, setShowRetry] = useState(false);
  const [showIntegration, setShowIntegration] = useState(false);
  const { can } = useCan();

  const form = useForm<UpdateCampaignValues>({
    resolver: zodResolver(updateCampaignSchema),
    values: campaign
      ? {
          name: campaign.name,
          description: campaign.description ?? "",
          runMode: campaign.runMode,
          contactGroupId: campaign.contactGroupId ?? "",
          didId: campaign.didId ?? "",
          audioAssetId: campaign.audioAssetId ?? "",
          contentMode: campaign.contentMode ?? undefined,
          schedule: campaign.schedule ?? undefined,
          retryPolicy: campaign.retryPolicy ?? undefined,
          typeConfig: campaign.typeConfig ?? undefined,
          integrationConfig: campaign.integrationConfig ?? undefined,
          maxCallDurationSeconds: campaign.maxCallDurationSeconds ?? undefined,
          maxDailyAttempts: campaign.maxDailyAttempts ?? undefined,
          dailyDialLimit: campaign.dailyDialLimit ?? undefined,
        }
      : undefined,
    mode: "onBlur",
  });

  const didsQuery = useQuery({
    queryKey: ["campaign-refs", "dids", "edit"],
    queryFn: getCampaignDids,
    enabled: Boolean(campaign) && can(Capability.DID_VIEW),
    staleTime: 60_000,
  });
  const groupsQuery = useQuery({
    queryKey: ["campaign-refs", "contact-groups", "edit"],
    queryFn: getCampaignContactGroups,
    enabled: Boolean(campaign) && can(Capability.CONTACT_VIEW),
    staleTime: 60_000,
  });
  const audioQuery = useQuery({
    queryKey: ["campaign-refs", "audio-assets", "edit"],
    queryFn: getCampaignAudioAssets,
    enabled: Boolean(campaign) && can(Capability.AUDIO_VIEW),
    staleTime: 60_000,
  });
  const queuesQuery = useQuery({
    queryKey: ["campaign-refs", "queues", "edit"],
    queryFn: getCampaignQueues,
    enabled:
      Boolean(campaign) &&
      campaign?.campaignType === "CONNECT_BY_AGENT" &&
      can(Capability.QUEUE_VIEW),
    staleTime: 60_000,
  });

  const updateMutation = useMutation({
    mutationFn: (values: UpdateCampaignValues) => {
      if (!campaign) throw new Error("No campaign selected");
      return updateCampaign(campaign.id, toUpdateCampaignPayload(values));
    },
    onSuccess: async () => {
      toast.success("Campaign updated");
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      setAlert(null);
      onOpenChange(false);
    },
    onError: (error: unknown) => {
      const apiError = toApiError(error);
      const applied = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(updateCampaignSchema.shape),
        (field, message) =>
          form.setError(field as keyof UpdateCampaignValues, { message }),
      );
      if (applied === 0) setAlert(apiError.message);
    },
  });

  // F5: preserve the campaign's current queue when it is no longer ACTIVE.
  //
  // `getCampaignQueues` returns only ACTIVE queues, so a queue that has since
  // been deactivated drops out of the option list. Without this the select would
  // hold a value matching no option — rendering as though nothing were chosen
  // while still submitting the stored id. `buildQueueSelectionOptions` re-adds it
  // with an honest label and reports the condition so the field can explain it.

  /**
   * F5.1 — the edit picker must be narrowed to the CAMPAIGN's tenant, exactly as
   * the create picker is narrowed to the chosen target tenant.
   *
   * The create dialog has always done this (`filterByTenant(rows, targetTenantId)`)
   * because a platform or reseller caller creates into a tenant it chooses. The
   * edit dialog had no equivalent and mapped its query results straight into the
   * options, so a platform or reseller user editing a tenant-T draft was shown
   * every ASSIGNED DID, contact group and APPROVED audio asset the hierarchy
   * list returned — including other tenants' — and any of them would be refused
   * by `validateDidReference` / `validateContactGroupReference` /
   * `validateContentReferences` on submit.
   *
   * `campaign.tenantId` is the authoritative target and needs no tenant picker,
   * which is what makes this strictly better than create's conditional filter: it
   * is correct for every scope. For a TENANT caller the list is already scoped to
   * their own tenant and this filter is a no-op.
   *
   * Backend validation remains authoritative — this narrows an already-authorized
   * list and grants nothing.
   */
  const campaignTenantId = campaign?.tenantId ?? null;
  const didRows = filterByTenant(didsQuery.data ?? [], campaignTenantId);
  const groupRows = filterByTenant(groupsQuery.data ?? [], campaignTenantId);
  const audioRows = filterByTenant(audioQuery.data ?? [], campaignTenantId);
  const queueRows = filterByTenant(queuesQuery.data ?? [], campaignTenantId);

  const queueSelection = buildQueueSelectionOptions(
    queueRows,
    connectByAgentQueueId(campaign?.typeConfig ?? null),
  );

  if (!campaign) return null;

  // The authoritative check. A campaign that left DRAFT between the click and
  // the render is refused here rather than producing a doomed form.
  if (!isEditable(campaign.status)) {
    return (
      <Dialog open={open} onOpenChange={onOpenChange}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Campaign is not editable</DialogTitle>
            <DialogDescription>
              Configuration can only be changed while a campaign is a draft. This
              one is {campaign.status}. Return it to draft first if you need to
              change it.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    );
  }

  const playsMedia = campaignTypePlaysMedia(campaign.campaignType);
  const pending = updateMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Edit campaign</DialogTitle>
          <DialogDescription>
            The campaign type is fixed for this campaign&apos;s lifetime. Any
            optional section you leave empty is CLEARED on the server, not left
            as it was. Existing executions keep the configuration they were
            created with.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-destructive mb-4 text-sm font-medium">
            {alert}
          </p>
        ) : null}

        <form
          onSubmit={form.handleSubmit((values) => updateMutation.mutate(values))}
          noValidate
        >
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Identity</FieldLegend>
              <TextField
                label="Name"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={2}
              />
              <div className="flex flex-col gap-1.5">
                <FieldLegend>Campaign type</FieldLegend>
                <p className="bg-muted text-muted-foreground w-fit rounded-md px-3 py-1.5 font-mono text-sm">
                  {campaign.campaignType}
                </p>
                <p className="text-muted-foreground text-sm">
                  Immutable. The server has no field to change it, so it is not
                  editable here either.
                </p>
              </div>
              <SelectField
                label="Run mode"
                name="runMode"
                options={[...RUN_MODE_OPTIONS]}
                description="A recurring campaign requires a schedule."
                error={form.formState.errors.runMode?.message}
                control={form.control}
              />
            </FieldSet>

            <CampaignContentFields
              campaignType={campaign.campaignType}
              audioOptions={[
                { value: "", label: "— Select an approved recording —" },
                ...audioRows.map((asset) => ({
                  value: asset.id,
                  label: asset.name,
                })),
              ]}
              audioTruncated={isReferencePageTruncated((audioQuery.data ?? []).length)}
              contactGroupOptions={groupRows.map((group) => ({
                value: group.id,
                label: `${group.name} (${group.memberCount})`,
              }))}
              contactGroupsTruncated={isReferencePageTruncated((groupsQuery.data ?? []).length)}

              didOptions={didRows.map((did) => ({
                value: did.id,
                label: did.e164Number,
              }))}
              didsTruncated={isReferencePageTruncated((didsQuery.data ?? []).length)}
              detachedContactGroup={
                campaign.contactGroupId &&
                !groupRows.some(
                  (group) => group.id === campaign.contactGroupId,
                )
                  ? { id: campaign.contactGroupId, label: "Current contact group" }
                  : null
              }
              detachedDid={
                campaign.didId &&
                !didRows.some((did) => did.id === campaign.didId)
                  ? { id: campaign.didId, label: "Current DID" }
                  : null
              }
            />

            {campaign.campaignType === "CONNECT_BY_AGENT" ? (
              <ConnectByAgentTypeConfigField
                name="typeConfig"
                queueOptions={queueSelection.options}
                queuesTruncated={isReferencePageTruncated((queuesQuery.data ?? []).length)}

                currentQueueUnavailable={queueSelection.currentQueueUnavailable}
                error={form.formState.errors.typeConfig?.message as string | undefined}
              />
            ) : null}
            {campaign.campaignType === "DTMF" ? (
              <DtmfTypeConfigField
                name="typeConfig"
                error={form.formState.errors.typeConfig?.message as string | undefined}
              />
            ) : null}
            {campaign.campaignType === "MISSED_CALL" ? (
              <MissedCallTypeConfigField
                name="typeConfig"
                error={form.formState.errors.typeConfig?.message as string | undefined}
              />
            ) : null}

            <FieldSet>
              <FieldLegend>Limits</FieldLegend>
              <TextField
                label="Maximum call duration (seconds)"
                type="number"
                inputMode="numeric"
                min={1}
                max={3600}
                description="Bounds an answered call from the moment it is answered. Empty uses the platform default of 300 seconds."
                registration={form.register("maxCallDurationSeconds", {
                  valueAsNumber: true,
                })}
                error={form.formState.errors.maxCallDurationSeconds?.message}
              />
              <TextField
                label="Daily attempts per contact"
                type="number"
                inputMode="numeric"
                min={1}
                max={10}
                description="Caps dispatches per contact per day across this tenant's campaigns. Empty uses the platform default of 10."
                registration={form.register("maxDailyAttempts", { valueAsNumber: true })}
                error={form.formState.errors.maxDailyAttempts?.message}
              />
              <TextField
                label="Daily dial limit"
                type="number"
                inputMode="numeric"
                min={1}
                max={3}
                description="Caps provider-accepted dials per contact per number. Empty uses the platform maximum of 3."
                registration={form.register("dailyDialLimit", { valueAsNumber: true })}
                error={form.formState.errors.dailyDialLimit?.message}
              />
              <div className="flex flex-col gap-1.5">
                <FieldLegend>Whitelist-only dialling</FieldLegend>
                <p className="text-sm">
                  {campaign.callOnWhitelistNumbers
                    ? "Only numbers on the tenant whitelist are dialled."
                    : "Numbers are dialled without a whitelist restriction."}
                </p>
                <p className="text-muted-foreground text-sm">
                  Set when the campaign was created. The server has no update
                  field for it, so it cannot be changed here.
                </p>
              </div>
            </FieldSet>

            <FieldSet>
              <div className="flex items-center justify-between gap-2">
                <div>
                  <FieldLegend>Schedule</FieldLegend>
                  <p className="text-muted-foreground text-sm">
                    A timezone is required before this campaign can execute.
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowSchedule((value) => !value)}
                  aria-expanded={showSchedule}
                >
                  {showSchedule ? "Hide schedule" : "Configure schedule"}
                </Button>
              </div>
              {showSchedule ? <CampaignScheduleField name="schedule" /> : null}
            </FieldSet>

            <FieldSet>
              <div className="flex items-center justify-between gap-2">
                <div>
                  <FieldLegend>Retry policy</FieldLegend>
                  <p className="text-muted-foreground text-sm">
                    Optional. Empty means a failed call is not retried.
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowRetry((value) => !value)}
                  aria-expanded={showRetry}
                >
                  {showRetry ? "Hide retries" : "Configure retries"}
                </Button>
              </div>
              {showRetry ? <CampaignRetryPolicyField name="retryPolicy" /> : null}
            </FieldSet>

            <FieldSet>
              <div className="flex items-center justify-between gap-2">
                <div>
                  <FieldLegend>Integration</FieldLegend>
                  <p className="text-muted-foreground text-sm">
                    Configuration only. Nothing is delivered and no report is
                    generated.
                  </p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowIntegration((value) => !value)}
                  aria-expanded={showIntegration}
                >
                  {showIntegration ? "Hide integration" : "Configure integration"}
                </Button>
              </div>
              {showIntegration ? (
                <CampaignIntegrationField name="integrationConfig" />
              ) : null}
            </FieldSet>

            {playsMedia && campaign.ttsTemplateId ? (
              <FieldSet>
                <FieldLegend>Text-to-speech reference</FieldLegend>
                <p className="text-muted-foreground font-mono text-sm">
                  {campaign.ttsTemplateId}
                </p>
                <p className="text-muted-foreground text-sm">
                  This campaign stores a TTS template, but the platform has no TTS
                  playback runtime, so it cannot execute. Saving the form clears
                  the reference, because the server refuses TTS content for this
                  type.
                </p>
              </FieldSet>
            ) : null}

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
