"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
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
import { toApiError } from "@/lib/api/error";
import { tenantsKeys, createTenant } from "@/lib/api/tenants";
import type { CreateTenantValues } from "@/lib/schemas/tenant-mutation";
import {
  createTenantSchema,
  toCreateTenantPayload,
} from "@/lib/schemas/tenant-mutation";

const EMPTY_VALUES: CreateTenantValues = {
  name: "",
  slug: "",
  adminEmail: "",
  adminPassword: "",
  adminDisplayName: "",
};

interface CreateTenantDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Tenant provisioning with its mandatory initial administrator
 * (POST /api/v1/tenants). Reseller scope is derived server-side; the
 * payload intentionally carries no resellerId.
 */
export function CreateTenantDialog({ open, onOpenChange }: CreateTenantDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateTenantValues>({
    resolver: zodResolver(createTenantSchema),
    defaultValues: EMPTY_VALUES,
  });

  /** Reset transient state whenever the dialog closes. */
  function handleOpenChange(nextOpen: boolean) {
    if (!nextOpen) {
      form.reset(EMPTY_VALUES);
      setAlert(null);
    }
    onOpenChange(nextOpen);
  }

  async function onSubmit(values: CreateTenantValues) {
    setAlert(null);
    try {
      const tenant = await createTenant(toCreateTenantPayload(values));
      toast.success("Tenant created", {
        description:
          "Sign in credentials for the administrator were provisioned.",
      });
      await queryClient.invalidateQueries({ queryKey: tenantsKeys.all });
      handleOpenChange(false);
      router.push(`/tenants/${tenant.id}`);
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
      if (key in createTenantSchema.shape) {
        form.setError(key as keyof CreateTenantValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    // 409 duplicates (slug/email) carry meaningful backend messages.
    setAlert(apiError.message);
  }

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Create tenant</DialogTitle>
          <DialogDescription>
            Provisions a tenant together with its TENANT_ADMIN account.
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
              <FieldLegend>Tenant</FieldLegend>
              <TextField
                label="Name"
                autoComplete="organization"
                placeholder="Acme Communications"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextField
                label="Slug"
                placeholder="acme-communications"
                description="Lowercase letters, digits and hyphens. Permanent identifier."
                registration={form.register("slug")}
                error={form.formState.errors.slug?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Initial administrator</FieldLegend>
              <FieldDescription>
                This account administers the new tenant. Password minimum is
                12 characters.
              </FieldDescription>
              <TextField
                label="Admin email"
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="admin@acme.com"
                registration={form.register("adminEmail")}
                error={form.formState.errors.adminEmail?.message}
              />
              <TextField
                label="Admin password"
                type="password"
                autoComplete="new-password"
                description="Minimum 12 characters."
                registration={form.register("adminPassword")}
                error={form.formState.errors.adminPassword?.message}
              />
              <TextField
                label="Display name"
                placeholder="Alex Admin"
                registration={form.register("adminDisplayName")}
                error={form.formState.errors.adminDisplayName?.message}
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
                  "Create tenant"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
