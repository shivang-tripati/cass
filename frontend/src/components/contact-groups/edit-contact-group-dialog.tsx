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
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import type { ContactGroupResponse } from "@/lib/api/contracts";
import { contactGroupsKeys, updateContactGroup } from "@/lib/api/contact-groups";
import type { UpdateContactGroupValues } from "@/lib/schemas/contact-group-mutation";
import {
  updateContactGroupSchema,
  toUpdateContactGroupPayload,
} from "@/lib/schemas/contact-group-mutation";

interface EditContactGroupDialogProps {
  group: ContactGroupResponse | null;
  onOpenChange: (open: boolean) => void;
}

export function EditContactGroupDialog({ group, onOpenChange }: EditContactGroupDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<UpdateContactGroupValues>({
    resolver: zodResolver(updateContactGroupSchema),
    defaultValues: {
      name: group?.name ?? "",
      description: group?.description ?? "",
    },
  });

  if (!group) return null;

  async function onSubmit(values: UpdateContactGroupValues) {
    if (!group) return;
    setAlert(null);
    try {
      const updated = await updateContactGroup(group.id, toUpdateContactGroupPayload(values));
      toast.success("Contact group updated", {
        description: `${updated.name} was saved.`,
      });
      // The list AND any open detail view show the new name/description.
      await queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all });
      onOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F2: shared mapper. The previous handler `return`ed after the FIRST mapped
    // field error regardless of status, so a single message suppressed the form
    // summary even when the failure was not a field-level 400.
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(updateContactGroupSchema.shape),
      (field, message) => {
        form.setError(field as keyof UpdateContactGroupValues, { message });
      },
    );
    if (mapped > 0 && apiError.status === 400) return;

    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit Contact Group</DialogTitle>
          <DialogDescription>
            Update the contact group name and description.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive mb-4">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <div className="space-y-4">
              <TextField
                label="Name"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={3}
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