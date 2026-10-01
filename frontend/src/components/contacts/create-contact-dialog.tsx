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
import { isConflict, toApiError } from "@/lib/api/error";
import { createContact, contactsKeys } from "@/lib/api/contacts";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import {
  CONTACT_E164_HINT,
  createContactSchema,
  toCreateContactPayload,
  type CreateContactFormValues,
  type CreateContactValues,
} from "@/lib/schemas/contact-mutation";

const EMPTY_VALUES: CreateContactFormValues = {
  firstName: "",
  lastName: "",
  phoneNumber: "",
  email: "",
  attributes: "",
};

interface CreateContactDialogProps {
  contactGroupId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Invalidates the group list so `memberCount` refreshes. */
  onCreated?: () => void | Promise<void>;
}

/**
 * Create a contact in a group.
 *
 * F2: the form now holds a JSON *string* for `attributes` and the schema parses
 * it into the object the API takes (see `contact-mutation.ts`). Previously a
 * textarea string was bound to a `z.record` schema, so the field could never
 * validate.
 *
 * The dialog explains the VERIFIED find-or-create behaviour, because "Add
 * Contact" on a number that already exists in the tenant does NOT create a
 * duplicate and does not error — it attaches the existing contact to this group.
 */
export function CreateContactDialog({
  contactGroupId,
  open,
  onOpenChange,
  onCreated,
}: CreateContactDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateContactFormValues, unknown, CreateContactValues>({
    // The schema TRANSFORMS `attributes` from a JSON string into the object the
    // API takes, so the form's field type and its submitted type differ. React
    // Hook Form models that with the third `useForm` generic; without it the
    // resolver's output type does not line up with the declared field type.
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
      // F2: invalidate EVERY list of this group's contacts, not one hardcoded
      // page/sort. The previous call reconstructed a single exact query key, so
      // a create performed while a different page or search was active left the
      // visible list stale.
      const contact = await createContact(
        contactGroupId,
        toCreateContactPayload(values),
      );
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: contactsKeys.forGroup(contactGroupId),
        }),
        onCreated?.(),
      ]);
      toast.success("Contact added", {
        description: `${contact.firstName ?? contact.lastName ?? contact.phoneNumber} is now in this group.`,
      });
      handleOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F1 shared mapping: drops a field this form does not declare rather than
    // attaching the message to an arbitrary control.
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(createContactSchema.shape),
      (field, message) => {
        form.setError(field as keyof CreateContactFormValues, { message });
      },
    );
    if (mapped > 0 && apiError.status === 400) return;

    // VERIFIED: a 409 here is a concurrent create of the same tenant+phone
    // (ContactIdentityService.duplicateContactConflict), never a plain
    // duplicate — an existing number is normally reused silently.
    setAlert(
      isConflict(apiError)
        ? `${apiError.message} It may have just been added by someone else — refresh to see it.`
        : apiError.message,
    );
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Add Contact</DialogTitle>
          <DialogDescription>
            Contacts are identified by phone number within your organization. If
            this number already exists, the existing contact is added to this
            group rather than duplicated.
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
                error={form.getFieldState("firstName").error?.message}
              />
              <TextField
                label="Last Name"
                placeholder="Doe"
                registration={form.register("lastName")}
                error={form.getFieldState("lastName").error?.message}
              />
              <TextField
                label="Phone Number"
                placeholder="+918012345678"
                description={CONTACT_E164_HINT}
                registration={form.register("phoneNumber")}
                error={form.getFieldState("phoneNumber").error?.message}
              />
              <TextField
                label="Email"
                type="email"
                placeholder="john.doe@example.com"
                registration={form.register("email")}
                error={form.getFieldState("email").error?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Attributes (Optional)</FieldLegend>
              <FieldDescription>
                A JSON object for your own reference data. It is stored as-is and
                is not interpreted by the platform.
              </FieldDescription>
              <TextareaField
                label="Attributes (JSON)"
                placeholder={'{\n  "city": "Pune"\n}'}
                registration={form.register("attributes")}
                error={form.getFieldState("attributes").error?.message}
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
                    Adding…
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
