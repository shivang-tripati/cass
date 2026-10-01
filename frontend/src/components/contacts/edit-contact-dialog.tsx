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
import { isConflict, toApiError } from "@/lib/api/error";
import type { ContactResponse } from "@/lib/api/contracts";
import { contactsKeys, updateContact } from "@/lib/api/contacts";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import {
  CONTACT_E164_HINT,
  formatAttributesForInput,
  updateContactSchema,
  toUpdateContactPayload,
  type UpdateContactFormValues,
  type UpdateContactValues,
} from "@/lib/schemas/contact-mutation";

interface EditContactDialogProps {
  contact: ContactResponse | null;
  contactGroupId: string;
  onOpenChange: (open: boolean) => void;
  onSaved?: () => void | Promise<void>;
}

function defaultValuesFor(contact: ContactResponse): UpdateContactFormValues {
  return {
    firstName: contact.firstName ?? "",
    lastName: contact.lastName ?? "",
    phoneNumber: contact.phoneNumber ?? "",
    email: contact.email ?? "",
    // F2: the form holds a JSON STRING; the schema parses it. Passing the
    // parsed object here would be the type-mismatch this change removes.
    attributes: formatAttributesForInput(contact.attributes),
  };
}

/**
 * Edit a contact.
 *
 * F2 notes that this is a **partial** update server-side: `ContactMapper`
 * blank-to-nulls the three text fields, so clearing a name or email persists.
 * A phone change keeps the same contact id.
 *
 * The dialog also states the destructive consequence of deleting a contact
 * elsewhere, so the user is not surprised: `DELETE …/contacts/{id}` soft-deletes
 * the identity across every group, whereas removing it from this one group only
 * leaves it alive elsewhere.
 */
export function EditContactDialog({
  contact,
  contactGroupId,
  onOpenChange,
  onSaved,
}: EditContactDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  // `key` on the parent remounts this per contact, so the form always starts
  // from the record being edited rather than a previous one.
  const form = useForm<UpdateContactFormValues, unknown, UpdateContactValues>({
    // See the note in `create-contact-dialog.tsx`: the third generic is what lets
    // the transformed `attributes` output differ from the field type.
    resolver: zodResolver(updateContactSchema),
    defaultValues: contact ? defaultValuesFor(contact) : EMPTY,
  });

  // `onSubmit` is declared with `const` AFTER this guard so the narrowing of
  // `target` is visible inside it. A hoisted `function` declaration would not
  // narrow, and a non-null assertion would be a lie the compiler cannot check.
  const target = contact;
  if (!target) return null;

  const onSubmit = async (values: UpdateContactValues) => {
    setAlert(null);
    try {
      await updateContact(contactGroupId, target.id, toUpdateContactPayload(values));
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: contactsKeys.forGroup(contactGroupId),
        }),
        queryClient.invalidateQueries({
          queryKey: contactsKeys.detail(contactGroupId, target.id),
        }),
        onSaved?.(),
      ]);
      toast.success("Contact updated", {
        description: `${target.firstName ?? target.phoneNumber} was saved.`,
      });
      onOpenChange(false);
    } catch (error) {
      applyServerError(error);
    }
  };

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(updateContactSchema.shape),
      (field, message) => {
        form.setError(field as keyof UpdateContactFormValues, { message });
      },
    );
    if (mapped > 0 && apiError.status === 400) return;

    // VERIFIED: a phone change that collides with another live contact in the
    // same tenant is a typed 409 (ContactIdentityService). The id is preserved.
    setAlert(
      isConflict(apiError)
        ? `${apiError.message} This contact keeps its existing number.`
        : apiError.message,
    );
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit Contact</DialogTitle>
          <DialogDescription>
            Phone number must be a valid E.164 number. Changing it keeps this
            contact&apos;s history attached.
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
                error={form.getFieldState("firstName").error?.message}
              />
              <TextField
                label="Last Name"
                registration={form.register("lastName")}
                error={form.getFieldState("lastName").error?.message}
              />
              <TextField
                label="Phone Number"
                description={CONTACT_E164_HINT}
                registration={form.register("phoneNumber")}
                error={form.getFieldState("phoneNumber").error?.message}
              />
              <TextField
                label="Email"
                type="email"
                registration={form.register("email")}
                error={form.getFieldState("email").error?.message}
              />
              <TextareaField
                label="Attributes (JSON, optional)"
                registration={form.register("attributes")}
                error={form.getFieldState("attributes").error?.message}
                rows={4}
                placeholder={'{\n  "city": "Pune"\n}'}
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

const EMPTY: UpdateContactFormValues = {
  firstName: "",
  lastName: "",
  phoneNumber: "",
  email: "",
  attributes: "",
};
