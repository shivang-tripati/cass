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
  FieldDescription,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { createContact, contactsKeys } from "@/lib/api/contacts";
import type { CreateContactValues } from "@/lib/schemas/contact-mutation";
import {
  createContactSchema,
  toCreateContactPayload,
} from "@/lib/schemas/contact-mutation";
import type { Path } from "react-hook-form";

const EMPTY_VALUES: CreateContactValues = {
  firstName: "",
  lastName: "",
  phoneNumber: "",
  email: "",
  attributes: undefined,
};

interface CreateContactDialogProps {
  contactGroupId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Returns the error message for a field, if any. */
function getError<T extends Record<string, unknown>>(
  form: ReturnType<typeof useForm<T>>,
  name: Path<T>,
): string | undefined {
  return form.getFieldState(name).error?.message;
}

export function CreateContactDialog({ contactGroupId, open, onOpenChange }: CreateContactDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateContactValues>({
    resolver: zodResolver(createContactSchema),
    defaultValues: EMPTY_VALUES,
  });

  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
    }
    onOpenChange(nextOpen);
  }

  async function onSubmit(values: CreateContactValues) {
    setAlert(null);
    try {
      const contact = await createContact(contactGroupId, toCreateContactPayload(values));
      toast.success("Contact created", {
        description: `${contact.firstName ?? contact.phoneNumber} was added.`,
      });
      await queryClient.invalidateQueries({ queryKey: contactsKeys.list({ contactGroupId, page: 0, size: 20, sortField: "firstName", sortDirection: "asc" }) });
      handleOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      if (fieldError.field in createContactSchema.shape) {
        form.setError(fieldError.field as keyof CreateContactValues, {
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
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add Contact</DialogTitle>
          <DialogDescription>
            Add a new contact to this group. Phone number must be a valid E.164 number.
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
              <FieldLegend>Contact Information</FieldLegend>
              <TextField
                label="First Name"
                placeholder="John"
                registration={form.register("firstName")}
                error={getError(form, "firstName")}
              />
              <TextField
                label="Last Name"
                placeholder="Doe"
                registration={form.register("lastName")}
                error={getError(form, "lastName")}
              />
              <TextField
                label="Phone Number (E.164)"
                placeholder="+918012345678"
                description="Must be a valid E.164 number with leading +"
                registration={form.register("phoneNumber")}
                error={getError(form, "phoneNumber")}
              />
              <TextField
                label="Email"
                type="email"
                placeholder="john.doe@example.com"
                registration={form.register("email")}
                error={getError(form, "email")}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Attributes (Optional)</FieldLegend>
              <FieldDescription>
                JSON object for custom attributes. Leave empty if not needed.
              </FieldDescription>
              <TextareaField
                label="Attributes (JSON)"
                placeholder='{"key": "value"}'
                registration={form.register("attributes")}
                error={getError(form, "attributes")}
                rows={4}
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
                  "Add Contact"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}