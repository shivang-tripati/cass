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
import type { ResellerResponse } from "@/lib/api/contracts";
import { deleteReseller, resellersKeys } from "@/lib/api/resellers";

interface DeleteResellerDialogProps {
  reseller: ResellerResponse;
  onOpenChange: (open: boolean) => void;
}

/**
 * Confirmation for DELETE /api/v1/resellers/{id} — a server-side soft
 * delete (deletedAt stamp, bare 204). Verified behavior: no cascade —
 * tenant rows are not modified by this operation.
 */
export function DeleteResellerDialog({
  reseller,
  onOpenChange,
}: DeleteResellerDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const deleteMutation = useMutation({
    mutationFn: () => deleteReseller(reseller.id),
    onSuccess: () => {
      toast.success("Reseller deactivated", {
        description: `${reseller.name} no longer appears in active lists.`,
      });
      return queryClient.invalidateQueries({ queryKey: resellersKeys.all });
    },
    onError: (error) => {
      setAlert(toApiError(error).message);
    },
  });

  function handleConfirm() {
    deleteMutation.mutate(undefined, {
      onSuccess: () => {
        onOpenChange(false);
        router.push("/resellers");
      },
    });
  }

  const pending = deleteMutation.isPending;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Deactivate reseller</DialogTitle>
          <DialogDescription>
            This deactivates{" "}
            <span className="font-medium">{reseller.name}</span> (
            {reseller.slug}). The reseller will disappear from all active
            lists and its detail page will become unavailable. Tenants linked
            to it are not modified by this action. This cannot be undone from
            the application.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={pending}
          >
            Cancel
          </Button>
          <Button variant="destructive" onClick={handleConfirm} disabled={pending}>
            {pending ? (
              <>
                <Spinner aria-hidden="true" />
                Deactivating…
              </>
            ) : (
              "Deactivate reseller"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
