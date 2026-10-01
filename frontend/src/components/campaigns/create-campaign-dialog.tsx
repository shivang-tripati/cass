"use client";

import { useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
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
import { createCampaign, campaignsKeys } from "@/lib/api/campaigns";
import {
  filterByTenant,
  filterTtsForTenant,
  getCampaignAudioAssets,
  getCampaignContactGroups,
  getCampaignDids,
  getCampaignQueues,
  isReferencePageTruncated,
  REFERENCE_DATA_PAGE_SIZE,
} from "@/lib/api/campaign-references";
import { getTenants } from "@/lib/api/tenants";
import { Capability } from "@/lib/auth/capabilities";
import { useCan } from "@/lib/auth/use-can";
import {
  campaignCreateTargetFor,
  canCreateCampaign,
  requiresTargetTenant,
} from "@/lib/auth/campaign-gates";
import { useOperatingContext } from "@/lib/auth/operating-context";
import type { CreateCampaignValues } from "@/lib/schemas/campaign-mutation";
import {
  createCampaignSchema,
  toCreateCampaignPayload,
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
 * Campaign creation (POST /api/v1/campaigns).
 *
 * ## What F4 corrected in this dialog
 *
 *  1. **MISSED_CALL was missing entirely.** VERIFIED: the backend enum has four
 *     constants and `MissedCallCampaignConfig` is fully implemented. F1 added it
 *     to the *filter* and the *badge* but never to this list, so a MISSED_CALL
 *     campaign could not be created at all. See F4 doc §12, drift item D5.
 *  2. **TTS was offered as a content mode** and always 400s. Removed; see the
 *     long note in `campaign-content-fields.tsx`.
 *  3. **Type and integration config were free-text JSON.** They are typed
 *     editors now, because every backend parser rejects unknown fields.
 *  4. **No target-tenant picker.** `POST /campaigns` requires a resolvable
 *     tenant, and for platform/reseller callers that means `?tenantId=`. The
 *     picker is shown only to those scopes, because for a tenant caller the
 *     parameter is ignored.
 *  5. **`window.location.href`** on success became `router.push`, so the SPA is
 *     not torn down and the campaign list cache survives.
 *  6. The **600-second interval** in the retry editor now matches the server's
 *     5999 ceiling instead of a stale 604800.
 *
 * ## What this dialog deliberately does NOT do
 *
 * No IVR editor. `POST /campaigns/{id}/ivr-tree` is the only way to produce an
 * `ivr` type config, it requires `IVR_MANAGE`, and no migration seeds that
 * capability — so the endpoint answers 403 for every role. See the F4 doc §9.
 */
const EMPTY_VALUES: CreateCampaignValues = {
  name: "",
  description: "",
  campaignType: "PLAYFILE",
  runMode: "ONE_TIME",
  contactGroupId: "",
  didId: "",
  audioAssetId: "",
  callOnWhitelistNumbers: false,
};

/** VERIFIED `CampaignType` — four constants, and each one's label names what it does. */
const CAMPAIGN_TYPE_OPTIONS = [
  { value: "PLAYFILE", label: "Play an audio recording" },
  { value: "DTMF", label: "Play audio and collect DTMF digits" },
  { value: "CONNECT_BY_AGENT", label: "Connect the caller to an agent" },
  { value: "MISSED_CALL", label: "Let the call ring out, then end it" },
] as const;

const RUN_MODE_OPTIONS = [
  { value: "ONE_TIME", label: "One time" },
  { value: "RECURRING", label: "Recurring" },
] as const;

interface CreateCampaignDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateCampaignDialog({ open, onOpenChange }: CreateCampaignDialogProps) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [alert, setAlert] = useState<string | null>(null);
  const [showSchedule, setShowSchedule] = useState(false);
  const [showRetry, setShowRetry] = useState(false);
  const [showIntegration, setShowIntegration] = useState(false);
  const { can, user } = useCan();
  const { scope, tenantId: contextTenantId } = useOperatingContext();

  const form = useForm<CreateCampaignValues>({
    resolver: zodResolver(createCampaignSchema),
    defaultValues: EMPTY_VALUES,
    mode: "onBlur",
  });

  const campaignType = form.watch("campaignType");
  const targetRequired = requiresTargetTenant(scope);

  // Target tenants for platform/reseller callers. VERIFIED `CampaignService
  // .create` requires the target to exist and be ACTIVE, so ACTIVE is filtered
  // client-side from the ACTIVE-scoped list the backend already returns.
  const tenantsQuery = useQuery({
    queryKey: ["campaign-create-targets"],
    queryFn: async () => {
      const page = await getTenants({
        page: 0,
        size: REFERENCE_DATA_PAGE_SIZE,
        sortField: "name",
        sortDirection: "asc",
        status: "ACTIVE",
      });
      return page.items;
    },
    enabled: open && targetRequired && can(Capability.TENANT_VIEW),
  });

  const targetTenantId = form.watch("targetTenantId") || null;
  // A tenant caller's own context decides; the parameter is ignored for them.
  const effectiveTarget = targetRequired ? targetTenantId : contextTenantId;

  const didsQuery = useQuery({
    queryKey: ["campaign-refs", "dids", effectiveTarget],
    queryFn: getCampaignDids,
    enabled: open && can(Capability.DID_VIEW),
    staleTime: 60_000,
  });
  const groupsQuery = useQuery({
    queryKey: ["campaign-refs", "contact-groups", effectiveTarget],
    queryFn: getCampaignContactGroups,
    enabled: open && can(Capability.CONTACT_VIEW),
    staleTime: 60_000,
  });
  const audioQuery = useQuery({
    queryKey: ["campaign-refs", "audio-assets", effectiveTarget],
    queryFn: getCampaignAudioAssets,
    enabled: open && can(Capability.AUDIO_VIEW),
    staleTime: 60_000,
  });
  const queuesQuery = useQuery({
    queryKey: ["campaign-refs", "queues", effectiveTarget],
    queryFn: getCampaignQueues,
    enabled: open && campaignType === "CONNECT_BY_AGENT" && can(Capability.QUEUE_VIEW),
    staleTime: 60_000,
  });

  const didRows = filterByTenant(didsQuery.data ?? [], targetRequired ? targetTenantId : null);
  const groupRows = filterByTenant(groupsQuery.data ?? [], targetRequired ? targetTenantId : null);
  const audioRows = filterByTenant(audioQuery.data ?? [], targetRequired ? targetTenantId : null);
  const queueRows = filterByTenant(queuesQuery.data ?? [], targetRequired ? targetTenantId : null);
  // Retained so a TTS-future change has its filter in place; no TTS picker today.
  void filterTtsForTenant;

  const createMutation = useMutation({
    mutationFn: (values: CreateCampaignValues) => {
      const target = campaignCreateTargetFor(scope, targetTenantId);
      return createCampaign(
        toCreateCampaignPayload(values),
        target.tenantId ?? undefined,
      );
    },
    onSuccess: async (campaign) => {
      toast.success("Campaign created", {
        description: `${campaign.name} was created as a draft. Edit it, then schedule it.`,
      });      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      form.reset(EMPTY_VALUES);
      setAlert(null);
      onOpenChange(false);
      router.push(`/campaigns/${campaign.id}`);
    },
    onError: (error: unknown) => {
      const apiError = toApiError(error);
      // VERIFIED: campaign write failures come from `BusinessException` with
      // `CommonErrorCode.VALIDATION_ERROR`, which carries a summary message and
      // NO `errors[]` — the business rules in `validateConfiguration` are
      // service-level, not bean-validation, so there is no field to attach. The
      // summary is therefore the honest text in every case; the mapping below
      // only helps if a future bean constraint starts reporting fields.
      const applied = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(createCampaignSchema.shape),
        (field, message) =>
          form.setError(field as keyof CreateCampaignValues, { message }),
      );
      if (applied === 0) setAlert(apiError.message);
    },
  });

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
      setShowSchedule(false);
      setShowRetry(false);
      setShowIntegration(false);
    }
    onOpenChange(nextOpen);
  }

  if (!canCreateCampaign(user)) {
    // Defence in depth: the list view already gates the Create button on
    // CAMPAIGN_MANAGE, and this re-checks through the same function the doc
    // comment points at so the dialog cannot be opened by a path that skipped it.
    return null;
  }

  const pending = createMutation.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Create campaign</DialogTitle>
          <DialogDescription>
            A new campaign always starts as a draft. Drafts are the only editable
            state, and they are not executable until you schedule them.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-destructive mb-4 text-sm font-medium">
            {alert}
          </p>
        ) : null}

        <form
          onSubmit={form.handleSubmit((values) => createMutation.mutate(values))}
          noValidate
        >
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Identity</FieldLegend>
              <TextField
                label="Name"
                placeholder="October reminder"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                placeholder="Optional"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={2}
              />
              <SelectField
                label="Campaign type"
                name="campaignType"
                options={[...CAMPAIGN_TYPE_OPTIONS]}
                error={form.formState.errors.campaignType?.message}
                control={form.control}
              />
              <SelectField
                label="Run mode"
                name="runMode"
                options={[...RUN_MODE_OPTIONS]}
                description="A recurring campaign requires a schedule."
                error={form.formState.errors.runMode?.message}
                control={form.control}
              />
            </FieldSet>

            {targetRequired ? (
              <FieldSet>
                <FieldLegend>Tenant</FieldLegend>
                <SelectField
                  label="Create this campaign in"
                  name="targetTenantId"
                  options={[
                    { value: "", label: "— Select a tenant —" },
                    ...(tenantsQuery.data ?? []).map((tenant) => ({
                      value: tenant.id,
                      label: tenant.name,
                    })),
                  ]}
                  description="Your account has no tenant of its own, so the campaign must be created in one you are authorized for. It can only be an active tenant, and every reference you pick must belong to it."
                  error={form.formState.errors.targetTenantId?.message}
                  control={form.control}
                />
              </FieldSet>
            ) : null}

            <CampaignContentFields
              campaignType={campaignType}
              audioOptions={[
                { value: "", label: "— Select an approved recording —" },
                ...audioRows.map((asset) => ({ value: asset.id, label: asset.name })),
              ]}
              audioTruncated={isReferencePageTruncated(audioRows.length)}
              contactGroupOptions={groupRows.map((group) => ({
                value: group.id,
                label: `${group.name} (${group.memberCount})`,
              }))}
              contactGroupsTruncated={isReferencePageTruncated(groupRows.length)}
              didOptions={didRows.map((did) => ({
                value: did.id,
                label: did.e164Number,
              }))}
              didsTruncated={isReferencePageTruncated(didRows.length)}
            />

            {campaignType === "CONNECT_BY_AGENT" ? (
              <ConnectByAgentTypeConfigField
                name="typeConfig"
                queueOptions={queueRows.map((queue) => ({
                  value: queue.id,
                  label: queue.name,
                }))}
                queuesTruncated={isReferencePageTruncated(queueRows.length)}
                error={form.formState.errors.typeConfig?.message as string | undefined}
              />
            ) : null}
            {campaignType === "DTMF" ? (
              <DtmfTypeConfigField
                name="typeConfig"
                error={form.formState.errors.typeConfig?.message as string | undefined}
              />
            ) : null}
            {campaignType === "MISSED_CALL" ? (
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
                description="Bounds an answered call from the moment it is answered. Leave empty for the platform default of 300 seconds. Not a ring timeout and not a playback length."
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
                description="Caps dispatches per contact per day across all of this tenant's campaigns, so rotating the number cannot reset it. Leave empty for the platform default of 10."
                registration={form.register("maxDailyAttempts", { valueAsNumber: true })}
                error={form.formState.errors.maxDailyAttempts?.message}
              />
              <TextField
                label="Daily dial limit"
                type="number"
                inputMode="numeric"
                min={1}
                max={3}
                description="Caps provider-accepted dials per contact per number. Leave empty for the platform maximum of 3."
                registration={form.register("dailyDialLimit", { valueAsNumber: true })}
                error={form.formState.errors.dailyDialLimit?.message}
              />
              <label className="inline-flex cursor-pointer items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  {...form.register("callOnWhitelistNumbers")}
                  className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                />
                <span>Only dial numbers on the tenant whitelist</span>
              </label>
              <p className="text-muted-foreground text-sm">
                This can only be set at creation. The server has no update field
                for it, so it cannot be changed afterwards.
              </p>
            </FieldSet>

            <Collapsible
              open={showSchedule}
              onToggle={setShowSchedule}
              label="Schedule"
              onLabel="Configure schedule"
              offLabel="Hide schedule"
              hint="Required before the campaign can be scheduled, and a timezone is required before it can execute at all."
            >
              <CampaignScheduleField name="schedule" />
            </Collapsible>

            <Collapsible
              open={showRetry}
              onToggle={setShowRetry}
              label="Retry policy"
              onLabel="Configure retries"
              offLabel="Hide retries"
              hint="Optional. Without one, a failed call is not retried."
            >
              <CampaignRetryPolicyField name="retryPolicy" />
            </Collapsible>

            <Collapsible
              open={showIntegration}
              onToggle={setShowIntegration}
              label="Integration"
              onLabel="Configure integration"
              offLabel="Hide integration"
              hint="Optional, and configuration only — nothing is delivered and no report is generated."
            >
              <CampaignIntegrationField name="integrationConfig" />
            </Collapsible>

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
                  "Create campaign"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function Collapsible({
  open,
  onToggle,
  label,
  onLabel,
  offLabel,
  hint,
  children,
}: {
  open: boolean;
  onToggle: (next: boolean) => void;
  label: string;
  onLabel: string;
  offLabel: string;
  hint: string;
  children: React.ReactNode;
}) {
  return (
    <FieldSet>
      <div className="flex items-center justify-between gap-2">
        <div>
          <FieldLegend>{label}</FieldLegend>
          <p className="text-muted-foreground text-sm">{hint}</p>
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => onToggle(!open)}
          aria-expanded={open}
        >
          {open ? offLabel : onLabel}
        </Button>
      </div>
      {open ? children : null}
    </FieldSet>
  );
}
