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
import { TextField } from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import type { TenantResponse } from "@/lib/api/contracts";
import { tenantsKeys, updateTenant } from "@/lib/api/tenants";
import type { EditTenantValues } from "@/lib/schemas/tenant-mutation";
import {
  editTenantSchema,
  toUpdateTenantPayload,
} from "@/lib/schemas/tenant-mutation";

interface EditTenantDialogProps {
  tenant: TenantResponse | null;
  onOpenChange: (open: boolean) => void;
}

/**
 * Name is the only backend-editable tenant field. Mount-scoped: parents
 * render one instance per target (keyed by id), so the form seeds itself
 * from props without reset effects.
 */
export function EditTenantDialog({ tenant, onOpenChange }: EditTenantDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<EditTenantValues>({
    resolver: zodResolver(editTenantSchema),
    defaultValues: { name: tenant?.name ?? "" },
  });

  if (!tenant) return null;

  async function onSubmit(values: EditTenantValues) {
    if (!tenant) return;
    setAlert(null);
    try {
      await updateTenant(tenant.id, toUpdateTenantPayload(values, tenant));
      toast.success("Tenant updated", {
        description: `${tenant.name} was saved.`,
      });
      await queryClient.invalidateQueries({ queryKey: tenantsKeys.all });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      for (const fieldError of apiError.fieldErrors) {
        if (fieldError.field in editTenantSchema.shape) {
          form.setError(fieldError.field as keyof EditTenantValues, {
            message: fieldError.message,
          });
          return;
        }
      }
      setAlert(apiError.message);
    }
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Edit tenant</DialogTitle>
          <DialogDescription>
            Slug and organization binding are permanent identifiers and
            cannot be changed.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <TextField
              label="Name"
              registration={form.register("name")}
              error={form.formState.errors.name?.message}
            />
            <TextField label="Slug" value={tenant.slug} readOnly disabled />
            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Saving…
                  </>
                ) : (
                  "Save changes"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
