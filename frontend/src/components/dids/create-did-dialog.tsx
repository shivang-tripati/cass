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
import { SelectField } from "@/components/forms/select-field";
import { toApiError } from "@/lib/api/error";
import { createDid, didsKeys } from "@/lib/api/dids";
import type { CreateDidValues } from "@/lib/schemas/did-mutation";
import {
  createDidSchema,
  toCreateDidPayload,
} from "@/lib/schemas/did-mutation";

const EMPTY_VALUES: CreateDidValues = {
  e164Number: "",
  countryCode: "",
  areaCode: "",
  circle: "",
  numberType: "LANDLINE",
  provider: "",
  capabilities: [],
  status: undefined,
  allocationState: undefined,
  tenantId: undefined,
  resellerId: undefined,
};

interface CreateDidDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * DID registration (POST /api/v1/dids). Ownership is derived from the
 * caller's organizational context. tenantId/resellerId are honored only
 * within the caller's scope.
 */
export function CreateDidDialog({ open, onOpenChange }: CreateDidDialogProps) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<CreateDidValues>({
    resolver: zodResolver(createDidSchema),
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

  async function onSubmit(values: CreateDidValues) {
    setAlert(null);
    try {
      const did = await createDid(toCreateDidPayload(values));
      toast.success("DID created", {
        description: `${did.e164Number} was registered.`,
      });
      await queryClient.invalidateQueries({ queryKey: didsKeys.all });
      handleOpenChange(false);
      router.push(`/dids/${did.id}`);
    } catch (error) {
      applyServerError(error);
    }
  }

  function applyServerError(error: unknown) {
    const apiError = toApiError(error);
    let mapped = false;
    for (const fieldError of apiError.fieldErrors) {
      if (fieldError.field in createDidSchema.shape) {
        form.setError(fieldError.field as keyof CreateDidValues, {
          message: fieldError.message,
        });
        mapped = true;
      }
    }
    if (mapped && apiError.status === 400) return;

    // 409 duplicates (E.164) carry meaningful backend messages.
    setAlert(apiError.message);
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
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Register DID</DialogTitle>
          <DialogDescription>
            Register a canonical E.164 phone number. Ownership is derived
            from your organizational context.
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
              <FieldLegend>Number</FieldLegend>
              <TextField
                label="E.164 Number"
                placeholder="+918012345678"
                description="Canonical E.164 format with leading +. Must be unique."
                registration={form.register("e164Number")}
                error={form.formState.errors.e164Number?.message}
              />
              <TextField
                label="Country Code"
                placeholder="91"
                description="1-3 digits without leading +"
                registration={form.register("countryCode")}
                error={form.formState.errors.countryCode?.message}
              />
              <TextField
                label="Area Code"
                placeholder="80"
                description="Optional area/city code"
                registration={form.register("areaCode")}
                error={form.formState.errors.areaCode?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Classification</FieldLegend>
              <SelectField
                label="Number Type"
                name="numberType"
                options={numberTypeOptions}
                error={form.formState.errors.numberType?.message}
                control={form.control}
              />
              <TextField
                label="Provider"
                placeholder="TATA, Jio, Airtel, etc."
                registration={form.register("provider")}
                error={form.formState.errors.provider?.message}
              />
              <TextField
                label="Circle"
                placeholder="Mumbai, Delhi, Karnataka, etc."
                description="Optional telecom circle"
                registration={form.register("circle")}
                error={form.formState.errors.circle?.message}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>Capabilities</FieldLegend>
              <FieldDescription>
                Select the capabilities this DID supports.
              </FieldDescription>
              <div className="space-y-2">
                {capabilityOptions.map((cap) => (
                  <div key={cap.value} className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      id={`capability-${cap.value}`}
                      {...form.register("capabilities")}
                      value={cap.value}
                      className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                    />
                    <label
                      htmlFor={`capability-${cap.value}`}
                      className="text-sm font-medium"
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
            </FieldSet>

            <FieldSet>
              <FieldLegend>State</FieldLegend>
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
                    Registering…
                  </>
                ) : (
                  "Register DID"
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}