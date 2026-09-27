"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { toast } from "sonner";

import { Alert, AlertTitle } from "@/components/ui/alert";
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
import { toApiError } from "@/lib/api/error";
import type { ChangePasswordValues } from "@/lib/schemas/auth";
import { changePasswordSchema } from "@/lib/schemas/auth";
import { endLocalSession, useChangePassword } from "@/lib/session";

interface ChangePasswordDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const EMPTY_VALUES: ChangePasswordValues = {
  currentPassword: "",
  newPassword: "",
  confirmPassword: "",
};

/**
 * Controlled change-password dialog. On success the backend has already
 * revoked every refresh session (PASSWORD_CHANGED), so the local session
 * is ended and the user is returned to sign-in.
 */
export function ChangePasswordDialog({
  open,
  onOpenChange,
}: ChangePasswordDialogProps) {
  const router = useRouter();
  const changePassword = useChangePassword();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<ChangePasswordValues>({
    resolver: zodResolver(changePasswordSchema),
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

  async function onSubmit(values: ChangePasswordValues) {
    setAlert(null);
    try {
      await changePassword.mutateAsync(values);
      toast.success("Password changed", {
        description:
          "All sessions were signed out for security. Please sign in again.",
      });
      endLocalSession();
      onOpenChange(false);
      router.replace("/sign-in");
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      if (fieldError.field in changePasswordSchema.shape) {
        form.setError(fieldError.field as keyof ChangePasswordValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    // Includes wrong-current-password 401s ("Invalid email or password.")
    // and business rules such as unchanged-password 422s.
    setAlert(apiError.message);
  }

  const pending = changePassword.isPending;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Change password</DialogTitle>
          <DialogDescription>
            Changing your password signs you out of all devices.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <Alert variant="destructive">
            <AlertTitle>{alert}</AlertTitle>
          </Alert>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <TextField
              label="Current password"
              type="password"
              autoComplete="current-password"
              registration={form.register("currentPassword")}
              error={form.formState.errors.currentPassword?.message}
            />
            <TextField
              label="New password"
              type="password"
              autoComplete="new-password"
              description="Minimum 12 characters."
              registration={form.register("newPassword")}
              error={form.formState.errors.newPassword?.message}
            />
            <TextField
              label="Confirm new password"
              type="password"
              autoComplete="new-password"
              registration={form.register("confirmPassword")}
              error={form.formState.errors.confirmPassword?.message}
            />
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
                    Updating…
                  </>
                ) : (
                  "Update password"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
