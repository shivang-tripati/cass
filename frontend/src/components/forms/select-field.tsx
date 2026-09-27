"use client";

import { useId } from "react";
import { Controller, useFormContext } from "react-hook-form";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";

interface SelectOption {
  value: string;
  label: string;
}

interface SelectFieldProps {
  label: string;
  name: string;
  options: readonly SelectOption[];
  /** First resolved validation message for this field, if any. */
  error?: string;
  description?: string;
  placeholder?: string;
  /** Optional control from useForm; if omitted, uses FormProvider context via useFormContext. */
  control?: unknown;
}

export function SelectField({
  label,
  name,
  options,
  error,
  description,
  placeholder = "Select...",
  control: controlProp,
}: SelectFieldProps) {
  const contextControl = useFormContext();
  
  // Extract control from UseFormReturn if passed, otherwise use as-is or from context
  const control = ((): ReturnType<typeof import("react-hook-form").useForm>["control"] => {
    if (controlProp && typeof controlProp === "object" && "control" in controlProp) {
      return (controlProp as { control: ReturnType<typeof import("react-hook-form").useForm>["control"] }).control;
    }
    return (controlProp ?? contextControl) as ReturnType<typeof import("react-hook-form").useForm>["control"];
  })();
  
  if (!control) {
    throw new Error(
      "SelectField requires either a 'control' prop or a FormProvider ancestor. " +
      "Wrap your form in <FormProvider> or pass the control prop from useForm()."
    );
  }

  const generatedId = useId();
  const selectId = `select-${generatedId}`;
  const describedBy = [
    description ? `${selectId}-description` : null,
    error ? `${selectId}-error` : null,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <Field data-invalid={error ? true : undefined}>
      <FieldLabel htmlFor={selectId}>{label}</FieldLabel>
      {description ? (
        <FieldDescription id={`${selectId}-description`}>
          {description}
        </FieldDescription>
      ) : null}
      <Controller
        control={control}
        name={name}
        render={({ field }) => (
          <Select
            value={field.value}
            onValueChange={field.onChange}
            aria-invalid={error ? true : undefined}
            aria-describedby={describedBy || undefined}
          >
            <SelectTrigger id={selectId} className="w-full">
              <SelectValue placeholder={placeholder} />
            </SelectTrigger>
            <SelectContent>
              {options.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      />
      {error ? (
        <FieldError id={`${selectId}-error`} role="alert">
          {error}
        </FieldError>
      ) : null}
    </Field>
  );
}