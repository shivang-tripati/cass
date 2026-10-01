"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
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
import { QueryErrorFromApiError } from "@/components/common/query-state";
import { toApiError } from "@/lib/api/error";
import { changeCampaignStatus, campaignsKeys } from "@/lib/api/campaigns";
import {
  CAMPAIGN_STATUS_DESCRIPTION,
  CAMPAIGN_STATUS_LABEL,
  CAMPAIGN_TRANSITION_CONSEQUENCE,
  availableTransitions,
} from "@/lib/domain/campaign-lifecycle";
import type { CampaignResponse, CampaignStatus } from "@/lib/api/contracts";

/**
 * Campaign lifecycle transition (PATCH /api/v1/campaigns/{id}/status).
 *
 * ## This dialog is where the F1 lifecycle model was wrong
 *
 * The F1 version offered `CAMPAIGN_LEGAL_TRANSITIONS[status]` in full. That map
 * is the backend's LEGAL set, which still contains the three edges the service
 * refuses with 409 because the execution engine owns them:
 *
 * ```
 * SCHEDULED -> RUNNING    "performed by the execution engine"
 * RUNNING   -> COMPLETED  "performed by the execution engine"
 * RUNNING   -> FAILED     "performed by the execution engine"
 * ```
 *
 * So a user could select "Complete" or "Fail" on a running campaign and receive
 * a 409 for an action the product states is automatic. This uses
 * `availableTransitions`, which subtracts them, and names the target state so
 * the control reads as an action rather than as a raw enum.
 *
 * ## DRAFT -> SCHEDULED is the only transition that validates
 *
 * VERIFIED `CampaignService.changeStatus` L289-291: only the
 * `DRAFT -> SCHEDULED` edge runs `validateActivation`, which re-checks the
 * schedule, contact group, DID, content approval and type configuration. Every
 * other edge applies unconditionally. The dialog says which one is about to be
 * validated, because for that one the failure will name a missing field rather
 * than anything about the transition itself.
 *
 * ## 409 is rendered as a conflict, not as a generic failure
 *
 * The mutation's error goes through `QueryErrorFromApiError`, which the F1
 * version replaced with a bare `setAlert(apiError.message)` in a branch whose
 * two arms were identical — dead code that hid the distinction between a 409
 * and a 400.
 */
interface ChangeStatusDialogProps {
  campaign: CampaignResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ChangeStatusDialog({
  campaign,
  open,
  onOpenChange,
}: ChangeStatusDialogProps) {
  const queryClient = useQueryClient();
  const [target, setTarget] = useState<CampaignStatus | null>(null);

  const transitions = campaign ? availableTransitions(campaign.status) : [];

  const mutation = useMutation({
    mutationFn: (status: CampaignStatus) => {
      if (!campaign) throw new Error("No campaign selected");
      return changeCampaignStatus(campaign.id, { status });
    },
    onSuccess: async (updated) => {
      toast.success("Campaign status changed", {
        description: `Now ${CAMPAIGN_STATUS_LABEL[updated.status].toLowerCase()}.`,
      });
      setTarget(null);
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      onOpenChange(false);
    },
  });

  if (!campaign || transitions.length === 0) return null;

  function close(nextOpen: boolean) {
    if (!nextOpen) {
      setTarget(null);
      mutation.reset();
    }
    onOpenChange(nextOpen);
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Change lifecycle state</DialogTitle>
          <DialogDescription>
            {campaign.name} is currently{" "}
            <strong>{CAMPAIGN_STATUS_LABEL[campaign.status]}</strong>.{" "}
            {CAMPAIGN_STATUS_DESCRIPTION[campaign.status]}
          </DialogDescription>
        </DialogHeader>

        {mutation.isError ? (
          <QueryErrorFromApiError
            apiError={toApiError(mutation.error)}
            entityLabel="this campaign"
            onRetry={target ? () => mutation.mutate(target) : undefined}
          />
        ) : null}

        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (target) mutation.mutate(target);
          }}
          noValidate
        >
          <FieldGroup>
            <fieldset className="flex flex-col gap-2">
              <legend className="text-sm font-medium">
                Choose the state to move to
              </legend>
              {transitions.map((status) => (
                <label
                  key={status}
                  className="flex cursor-pointer items-start gap-2.5 rounded-md border p-3 text-sm hover:bg-accent"
                >
                  <input
                    type="radio"
                    name="target-status"
                    value={status}
                    checked={target === status}
                    onChange={() => {
                      setTarget(status);
                      mutation.reset();
                    }}
                    className="mt-0.5 h-4 w-4 border-gray-300 text-primary focus:ring-primary"
                  />
                  <span className="flex flex-col gap-1">
                    <span className="font-medium">
                      {CAMPAIGN_STATUS_LABEL[status]}
                    </span>
                    <span className="text-muted-foreground">
                      {CAMPAIGN_TRANSITION_CONSEQUENCE[status]}
                    </span>
                  </span>
                </label>
              ))}
            </fieldset>

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => close(false)}
                disabled={mutation.isPending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={!target || mutation.isPending}>
                {mutation.isPending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Applying…
                  </>
                ) : (
                  "Change status"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
