"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
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
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import { campaignsKeys, cloneCampaign } from "@/lib/api/campaigns";
import type { CampaignResponse } from "@/lib/api/contracts";

/**
 * POST /api/v1/campaigns/{id}/clone.
 *
 * VERIFIED (CampaignController.clone L180-184 and its OpenAPI description): the
 * server creates a fresh DRAFT lineage successor — same tenant and configuration,
 * version incremented by 1, `clonedFromCampaignId` set to the source, new
 * identity and audit trail — and requires `CAMPAIGN_MANAGE`. The source is
 * resolved inside the caller's boundary, so cloning a foreign campaign is
 * indistinguishable from a missing one (404). There is **no request body**: the
 * clone always copies the source's configuration verbatim, which is why this
 * dialog confirms rather than collects.
 *
 * F1 — F0 shipped `cloneCampaign` in the API layer and rendered a Clone button
 * that opened the **status** dialog, so the action was unreachable. This wires
 * the endpoint that already existed.
 */
export function CloneCampaignDialog({
  campaign,
  open,
  onOpenChange,
}: {
  campaign: CampaignResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => cloneCampaign(campaign.id),
    onSuccess: async (clone) => {
      await qc.invalidateQueries({ queryKey: campaignsKeys.all });
      toast.success("Campaign cloned", {
        description: `Created as a draft, version ${clone.version}.`,
      });
      onOpenChange(false);
      // Land on the new campaign so the lineage is visible immediately.
      router.push(`/campaigns/${clone.id}`);
    },
    onError: (e: unknown) => {
      const apiError = toApiError(e);
      setError(
        apiError.requestId
          ? `${apiError.message} (Request ID: ${apiError.requestId})`
          : apiError.message,
      );
    },
  });

  return (
    <Dialog open={open} onOpenChange={(next) => !mutation.isPending && onOpenChange(next)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Clone campaign</DialogTitle>
          <DialogDescription>
            Creates a new draft copy of{" "}
            <span className="font-medium">{campaign.name}</span> with the same
            configuration and version {campaign.version + 1}. The original is
            unchanged, and the copy is not scheduled or started.
          </DialogDescription>
        </DialogHeader>

        {error ? (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => {
              setError(null);
              mutation.mutate();
            }}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? <Spinner className="size-4" /> : null}
            {mutation.isPending ? "Cloning…" : "Clone campaign"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
