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
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { SelectField } from "@/components/forms/select-field";
import { toApiError } from "@/lib/api/error";
import type { DidResponse } from "@/lib/api/contracts";
import { didsKeys, updateDid } from "@/lib/api/dids";
import type { EditDidValues } from "@/lib/schemas/did-mutation";
import {
  editDidSchema,
  toUpdateDidPayload,
} from "@/lib/schemas/did-mutation";

interface EditDidDialogProps {
  did: DidResponse | null;
  onOpenChange: (open: boolean) => void;
}

/**
 * DID configuration update (PUT /api/v1/dids/{id}).
 * PUT semantics: omitted optional fields are cleared.
 * E.164 number and ownership are immutable.
 */
export function EditDidDialog({ did, onOpenChange }: EditDidDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<EditDidValues>({
    resolver: zodResolver(editDidSchema),
    defaultValues: {
      countryCode: did?.countryCode ?? "",
      areaCode: did?.areaCode ?? "",
      circle: did?.circle ?? "",
      numberType: did?.numberType ?? "LANDLINE",
      provider: did?.provider ?? "",
      capabilities: did?.capabilities ?? [],
      status: did?.status ?? "ACTIVE",
      allocationState: did?.allocationState ?? "AVAILABLE",
    },
  });

  if (!did) return null;

  async function onSubmit(values: EditDidValues) {
    if (!did) return;
    setAlert(null);
    try {
      await updateDid(did.id, toUpdateDidPayload(values));
      toast.success("DID updated", {
        description: `${did.e164Number} was saved.`,
      });
      await queryClient.invalidateQueries({ queryKey: didsKeys.all });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      for (const fieldError of apiError.fieldErrors) {
        if (fieldError.field in editDidSchema.shape) {
          form.setError(fieldError.field as keyof EditDidValues, {
            message: fieldError.message,
          });
          return;
        }
      }
      setAlert(apiError.message);
    }
  }

  const pending = form.formState.isSubmitting;

  const numberTypeOptions = [
    { value: "LANDLINE", label: "Landline" },
    { value: "MOBILE", label: "Mobile" },
    { value: "PROMOTIONAL_140", label: "Promotional (140)" },
  ] as const;

  const statusOptions = [
    { value: "ACTIVE", label: "Active" },
    { value: "INACTIVE", label: "Inactive" },
  ] as const;

  const allocationStateOptions = [
    { value: "AVAILABLE", label: "Available" },
    { value: "ASSIGNED", label: "Assigned" },
  ] as const;

  const capabilityOptions = [
    { value: "VOICE_OUTBOUND", label: "Voice Outbound" },
  ] as const;

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit DID</DialogTitle>
          <DialogDescription>
            E.164 number and ownership are immutable and cannot be changed.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <div className="space-y-4">
              <TextField label="E.164 Number" value={did.e164Number} readOnly disabled />
              <SelectField
                label="Number Type"
                name="numberType"
                options={numberTypeOptions}
                error={form.formState.errors.numberType?.message}
                control={form.control}
              />
              <TextField
                label="Provider"
                registration={form.register("provider")}
                error={form.formState.errors.provider?.message}
              />
              <TextField
                label="Country Code"
                registration={form.register("countryCode")}
                error={form.formState.errors.countryCode?.message}
              />
              <TextField
                label="Area Code"
                registration={form.register("areaCode")}
                error={form.formState.errors.areaCode?.message}
              />
              <TextField
                label="Circle"
                registration={form.register("circle")}
                error={form.formState.errors.circle?.message}
              />
              <SelectField
                label="Status"
                name="status"
                options={statusOptions}
                error={form.formState.errors.status?.message}
                control={form.control}
              />
              <SelectField
                label="Allocation State"
                name="allocationState"
                options={allocationStateOptions}
                error={form.formState.errors.allocationState?.message}
                control={form.control}
              />
              <div className="space-y-2">
                <Label className="text-sm font-medium">Capabilities</Label>
                <div className="space-y-1">
                  {capabilityOptions.map((cap) => (
                    <div key={cap.value} className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        id={`capability-edit-${cap.value}`}
                        {...form.register("capabilities")}
                        value={cap.value}
                        className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                      />
                      <label
                        htmlFor={`capability-edit-${cap.value}`}
                        className="text-sm"
                      >
                        {cap.label}
                      </label>
                    </div>
                  ))}
                </div>
                {form.formState.errors.capabilities?.message && (
                  <p className="text-sm text-destructive" role="alert">
                    {form.formState.errors.capabilities.message}
                  </p>
                )}
              </div>
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