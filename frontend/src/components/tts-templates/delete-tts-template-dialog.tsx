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
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import { deleteTtsTemplate, ttsTemplatesKeys } from "@/lib/api/tts-templates";
import type { TtsTemplateResponse } from "@/lib/api/contracts";

/**
 * Delete a TTS template.
 *
 * ## Soft delete, and the scope-specific consequence
 *
 * `TtsTemplateService.delete` (L193-202) stamps `deletedAt`/`deletedBy` and
 * returns a bare 204. The row and its history remain.
 *
 * The consequence differs by scope, and the dialog says which one applies:
 *
 *  - **GLOBAL** — deleting removes the template from the shared catalog, so it
 *    stops being readable by *every* organization, not just this one. That is a
 *    larger blast radius than the same action on a tenant template, and the
 *    dialog names it.
 *  - **TENANT** — only the owning organization (or a reseller managing it) loses
 *    it. Other tenants were only ever able to read it while it was approved.
 *
 * Campaigns that still reference the template fail to start, which is the same
 * consequence `reject` has — the difference is that delete also removes it from
 * the owner's list, whereas a rejection leaves it visible and re-approvable.
 */
export function DeleteTtsTemplateDialog({
  template,
  open,
  onOpenChange,
  /** Called after a successful delete, so the caller can leave a now-404 view. */
  onDeleted,
}: {
  template: TtsTemplateResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted?: () => void;
}) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const isGlobal = template.scope === "GLOBAL";

  const mutation = useMutation({
    mutationFn: () => deleteTtsTemplate(template.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ttsTemplatesKeys.all });
      toast.success("Template deleted", {
        description: isGlobal
          ? `${template.name} is no longer in the shared catalog.`
          : `${template.name} is no longer available. Campaigns that use it will fail to start.`,
      });
      setError(null);
      onOpenChange(false);
      onDeleted?.();
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
          <DialogTitle>Delete this template?</DialogTitle>
          <DialogDescription>
            <strong>{template.name}</strong> will no longer be available.
          </DialogDescription>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          {isGlobal
            ? "This is a global template, so deleting it removes it from the shared catalog for every organization, not just yours. Campaigns that still use it will fail to start."
            : "Any campaign in your organization that uses this template will fail to start. Templates in other organizations are unaffected."}
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
            {mutation.isPending ? "Deleting…" : "Delete template"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
