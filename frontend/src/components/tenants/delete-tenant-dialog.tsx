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
import type { TenantResponse } from "@/lib/api/contracts";
import { deleteTenant, tenantsKeys } from "@/lib/api/tenants";

interface DeleteTenantDialogProps {
  tenant: TenantResponse;
  onOpenChange: (open: boolean) => void;
}

/**
 * Confirmation for DELETE /api/v1/tenants/{id} — a server-side soft
 * delete: the tenant is stamped deleted and disappears from all active
 * lists and lookups; there is no undo endpoint.
 */
export function DeleteTenantDialog({ tenant, onOpenChange }: DeleteTenantDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const deleteMutation = useMutation({
    mutationFn: () => deleteTenant(tenant.id),
    onSuccess: () => {
      toast.success("Tenant deactivated", {
        description: `${tenant.name} no longer appears in active lists.`,
      });
      return queryClient.invalidateQueries({ queryKey: tenantsKeys.all });
    },
    onError: (error) => {
      setAlert(toApiError(error).message);
    },
  });

  function handleConfirm() {
    deleteMutation.mutate(undefined, {
      onSuccess: () => {
        onOpenChange(false);
        router.push("/tenants");
      },
    });
  }

  const pending = deleteMutation.isPending;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Deactivate tenant</DialogTitle>
          <DialogDescription>
            This deactivates <span className="font-medium">{tenant.name}</span>{" "}
            ({tenant.slug}). The tenant will disappear from all active lists
            and its detail page will become unavailable. This action cannot
            be undone from the application.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={handleConfirm} disabled={pending}>
            {pending ? (
              <>
                <Spinner aria-hidden="true" />
                Deactivating…
              </>
            ) : (
              "Deactivate tenant"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
