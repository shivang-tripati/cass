"use client";

import { useId } from "react";
import type { UseFormRegisterReturn } from "react-hook-form";

import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { cn } from "@/lib/utils";

interface TextFieldProps extends React.ComponentProps<typeof Input> {
  label: string;
  /** RHF binding; omit for read-only display fields. */
  registration?: UseFormRegisterReturn;
  /** First resolved validation message for this field, if any. */
  error?: string;
  description?: string;
}

/**
 * Single-line text input bound to React Hook Form with accessible
 * label/description/error wiring.
 */
export function TextField({
  label,
  registration,
  error,
  description,
  className,
  ...inputProps
}: TextFieldProps) {
  const generatedId = useId();
  const inputId = inputProps.id ?? `field-${generatedId}`;
  const describedBy = [
    description ? `${inputId}-description` : null,
    error ? `${inputId}-error` : null,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <Field data-invalid={error ? true : undefined}>
      <FieldLabel htmlFor={inputId}>{label}</FieldLabel>
      {description ? (
        <FieldDescription id={`${inputId}-description`}>
          {description}
        </FieldDescription>
      ) : null}
      <Input
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={cn(className)}
        {...registration}
        {...inputProps}
      />
      {error ? (
        <FieldError id={`${inputId}-error`} role="alert">
          {error}
        </FieldError>
      ) : null}
    </Field>
  );
}

interface CheckboxFieldProps {
  label: string;
  description?: string;
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
}

/** Checkbox bound to boolean form state (RHF watch/setValue pattern). */
export function CheckboxField({
  label,
  description,
  checked,
  onCheckedChange,
}: CheckboxFieldProps) {
  const generatedId = useId();
  const checkboxId = `checkbox-${generatedId}`;
  const descriptionId = `${checkboxId}-description`;

  return (
    <div className="flex items-start gap-2.5">
      <Checkbox
        id={checkboxId}
        className="mt-0.5"
        checked={checked}
        onCheckedChange={(value) => onCheckedChange(value === true)}
        aria-describedby={description ? descriptionId : undefined}
      />
      <Label
        htmlFor={checkboxId}
        className="flex flex-col gap-0.5 leading-snug"
      >
        <span className="text-sm font-medium">{label}</span>
        {description ? (
          <span
            id={descriptionId}
            className="text-muted-foreground text-sm font-normal"
          >
            {description}
          </span>
        ) : null}
      </Label>
    </div>
  );
}
