"use client";

import { useState } from "react";
import { zodResolver } from "@hookform/resolvers/zod";
import { Controller, useForm } from "react-hook-form";
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
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { toApiError } from "@/lib/api/error";
import type { UserResponse } from "@/lib/api/contracts";
import { usersKeys, updateUser } from "@/lib/api/users";
import type { EditUserValues } from "@/lib/schemas/user-update";
import { editUserSchema, toUpdateUserRequest } from "@/lib/schemas/user-update";

interface EditUserDialogProps {
  user: UserResponse | null;
  onOpenChange: (open: boolean) => void;
}

/**
 * Focused edit dialog for the two backend-supported fields
 * (displayName + status). Blank display name is omitted — the backend
 * treats blank as "no change".
 *
 * Mount-scoped: parents render one instance per target user (keyed by id),
 * so the form seeds itself from props without reset effects.
 */
export function EditUserDialog({ user, onOpenChange }: EditUserDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<EditUserValues>({
    resolver: zodResolver(editUserSchema),
    defaultValues: {
      displayName: user?.displayName ?? "",
      status: user?.status ?? "ACTIVE",
    },
  });

  if (!user) return null;

  async function onSubmit(values: EditUserValues) {
    if (!user) return;
    setAlert(null);
    try {
      await updateUser(user.id, toUpdateUserRequest(values, user));
      toast.success("User updated", {
        description: `${user.email} was saved.`,
      });
      // Targeted invalidation: this list page and the detail cache.
      await queryClient.invalidateQueries({ queryKey: usersKeys.all });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      for (const fieldError of apiError.fieldErrors) {
        if (fieldError.field in editUserSchema.shape) {
          form.setError(fieldError.field as keyof EditUserValues, {
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
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Edit user</DialogTitle>
          <DialogDescription>
            Email, roles and organization are managed by the platform and
            cannot be changed here.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="Email"
                value={user.email}
                readOnly
                disabled
                aria-readonly="true"
              />
              <Controller
                control={form.control}
                name="status"
                render={({ field }) => (
                  <Field>
                    <FieldLabel htmlFor="edit-user-status">Status</FieldLabel>
                    <Select
                      value={field.value}
                      onValueChange={field.onChange}
                    >
                      <SelectTrigger id="edit-user-status" className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="ACTIVE">Active</SelectItem>
                        <SelectItem value="SUSPENDED">Suspended</SelectItem>
                      </SelectContent>
                    </Select>
                  </Field>
                )}
              />
            </div>
            <TextField
              label="Display name"
              registration={form.register("displayName")}
              error={form.formState.errors.displayName?.message}
              description="Shown across the platform. Leave empty to keep unchanged."
            />

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
