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
import { FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { SelectField } from "@/components/forms/select-field";
import { toApiError } from "@/lib/api/error";
import { campaignsKeys, changeCampaignStatus, CAMPAIGN_LEGAL_TRANSITIONS } from "@/lib/api/campaigns";
import type { CampaignResponse } from "@/lib/api/contracts";
import type { UpdateCampaignStatusValues } from "@/lib/schemas/campaign-mutation";
import {
  updateCampaignStatusSchema,
  toUpdateCampaignStatusPayload,
} from "@/lib/schemas/campaign-mutation";

interface ChangeStatusDialogProps {
  campaign: CampaignResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Campaign status transition dialog (PATCH /api/v1/campaigns/{id}/status).
 * Only legal transitions are shown.
 */
export function ChangeStatusDialog({
  campaign,
  open,
  onOpenChange,
}: ChangeStatusDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const legalTransitions = campaign ? CAMPAIGN_LEGAL_TRANSITIONS[campaign.status] ?? [] : [];
  const isTerminal = campaign
    ? ["COMPLETED", "FAILED", "ARCHIVED"].includes(campaign.status)
    : false;

  const form = useForm<UpdateCampaignStatusValues>({
    resolver: zodResolver(updateCampaignStatusSchema),
    defaultValues: { status: legalTransitions[0] || "SCHEDULED" },
  });

  if (!campaign || isTerminal || legalTransitions.length === 0) return null;

  async function onSubmit(values: UpdateCampaignStatusValues) {
    if (!campaign) return;
    setAlert(null);
    try {
      await changeCampaignStatus(campaign.id, toUpdateCampaignStatusPayload(values));
      toast.success("Campaign status updated", {
        description: `Campaign moved from ${campaign.status} to ${values.status}.`,
      });
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.status === 409) {
        setAlert(apiError.message);
      } else {
        setAlert(apiError.message);
      }
    }
  }

  const pending = form.formState.isSubmitting;

  const transitionOptions = legalTransitions.map((status) => ({
    value: status,
    label: status,
  }));

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Change Campaign Status</DialogTitle>
          <DialogDescription>
            Current status: <strong>{campaign.status}</strong>. Select a valid transition.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive mb-4">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <SelectField
              label="New Status"
              name="status"
              options={transitionOptions}
              error={form.formState.errors.status?.message}
              control={form.control}
            />

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending} variant="default">
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Updating…
                  </>
                ) : (
                  "Change Status"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}