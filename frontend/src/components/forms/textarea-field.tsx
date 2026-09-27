"use client";

import { useId } from "react";
import type { UseFormRegisterReturn } from "react-hook-form";

import { Textarea } from "@/components/ui/textarea";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldLabel,
} from "@/components/ui/field";
import { cn } from "@/lib/utils";

interface TextareaFieldProps {
  label: string;
  /** RHF binding; omit for read-only display fields. */
  registration?: UseFormRegisterReturn;
  /** First resolved validation message for this field, if any. */
  error?: string;
  description?: string;
  className?: string;
  placeholder?: string;
  rows?: number;
  readOnly?: boolean;
}

export function TextareaField({
  label,
  registration,
  error,
  description,
  className,
  placeholder,
  rows = 4,
  readOnly,
}: TextareaFieldProps) {
  const generatedId = useId();
  const textareaId = `textarea-${generatedId}`;
  const describedBy = [
    description ? `${textareaId}-description` : null,
    error ? `${textareaId}-error` : null,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <Field data-invalid={error ? true : undefined}>
      <FieldLabel htmlFor={textareaId}>{label}</FieldLabel>
      {description ? (
        <FieldDescription id={`${textareaId}-description`}>
          {description}
        </FieldDescription>
      ) : null}
      <Textarea
        id={textareaId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={cn(className)}
        {...registration}
        placeholder={placeholder}
        rows={rows}
        readOnly={readOnly}
      />
      {error ? (
        <FieldError id={`${textareaId}-error`} role="alert">
          {error}
        </FieldError>
      ) : null}
    </Field>
  );
}