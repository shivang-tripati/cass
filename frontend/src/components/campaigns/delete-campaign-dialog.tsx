"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
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
import { Spinner } from "@/components/ui/spinner";
import { QueryErrorFromApiError } from "@/components/common/query-state";
import { toApiError } from "@/lib/api/error";
import { deleteCampaign, campaignsKeys } from "@/lib/api/campaigns";
import type { CampaignResponse } from "@/lib/api/contracts";

/**
 * Campaign delete (DELETE /api/v1/campaigns/{id}).
 *
 * ## The F1 "Delete" button did nothing
 *
 * VERIFIED drift: `campaign-detail-view.tsx` defined
 *
 * ```ts
 * function confirmDelete(campaignId: string) {
 *   if (confirm("Are you sure…")) {
 *     // In a real implementation, this would call the delete API
 *     window.location.reload();
 *   }
 *   void campaignId;
 * }
 * ```
 *
 * — a stub that reloaded the page and discarded the id, while `deleteCampaign`
 * sat unused in the service layer. The button was destructive-looking and
 * harmless. See the F4 doc §12, drift item D7.
 *
 * ## It is a SOFT delete, and the dialog says so
 *
 * VERIFIED `CampaignService.delete` L250-260: the row is preserved with
 * `deletedAt` and `deletedBy` stamped, and it disappears from all normal
 * queries because `findVisible` and `list` both constrain
 * `deletedAt IS NULL`. So the campaign is not destroyed and its history is
 * retained; the dialog states that rather than the F1 wording "This action
 * cannot be undone."
 *
 * ## No lifecycle gate
 *
 * `delete` checks only `CAMPAIGN_MANAGE`. There is no status precondition, so a
 * running or archived campaign can be deleted. The dialog therefore does not
 * try to discourage it — that would be inventing a restriction the server does
 * not apply.
 */
export function DeleteCampaignDialog({
  campaign,
  open,
  onOpenChange,
}: {
  campaign: CampaignResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const router = useRouter();

  const mutation = useMutation({
    mutationFn: async () => {
      if (!campaign) throw new Error("No campaign selected");
      await deleteCampaign(campaign.id);
    },
    onSuccess: async () => {
      toast.success("Campaign deleted", {
        description: "It no longer appears in any list, but its history is retained.",
      });
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.all });
      onOpenChange(false);
      // The campaign is gone, so its detail route would now 404. Returning to
      // the list is the only honest destination.
      router.push("/campaigns");
    },
  });

  if (!campaign) return null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) mutation.reset();
        onOpenChange(next);
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Delete “{campaign.name}”?</DialogTitle>
          <DialogDescription>
            The campaign is removed from every list and can no longer be opened,
            scheduled or executed. Its record and history are retained rather
            than destroyed.
          </DialogDescription>
        </DialogHeader>

        {mutation.isError ? (
          <QueryErrorFromApiError
            apiError={toApiError(mutation.error)}
            entityLabel="this campaign"
            onRetry={() => mutation.mutate()}
          />
        ) : null}

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? (
              <>
                <Spinner aria-hidden="true" />
                Deleting…
              </>
            ) : (
              "Delete campaign"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
