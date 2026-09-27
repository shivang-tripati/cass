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
import type { ContactResponse } from "@/lib/api/contracts";
import { contactsKeys, updateContact } from "@/lib/api/contacts";
import type { UpdateContactValues } from "@/lib/schemas/contact-mutation";
import {
  updateContactSchema,
  toUpdateContactPayload,
} from "@/lib/schemas/contact-mutation";
import type { Path } from "react-hook-form";

interface EditContactDialogProps {
  contact: ContactResponse | null;
  contactGroupId: string;
  onOpenChange: (open: boolean) => void;
}

/** Returns the error message for a field, if any. */
function getError<T extends Record<string, unknown>>(
  form: ReturnType<typeof useForm<T>>,
  name: Path<T>,
): string | undefined {
  return form.getFieldState(name).error?.message;
}

export function EditContactDialog({ contact, contactGroupId, onOpenChange }: EditContactDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<UpdateContactValues>({
    resolver: zodResolver(updateContactSchema),
    defaultValues: {
      firstName: contact?.firstName ?? "",
      lastName: contact?.lastName ?? "",
      phoneNumber: contact?.phoneNumber ?? "",
      email: contact?.email ?? "",
      attributes: contact?.attributes ?? undefined,
    },
  });

  if (!contact) return null;

  async function onSubmit(values: UpdateContactValues) {
    if (!contact) return;
    setAlert(null);
    try {
      await updateContact(contactGroupId, contact.id, toUpdateContactPayload(values));
      toast.success("Contact updated", {
        description: `${contact.firstName ?? contact.phoneNumber} was saved.`,
      });
      await queryClient.invalidateQueries({ queryKey: contactsKeys.list({ contactGroupId, page: 0, size: 20, sortField: "firstName", sortDirection: "asc" }) });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      for (const fieldError of apiError.fieldErrors) {
        if (fieldError.field in updateContactSchema.shape) {
          form.setError(fieldError.field as keyof UpdateContactValues, {
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
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit Contact</DialogTitle>
          <DialogDescription>
            Update the contact information. Phone number is required and must be a valid E.164 number.
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
                label="First Name"
                registration={form.register("firstName")}
                error={getError(form, "firstName")}
              />
              <TextField
                label="Last Name"
                registration={form.register("lastName")}
                error={getError(form, "lastName")}
              />
              <TextField
                label="Phone Number (E.164)"
                registration={form.register("phoneNumber")}
                error={getError(form, "phoneNumber")}
              />
              <TextField
                label="Email"
                type="email"
                registration={form.register("email")}
                error={getError(form, "email")}
              />
              <TextareaField
                label="Attributes (JSON, optional)"
                registration={form.register("attributes")}
                error={getError(form, "attributes")}
                rows={4}
                placeholder='{"key": "value"}'
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