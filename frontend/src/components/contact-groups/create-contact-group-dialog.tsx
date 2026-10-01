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
import {
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { createContactGroup, contactGroupsKeys } from "@/lib/api/contact-groups";
import type { CreateContactGroupValues } from "@/lib/schemas/contact-group-mutation";
import {
  createContactGroupSchema,
  toCreateContactGroupPayload,
} from "@/lib/schemas/contact-group-mutation";

const EMPTY_VALUES: CreateContactGroupValues = {
  name: "",
  description: "",
};

interface CreateContactGroupDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function CreateContactGroupDialog({ open, onOpenChange }: CreateContactGroupDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateContactGroupValues>({
    resolver: zodResolver(createContactGroupSchema),
    defaultValues: EMPTY_VALUES,
  });

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
    }
    onOpenChange(nextOpen);
  }

  async function onSubmit(values: CreateContactGroupValues) {
    setAlert(null);
    try {
      const group = await createContactGroup(toCreateContactGroupPayload(values));
      toast.success("Contact group created", {
        description: `${group.name} was created.`,
      });
      await queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all });
      handleOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F2: the F1 shared mapper replaces a hand-rolled copy of this loop. The
    // copy was functionally identical, which is exactly why it is dangerous —
    // it could drift without anything noticing.
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(createContactGroupSchema.shape),
      (field, message) => {
        form.setError(field as keyof CreateContactGroupValues, { message });
      },
    );
    if (mapped > 0 && apiError.status === 400) return;

    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Contact Group</DialogTitle>
          <DialogDescription>
            Create a new contact group to organize contacts for campaigns.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive mb-4">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Basic Information</FieldLegend>
              <TextField
                label="Name"
                placeholder="Marketing Contacts"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextareaField
                label="Description"
                placeholder="Optional description"
                registration={form.register("description")}
                error={form.formState.errors.description?.message}
                rows={3}
              />
            </FieldSet>

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => handleOpenChange(false)}
                disabled={pending}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={pending}>
                {pending ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Creating…
                  </>
                ) : (
                  "Create Contact Group"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}