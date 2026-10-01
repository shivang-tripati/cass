"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { toast } from "sonner";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { FieldDescription, FieldGroup, FieldLegend } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import { signupTenant } from "@/lib/api/signup";
import type { TenantSignupValues } from "@/lib/schemas/signup";
import { tenantSignupSchema } from "@/lib/schemas/signup";
import { toSignupFormFieldKey } from "@/components/auth/signup-error-mapping";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";

interface FormAlert {
  title: string;
  description?: string;
}

export function TenantSignupForm() {
  const router = useRouter();
  const [alert, setAlert] = useState<FormAlert | null>(null);

  const form = useForm<TenantSignupValues>({
    resolver: zodResolver(tenantSignupSchema),
    defaultValues: {
      name: "",
      slug: "",
      adminEmail: "",
      adminPassword: "",
      adminDisplayName: "",
    },
  });

  async function onSubmit(values: TenantSignupValues) {
    setAlert(null);
    try {
      await signupTenant({
        name: values.name,
        slug: values.slug,
        admin: {
          email: values.adminEmail,
          password: values.adminPassword,
          displayName: values.adminDisplayName || undefined,
        },
      });
      toast.success("Tenant created", {
        description:
          "Sign in with the administrator account you just created.",
      });
      router.replace("/sign-in");
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F1: shared mapping, which also DROPS a field this form does not declare
    // instead of attaching the message to an arbitrary control.
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(tenantSignupSchema.shape),
      (field, message) => {
        form.setError(field as keyof TenantSignupValues, { message });
      },
      toSignupFormFieldKey,
    );
    if (mapped > 0 && apiError.status === 400) return;

    setAlert({ title: apiError.message });
  }

  const pending = form.formState.isSubmitting;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-xl">Create a tenant account</CardTitle>
        <CardDescription>
          Set up a standalone organization with its administrator account.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {alert ? (
          <Alert variant="destructive" className="mb-5">
            <AlertTitle>{alert.title}</AlertTitle>
            {alert.description ? (
              <AlertDescription>{alert.description}</AlertDescription>
            ) : null}
          </Alert>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <TextField
              label="Organization name"
              autoComplete="organization"
              placeholder="Acme Communications"
              registration={form.register("name")}
              error={form.formState.errors.name?.message}
            />
            <TextField
              label="Slug"
              placeholder="acme-communications"
              description="Lowercase letters, digits and hyphens. Used in URLs and identifiers."
              registration={form.register("slug")}
              error={form.formState.errors.slug?.message}
            />

            <div className="space-y-4">
              <FieldLegend>Administrator account</FieldLegend>
              <FieldDescription>
                This account administers the new tenant.
              </FieldDescription>
            </div>

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
              label="Password"
              type="password"
              autoComplete="new-password"
              description="Minimum 8 characters."
              registration={form.register("adminPassword")}
              error={form.formState.errors.adminPassword?.message}
            />
            <TextField
              label="Display name"
              placeholder="Alex Admin"
              autoComplete="nickname"
              registration={form.register("adminDisplayName")}
              error={form.formState.errors.adminDisplayName?.message}
            />

            <Button type="submit" className="w-full" disabled={pending}>
              {pending ? (
                <>
                  <Spinner aria-hidden="true" />
                  Creating tenant…
                </>
              ) : (
                "Create tenant"
              )}
            </Button>
          </FieldGroup>
        </form>

        <div className="mt-6 space-y-1 text-center text-sm text-muted-foreground">
          <p>
            Already have an account?{" "}
            <Link
              href="/sign-in"
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              Sign in
            </Link>
          </p>
          <p>
            Running a reseller business?{" "}
            <Link
              href="/sign-up/reseller"
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              Sign up as a reseller
            </Link>
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
