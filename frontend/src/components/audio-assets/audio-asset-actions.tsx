"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  CheckIcon,
  PencilIcon,
  Trash2Icon,
  XIcon,
} from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  ApprovalTransitionDialog,
  approvalConsequence,
} from "@/components/common/approval-transition-dialog";
import { EditAudioAssetDialog } from "@/components/audio-assets/edit-audio-asset-dialog";
import { DeleteAudioAssetDialog } from "@/components/audio-assets/delete-audio-asset-dialog";
import {
  approveAudioAsset,
  audioAssetsKeys,
  rejectAudioAsset,
} from "@/lib/api/audio-assets";
import type { AudioAssetResponse } from "@/lib/api/contracts";
import type { ApprovalTransition } from "@/lib/domain/approval";
import { canTransition } from "@/lib/domain/approval";

/**
 * The four write actions on one audio asset, plus their dialogs.
 *
 * ## Why this is one component
 *
 * The list row and the detail page offered the same four actions, and before F3
 * each place owned its own copy of the mutation, the invalidation and the
 * confirm text — so a fix to one was invisible in the other. This is the same
 * consolidation F2 applied to the duplicated contacts import dialog.
 *
 * ## The two capabilities, kept apart
 *
 * VERIFIED `AudioAssetService`:
 *
 *  - **Edit and delete are `AUDIO_MANAGE`** — `update` L124, `delete` L137.
 *  - **Approve and reject are `AUDIO_APPROVE`** — `transition` L269.
 *
 * V1 grants `RESELLER_ADMIN` `AUDIO_VIEW` and `AUDIO_APPROVE` but **not**
 * `AUDIO_MANAGE`. So a reseller administrator legitimately sees Approve and
 * Reject on every asset in their hierarchy and must not see Edit or Delete. F3
 * does not smooth that over in either direction, and `content-gates.test.ts`
 * asserts both halves.
 *
 * ## Which transitions are offered
 *
 * `canTransition` mirrors the server: the only refused case is a no-op, so an
 * asset can be approved from PENDING or REJECTED and rejected from PENDING or
 * APPROVED. The pre-F3 table showed both buttons only for
 * `PENDING_APPROVAL`, which hid three of the four legal transitions — a rejected
 * recording could never be re-approved through the UI.
 *
 * Approve and Reject are mutually exclusive on a row by construction: whichever
 * one is not a no-op for the current status is the one shown. That is a
 * readability choice, not a restriction.
 */
export function AudioAssetActions({
  asset,
  canManage,
  canApprove,
  /** `row` hides the button labels in favour of icons on a narrow table. */
  variant = "full",
}: {
  asset: AudioAssetResponse;
  canManage: boolean;
  canApprove: boolean;
  variant?: "full" | "row";
}) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [transition, setTransition] = useState<ApprovalTransition | null>(null);

  const approve = useMutation({
    mutationFn: () => approveAudioAsset(asset.id),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: audioAssetsKeys.all });
      toast.success("Recording approved", {
        description: `${updated.name} can now be used by campaigns.`,
      });
    },
  });

  const reject = useMutation({
    mutationFn: () => rejectAudioAsset(asset.id),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: audioAssetsKeys.all });
      toast.success("Recording rejected", {
        description: `${updated.name} can no longer be used by campaigns.`,
      });
    },
  });

  const compact = variant === "row";
  const buttonSize = compact ? "sm" : "default";
  const canApproveAction = canApprove && canTransition(asset.status, "approve");
  const canRejectAction = canApprove && canTransition(asset.status, "reject");

  async function confirmTransition() {
    const next = transition;
    if (!next) return;
    await (next === "approve" ? approve : reject).mutateAsync();
    setTransition(null);
  }

  return (
    <>
      {canRejectAction ? (
        <Button
          variant="ghost"
          size={buttonSize}
          onClick={() => setTransition("reject")}
          title={`Reject ${asset.name}`}
        >
          <XIcon aria-hidden="true" className="mr-1 h-4 w-4" />
          {compact ? <span className="sr-only">Reject</span> : "Reject"}
        </Button>
      ) : null}

      {canApproveAction ? (
        <Button
          variant="ghost"
          size={buttonSize}
          onClick={() => setTransition("approve")}
          title={`Approve ${asset.name}`}
        >
          <CheckIcon aria-hidden="true" className="mr-1 h-4 w-4" />
          {compact ? <span className="sr-only">Approve</span> : "Approve"}
        </Button>
      ) : null}

      {canManage ? (
        <>
          <Button
            variant="ghost"
            size={buttonSize}
            onClick={() => setEditing(true)}
            title={`Edit ${asset.name}`}
          >
            <PencilIcon aria-hidden="true" className="mr-1 h-4 w-4" />
            {compact ? <span className="sr-only">Edit</span> : "Edit"}
          </Button>
          <Button
            variant="ghost"
            size={buttonSize}
            className="text-destructive hover:bg-destructive/10"
            onClick={() => setDeleting(true)}
            title={`Delete ${asset.name}`}
          >
            <Trash2Icon aria-hidden="true" className="mr-1 h-4 w-4" />
            {compact ? <span className="sr-only">Delete</span> : "Delete"}
          </Button>
        </>
      ) : null}

      {/* `key` on the edit dialog so switching records remounts the form with
          fresh defaults instead of the previous record's values. */}
      {editing ? (
        <EditAudioAssetDialog
          key={`edit-${asset.id}`}
          asset={asset}
          open={editing}
          onOpenChange={(open) => {
            if (!open) setEditing(false);
          }}
        />
      ) : null}

      {deleting ? (
        <DeleteAudioAssetDialog
          asset={asset}
          open={deleting}
          onOpenChange={(open) => {
            if (!open) setDeleting(false);
          }}
        />
      ) : null}

      {transition ? (
        <ApprovalTransitionDialog
          key={`transition-${asset.id}-${transition}`}
          open={transition !== null}
          onOpenChange={(open) => {
            if (!open) setTransition(null);
          }}
          subject="Audio asset"
          label={asset.name}
          status={asset.status}
          transition={transition}
          consequence={approvalConsequence("audio", transition)}
          onConfirm={confirmTransition}
        />
      ) : null}
    </>
  );
}
