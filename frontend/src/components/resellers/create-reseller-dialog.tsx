"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { zodResolver } from "@hookform/resolvers/zod";
import { Controller, useForm, useWatch } from "react-hook-form";
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
  Field,
  FieldDescription,
  FieldGroup,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import {
  Collapsible,
  CollapsibleContent,
} from "@/components/ui/collapsible";
import { Spinner } from "@/components/ui/spinner";
import {
  CheckboxField,
  TextField,
} from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import { createReseller, resellersKeys } from "@/lib/api/resellers";
import type { CreateResellerValues } from "@/lib/schemas/reseller-mutation";
import {
  createResellerSchema,
  toCreateResellerPayload,
} from "@/lib/schemas/reseller-mutation";

const EMPTY_VALUES: CreateResellerValues = {
  name: "",
  slug: "",
  displayName: "",
  supportEmail: "",
  customDomain: "",
  logoUrl: "",
  primaryColor: "",
  createAdmin: false,
  adminEmail: "",
  adminPassword: "",
  adminDisplayName: "",
};

interface CreateResellerDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Reseller provisioning (POST /api/v1/resellers). The embedded RESELLER_ADMIN
 * account is optional on the backend; the disclosure below mirrors that.
 */
export function CreateResellerDialog({ open, onOpenChange }: CreateResellerDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateResellerValues>({
    resolver: zodResolver(createResellerSchema),
    defaultValues: EMPTY_VALUES,
  });

  const createAdmin = useWatch({
    control: form.control,
    name: "createAdmin",
    defaultValue: false,
  });

  /** Reset transient state whenever the dialog closes. */
  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
    }
    onOpenChange(nextOpen);
  }

  async function onSubmit(values: CreateResellerValues) {
    setAlert(null);
    try {
      const reseller = await createReseller(
        toCreateResellerPayload(values),
      );
      toast.success("Reseller created", {
        description:
          values.createAdmin
            ? "The administrator can sign in with the credentials you entered."
            : "An administrator account can be provisioned later.",
      });
      await queryClient.invalidateQueries({ queryKey: resellersKeys.all });
      handleOpenChange(false);
      router.push(`/resellers/${reseller.id}`);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // Backend nests admin field paths ("admin.email"); map to flat keys.
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      const key = fieldError.field.startsWith("admin.")
        ? `admin${fieldError.field.slice("admin.".length)}`
        : fieldError.field;
      if (key in createResellerSchema.shape) {
        form.setError(key as keyof CreateResellerValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    // 409 duplicates (slug/custom domain/email) carry meaningful messages.
    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Create reseller</DialogTitle>
          <DialogDescription>
            Provisions a reseller organization. Administrator creation is
            optional.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Company</FieldLegend>
              <TextField
                label="Name"
                autoComplete="organization"
                placeholder="VoiceHub Partners"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextField
                label="Slug"
                placeholder="voicehub-partners"
                description="Lowercase letters, digits and hyphens. Permanent identifier."
                registration={form.register("slug")}
                error={form.formState.errors.slug?.message}
              />
              <TextField
                label="Display name"
                placeholder="VoiceHub"
                registration={form.register("displayName")}
                error={form.formState.errors.displayName?.message}
              />
              <TextField
                label="Custom domain"
                placeholder="portal.voicehub.com"
                description="Permanent once created."
                registration={form.register("customDomain")}
                error={form.formState.errors.customDomain?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Branding &amp; contact</FieldLegend>
              <FieldDescription>Optional.</FieldDescription>
              <TextField
                label="Support email"
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="support@voicehub.com"
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
            </FieldSet>

            <Field>
              <CheckboxField
                label="Create administrator account now"
                description="Optional. A reseller can exist without its RESELLER_ADMIN user."
                checked={createAdmin}
                onCheckedChange={(value) =>
                  form.setValue("createAdmin", value, { shouldValidate: false })
                }
              />
            </Field>

            <Collapsible open={createAdmin}>
              <CollapsibleContent>
                <FieldSet className="pt-2">
                  <FieldLegend>Administrator account</FieldLegend>
                  <FieldDescription>
                    This account administers the new reseller. Password
                    minimum is 12 characters.
                  </FieldDescription>
                  <Controller
                    control={form.control}
                    name="adminEmail"
                    render={({ field }) => (
                      <TextField
                        label="Admin email"
                        type="email"
                        inputMode="email"
                        autoComplete="email"
                        placeholder="admin@voicehub.com"
                        value={field.value}
                        onChange={field.onChange}
                        onBlur={field.onBlur}
                        error={form.formState.errors.adminEmail?.message}
                      />
                    )}
                  />
                  <Controller
                    control={form.control}
                    name="adminPassword"
                    render={({ field }) => (
                      <TextField
                        label="Admin password"
                        type="password"
                        autoComplete="new-password"
                        value={field.value}
                        onChange={field.onChange}
                        onBlur={field.onBlur}
                        error={form.formState.errors.adminPassword?.message}
                      />
                    )}
                  />
                  <TextField
                    label="Display name"
                    placeholder="Alex Admin"
                    registration={form.register("adminDisplayName")}
                    error={form.formState.errors.adminDisplayName?.message}
                  />
                </FieldSet>
              </CollapsibleContent>
            </Collapsible>

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
                  "Create reseller"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
