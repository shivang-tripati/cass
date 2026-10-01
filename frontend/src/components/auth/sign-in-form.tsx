"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import type { SignInValues } from "@/lib/schemas/auth";
import { signInSchema } from "@/lib/schemas/auth";
import { useLogin } from "@/lib/session";

interface FormAlert {
  title: string;
  description?: string;
}

/** Only same-application relative targets may be used as post-login hops. */
function safeNextPath(raw: string | null): string {
  return raw !== null && raw.startsWith("/") && !raw.startsWith("//")
    ? raw
    : "/account";
}

export function SignInForm() {
  const router = useRouter();
  const login = useLogin();
  const [alert, setAlert] = useState<FormAlert | null>(null);

  const form = useForm<SignInValues>({
    resolver: zodResolver(signInSchema),
    defaultValues: { email: "", password: "" },
  });

  async function onSubmit(values: SignInValues) {
    setAlert(null);
    try {
      await login.mutateAsync(values);
      // Read the redirect target only when needed; avoids a render-time
      // dependency on search params (which forces a client-side prerender
      // bailout in Next.js).
      const next = new URLSearchParams(window.location.search).get("next");
      router.replace(safeNextPath(next));
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    // F1: shared mapping. The remap keeps the existing intent — LoginRequest
    // has exactly two fields, so anything that is not `password` is `email` —
    // while the shared helper is what guarantees an unmappable field is dropped
    // rather than attached to an arbitrary control.
    const mappedFieldCount = applyServerFieldErrors(
      apiError.fieldErrors,
      Object.keys(signInSchema.shape),
      (field, message) => {
        form.setError(field as "email" | "password", { message });
      },
      (field) => (field === "password" ? "password" : "email"),
    );
    if (mappedFieldCount > 0 && apiError.status === 400) return;

    setAlert({
      title: apiError.message,
      description:
        apiError.code === "RATE_LIMITED" && apiError.requestId
          ? `Too many attempts. Request ID: ${apiError.requestId}`
          : undefined,
    });
  }

  const pending = login.isPending;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-xl">Sign in</CardTitle>
        <CardDescription>
          Use your OBD Platform account credentials.
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
              label="Email"
              type="email"
              inputMode="email"
              autoComplete="email"
              placeholder="you@company.com"
              autoFocus
              registration={form.register("email")}
              error={form.formState.errors.email?.message}
            />
            <TextField
              label="Password"
              type="password"
              autoComplete="current-password"
              registration={form.register("password")}
              error={form.formState.errors.password?.message}
            />
            <Button type="submit" className="w-full" disabled={pending}>
              {pending ? (
                <>
                  <Spinner aria-hidden="true" />
                  Signing in…
                </>
              ) : (
                "Sign in"
              )}
            </Button>
          </FieldGroup>
        </form>

        <div className="mt-6 space-y-1 text-center text-sm text-muted-foreground">
          <p>
            Need an account?{" "}
            <Link
              href="/sign-up/tenant"
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              Sign up as a tenant
            </Link>{" "}
            or{" "}
            <Link
              href="/sign-up/reseller"
              className="font-medium text-foreground underline-offset-4 hover:underline"
            >
              as a reseller
            </Link>
            .
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
