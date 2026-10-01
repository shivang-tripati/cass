"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { toast } from "sonner";

import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
import { CheckboxField, TextField } from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import { signupReseller } from "@/lib/api/signup";
import type { ResellerSignupValues } from "@/lib/schemas/signup";
import { resellerSignupSchema } from "@/lib/schemas/signup";
import { toSignupFormFieldKey } from "@/components/auth/signup-error-mapping";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";

interface FormAlert {
  title: string;
}

export function ResellerSignupForm() {
  const router = useRouter();
  const [alert, setAlert] = useState<FormAlert | null>(null);

  const form = useForm<ResellerSignupValues>({
    resolver: zodResolver(resellerSignupSchema),
    defaultValues: {
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
    },
  });

  const createAdmin = useWatch({
    control: form.control,
    name: "createAdmin",
    defaultValue: false,
  });

  async function onSubmit(values: ResellerSignupValues) {
    setAlert(null);
    try {
      await signupReseller({
        name: values.name,
        slug: values.slug,
        displayName: values.displayName || undefined,
        supportEmail: values.supportEmail || undefined,
        customDomain: values.customDomain || undefined,
        logoUrl: values.logoUrl || undefined,
        primaryColor: values.primaryColor || undefined,
        admin: values.createAdmin
          ? {
              email: values.adminEmail,
              password: values.adminPassword,
              displayName: values.adminDisplayName || undefined,
            }
          : null,
      });
      toast.success("Reseller created", {
        description:
          "Sign in once an administrator account has been set up for you.",
      });
      router.replace("/sign-in");
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F1: shared mapping, which also DROPS a field this form does not declare.
    const mapped = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(resellerSignupSchema.shape),
      (field, message) => {
        form.setError(field as keyof ResellerSignupValues, { message });
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
        <CardTitle className="text-xl">Create a reseller account</CardTitle>
        <CardDescription>
          Register a reseller organization on the OBD Platform.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {alert ? (
          <Alert variant="destructive" className="mb-5">
            <AlertTitle>{alert.title}</AlertTitle>
          </Alert>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Company</FieldLegend>
              <TextField
                label="Company name"
                autoComplete="organization"
                placeholder="VoiceHub Partners"
                registration={form.register("name")}
                error={form.formState.errors.name?.message}
              />
              <TextField
                label="Slug"
                placeholder="voicehub-partners"
                description="Lowercase letters, digits and hyphens. Used in URLs and identifiers."
                registration={form.register("slug")}
                error={form.formState.errors.slug?.message}
              />
              <TextField
                label="Display name"
                placeholder="VoiceHub"
                registration={form.register("displayName")}
                error={form.formState.errors.displayName?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Branding &amp; contact</FieldLegend>
              <FieldDescription>
                Optional. Shown to tenants managed under this reseller.
              </FieldDescription>
              <TextField
                label="Support email"
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder="support@voicehub.com"
                registration={form.register("supportEmail")}
                error={form.formState.errors.supportEmail?.message}
              />
              <TextField
                label="Custom domain"
                placeholder="portal.voicehub.com"
                registration={form.register("customDomain")}
                error={form.formState.errors.customDomain?.message}
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
                description="Optional. A reseller can be registered without its RESELLER_ADMIN user."
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
                    This account administers the new reseller.
                  </FieldDescription>
                  <TextField
                    label="Admin email"
                    type="email"
                    inputMode="email"
                    autoComplete="email"
                    placeholder="admin@voicehub.com"
                    registration={form.register("adminEmail")}
                    error={form.formState.errors.adminEmail?.message}
                  />
                  <TextField
                    label="Password"
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
              </CollapsibleContent>
            </Collapsible>

            <Button type="submit" className="w-full" disabled={pending}>
              {pending ? (
                <>
                  <Spinner aria-hidden="true" />
                  Creating reseller…
                </>
              ) : (
                "Create reseller"
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
            Setting up a single tenant?{" "}
            <Link
              href="/sign-up/tenant"
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              Sign up as a tenant
            </Link>
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
