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
import type { ResellerResponse } from "@/lib/api/contracts";
import { resellersKeys, updateReseller } from "@/lib/api/resellers";
import type { EditResellerValues } from "@/lib/schemas/reseller-mutation";
import {
  editResellerSchema,
  toUpdateResellerPayload,
} from "@/lib/schemas/reseller-mutation";

interface EditResellerDialogProps {
  reseller: ResellerResponse | null;
  onOpenChange: (open: boolean) => void;
}

/**
 * Edits the backend-supported mutable profile fields. Slug and custom
 * domain are immutable (custom domain is not even part of the response).
 * Mount-scoped: parents render one instance per target (keyed by id).
 */
export function EditResellerDialog({
  reseller,
  onOpenChange,
}: EditResellerDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<EditResellerValues>({
    resolver: zodResolver(editResellerSchema),
    defaultValues: {
      name: reseller?.name ?? "",
      displayName: reseller?.displayName ?? "",
      supportEmail: reseller?.supportEmail ?? "",
      logoUrl: reseller?.logoUrl ?? "",
      primaryColor: reseller?.primaryColor ?? "",
    },
  });

  if (!reseller) return null;

  async function onSubmit(values: EditResellerValues) {
    if (!reseller) return;
    setAlert(null);
    try {
      await updateReseller(
        reseller.id,
        toUpdateResellerPayload(values, reseller),
      );
      toast.success("Reseller updated", {
        description: `${reseller.name} was saved.`,
      });
      await queryClient.invalidateQueries({ queryKey: resellersKeys.all });
      onOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      if (fieldError.field in editResellerSchema.shape) {
        form.setError(fieldError.field as keyof EditResellerValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit reseller</DialogTitle>
          <DialogDescription>
            Slug and custom domain are permanent identifiers and cannot be
            changed.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Name"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextField label="Slug" value={reseller.slug} readOnly disabled />
            </div>
            <TextField
              label="Display name"
              registration={form.register("displayName")}
              error={form.formState.errors.displayName?.message}
            />
            <TextField
              label="Support email"
              type="email"
              inputMode="email"
              autoComplete="email"
              registration={form.register("supportEmail")}
              error={form.formState.errors.supportEmail?.message}
            />
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Logo URL"
                placeholder="https://…/logo.png"
                registration={form.register("logoUrl")}
                error={form.formState.errors.logoUrl?.message}
              />
              <TextField
                label="Primary color"
                placeholder="#0ea5e9"
                maxLength={20}
                registration={form.register("primaryColor")}
                error={form.formState.errors.primaryColor?.message}
              />
            </div>

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
