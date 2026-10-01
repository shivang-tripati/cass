"use client";

import { useFieldArray, useFormContext, type FieldPath } from "react-hook-form";
import { PlusIcon, Trash2Icon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  FieldDescription,
  FieldError,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import {
  TTS_VARIABLES_MAX,
  TTS_VARIABLE_TYPES,
  templateContractIssues,
  type TtsTemplateFormValues,
} from "@/lib/schemas/tts-template-mutation";

/**
 * The declared-variable editor, shared by the create and edit dialogs.
 *
 * ## Why it is extracted
 *
 * The pre-F3 dialogs each had their own copy of this field array, with the same
 * `as any` casts on every `register` / `watch` / `setValue` and its own
 * hand-rolled label markup. Extracting it is what makes those casts removable:
 * the form context is typed against `TtsTemplateFormValues`, so
 * `variables.${index}.name` is a checked `FieldPath` instead of a silenced
 * string. The component requires a `FormProvider` from its parent, which both
 * dialogs already wrap around.
 *
 * ## What the rules are
 *
 * Every rule shown here is a port of `TtsTemplateValidation`, not a product
 * decision. The one that is easy to get wrong is the last: a declared variable
 * that is never referenced is **legal**, so the editor does not warn about
 * unused entries, and a template with declared-but-unused variables is submitted
 * without complaint.
 *
 * Contract errors that belong to the array as a whole — a duplicate name, a
 * malformed name, an unsupported type — are surfaced here, derived from the same
 * `templateContractIssues` the submit path uses, so what the user sees while
 * typing and what blocks submission can never disagree.
 */
export function TtsTemplateVariablesField({
  disabled = false,
}: {
  disabled?: boolean;
}) {
  const form = useFormContext<TtsTemplateFormValues>();
  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "variables",
  });

  const variables = form.watch("variables") ?? [];
  const templateText = form.watch("templateText") ?? "";
  const arrayIssues = templateContractIssues(templateText, variables).filter(
    (issue) => issue.path === "variables",
  );

  const atLimit = fields.length >= TTS_VARIABLES_MAX;

  function setField(
    index: number,
    key: "type" | "required",
    value: string | boolean | null,
  ) {
    const path = `variables.${index}.${key}` as FieldPath<TtsTemplateFormValues>;
    form.setValue(path, value as never, {
      shouldDirty: true,
      shouldValidate: true,
    });
  }

  return (
    <FieldSet>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <FieldLegend>
          Variables{" "}
          <span className="font-normal text-muted-foreground">
            ({fields.length} of {TTS_VARIABLES_MAX})
          </span>
        </FieldLegend>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={disabled || atLimit}
          onClick={() => append({ name: "", type: "STRING", required: false })}
        >
          <PlusIcon aria-hidden="true" />
          Add variable
        </Button>
      </div>

      <FieldDescription>
        Every <code>{"{{placeholder}}"}</code> in the template text must name a
        variable declared here, and nothing else may use curly braces. A variable
        you declare but do not use is allowed.
      </FieldDescription>

      {fields.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No variables declared. The template text must then contain no
          placeholders.
        </p>
      ) : null}

      <ul className="space-y-2">
        {fields.map((field, index) => {
          const nameId = `tts-var-name-${field.id}`;
          const typeId = `tts-var-type-${field.id}`;
          const requiredId = `tts-var-required-${field.id}`;
          return (
            <li
              key={field.id}
              className="flex flex-wrap items-end gap-2 rounded border p-2"
            >
              <div className="min-w-32 flex-1">
                <Label htmlFor={nameId} className="text-xs">
                  Name
                </Label>
                <Input
                  id={nameId}
                  placeholder="firstName"
                  disabled={disabled}
                  {...form.register(
                    `variables.${index}.name` as FieldPath<TtsTemplateFormValues>,
                  )}
                />
              </div>

              <div className="w-32">
                <Label htmlFor={typeId} className="text-xs">
                  Type
                </Label>
                <Select
                  value={variables[index]?.type || undefined}
                  disabled={disabled}
                  onValueChange={(value) => setField(index, "type", value)}
                >
                  <SelectTrigger
                    id={typeId}
                    aria-label={`Type for variable ${index + 1}`}
                  >
                    <SelectValue placeholder="STRING" />
                  </SelectTrigger>
                  <SelectContent>
                    {TTS_VARIABLE_TYPES.map((type) => (
                      <SelectItem key={type} value={type}>
                        {type}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex items-center gap-2 pb-2">
                <Checkbox
                  id={requiredId}
                  checked={variables[index]?.required === true}
                  disabled={disabled}
                  onCheckedChange={(value) =>
                    setField(index, "required", value === true)
                  }
                />
                <Label
                  htmlFor={requiredId}
                  className="whitespace-nowrap text-sm font-normal"
                >
                  Required
                </Label>
              </div>

              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Remove variable ${index + 1}`}
                disabled={disabled}
                onClick={() => remove(index)}
              >
                <Trash2Icon aria-hidden="true" />
              </Button>
            </li>
          );
        })}
      </ul>

      {arrayIssues.length > 0 ? (
        <FieldError>
          {arrayIssues.map((issue, index) => (
            <span key={`${issue.message}-${index}`} className="block">
              {issue.message}
            </span>
          ))}
        </FieldError>
      ) : null}
    </FieldSet>
  );
}
