"use client";

import { useState } from "react";
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
import { toApiError } from "@/lib/api/error";
import { audioAssetsKeys, deleteAudioAsset } from "@/lib/api/audio-assets";
import type { AudioAssetResponse } from "@/lib/api/contracts";

/**
 * Delete an audio recording.
 *
 * ## What actually happens, because the old message got it wrong
 *
 * `AudioAssetService.delete` (L133-144) is a **soft delete**: it stamps
 * `deletedAt` and `deletedBy` and returns a bare 204. The row and the recording's
 * call history remain. What dies is its availability — the asset disappears from
 * every list and `findByIdAndDeletedAtIsNull` stops resolving it.
 *
 * The pre-F3 `confirm()` said *"Soft-delete? Campaign references will fail
 * activation afterwards."* which is directionally right but buried the actual
 * consequence in a clause. This dialog leads with it, because the real answer to
 * "what am I losing?" is: campaigns that use this recording will no longer be
 * able to start, and the recording is not restorable through the UI.
 *
 * There is no un-delete endpoint, so the wording says "deleted" rather than
 * "hidden".
 */
export function DeleteAudioAssetDialog({
  asset,
  open,
  onOpenChange,
}: {
  asset: AudioAssetResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => deleteAudioAsset(asset.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: audioAssetsKeys.all });
      toast.success("Recording deleted", {
        description: `${asset.name} is no longer available. Campaigns that use it will fail to start.`,
      });
      setError(null);
      onOpenChange(false);
      // A detail view for a soft-deleted record is now a 404, so leave it.
      router.push("/audio-assets");
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
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (mutation.isPending) return;
        if (!next) setError(null);
        onOpenChange(next);
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete this recording?</DialogTitle>
          <DialogDescription>
            <strong>{asset.name}</strong> will no longer be available to your
            organization.
          </DialogDescription>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          Any campaign that uses this recording will fail to start, and there is
          no way to restore it from this screen. Campaigns that do not use it are
          unaffected.
        </p>

        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
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
            variant="destructive"
            onClick={() => {
              setError(null);
              mutation.mutate();
            }}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? <Spinner className="size-4" /> : null}
            {mutation.isPending ? "Deleting…" : "Delete recording"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
