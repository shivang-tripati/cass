"use client";

import { useState } from "react";
import { useForm, FormProvider } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
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
import { FieldGroup, FieldSet, FieldLegend } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { TtsTemplateVariablesField } from "@/components/tts-templates/tts-template-variables-field";
import { TtsTemplateScopeBadge } from "@/components/tts-templates/tts-template-scope-badge";
import { updateTtsTemplate, ttsTemplatesKeys } from "@/lib/api/tts-templates";
import { toApiError } from "@/lib/api/error";
import type { TtsTemplateResponse, TtsTemplateVariable } from "@/lib/api/contracts";
import {
  toUpdateTtsTemplatePayload,
  updateTtsTemplateSchema,
  willTtsEditResetApproval,
  type TtsTemplateFormInput,
  type UpdateTtsTemplateValues,
} from "@/lib/schemas/tts-template-mutation";

function defaultsFor(
  template: TtsTemplateResponse,
): UpdateTtsTemplateValues {
  return {
    name: template.name,
    description: template.description ?? "",
    templateText: template.templateText,
    // `variables` is nullable on the response — `TtsTemplateMapper` stores null
    // for an empty list — so it is normalised to an empty array here. The
    // request field is `@NotNull`, so the form must always send an array.
    variables: (template.variables ?? []).map((variable) => ({
      name: variable.name,
      type: variable.type ?? "STRING",
      required: variable.required === true,
    })),
  };
}

/**
 * Edit a TTS template.
 *
 * ## The approval warning is now precise
 *
 * The pre-F3 dialog said, unconditionally, "Editing an approved template will
 * return it to pending approval." That is wrong most of the time.
 *
 * VERIFIED `TtsTemplateService.update` L181-186 gates the revert on:
 *
 * ```java
 * boolean contentChanged = !entity.getTemplateText().equals(request.templateText())
 *     || !toSchemaSet(entity.getVariables()).equals(toSchemaSet(request.variables));
 * ```
 *
 * `toSchemaSet` is the set of declared variable **names**. So the warning is
 * correct only when the text changes or the set of names changes. Renaming the
 * template, editing the description, reordering variables, toggling
 * `required`, or changing a variable's `type` all leave the template
 * `APPROVED`.
 *
 * `willTtsEditResetApproval` implements exactly that test, and the dialog
 * computes it live from the form so the warning appears and disappears as the
 * user types. Saying "this will re-enter review" when it will not is how a user
 * learns to ignore the warning.
 *
 * ## Scope and status are not editable
 *
 * `UpdateTtsTemplateRequest` has four fields. `scope` is immutable after
 * creation, and status moves only through approve/reject — so the dialog shows
 * the scope as a read-only badge and says so, instead of leaving the user to
 * wonder whether a scope switch is available.
 */
export function EditTtsTemplateDialog({
  template,
  open,
  onOpenChange,
}: {
  template: TtsTemplateResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const defaults = defaultsFor(template);
  const form = useForm<TtsTemplateFormInput, unknown, UpdateTtsTemplateValues>({
    // The third generic is required: the variable `type` select value is a raw
    // string in the form and a `TtsVariableType` union after validation, and
    // React Hook Form needs both types to type the resolver.
    resolver: zodResolver(updateTtsTemplateSchema),
    defaultValues: defaults,
  });

  // Live: the warning tracks what the user is actually typing.
  const watched = form.watch();
  const willResetApproval =
    template.status === "APPROVED" &&
    willTtsEditResetApproval(
      {
        templateText: template.templateText,
        variables: template.variables ?? [],
      },
      {
        templateText: watched.templateText ?? template.templateText,
        variables: (watched.variables ?? []) as TtsTemplateVariable[],
      },
    );

  function handleOpenChange(next: boolean) {
    if (form.formState.isSubmitting) return;
    if (!next) {
      form.reset(defaults);
      setAlert(null);
    }
    onOpenChange(next);
  }

  const onSubmit = async (values: UpdateTtsTemplateValues) => {
    setAlert(null);
    try {
      const updated = await updateTtsTemplate(
        template.id,
        toUpdateTtsTemplatePayload(values),
      );
      await queryClient.invalidateQueries({ queryKey: ttsTemplatesKeys.all });
      toast.success("Template updated", {
        description:
          updated.status === "PENDING_APPROVAL" && template.status === "APPROVED"
            ? `${updated.name} changed, so it is waiting for approval again.`
            : `${updated.name} was saved and stays ${updated.status === "APPROVED" ? "approved" : "pending approval"}.`,
      });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      const mapped = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(updateTtsTemplateSchema.shape),
        (field, message) => {
          form.setError(field as keyof UpdateTtsTemplateValues, { message });
        },
      );
      if (mapped > 0 && apiError.status === 400) return;
      setAlert(apiError.message);
    }
  };

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex max-h-[88vh] max-w-2xl flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>Edit template</DialogTitle>
          <DialogDescription>
            Update the content of <strong>{template.name}</strong>. Its scope and
            approval status are not changed here.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p
            role="alert"
            className="mx-6 rounded bg-destructive/10 p-3 text-sm font-medium text-destructive"
          >
            {alert}
          </p>
        ) : null}

        <div className="flex items-center gap-2 px-6">
          <span className="text-sm text-muted-foreground">Scope</span>
          <TtsTemplateScopeBadge scope={template.scope} />
        </div>

        {template.status === "APPROVED" ? (
          <p
            className={`mx-6 rounded border p-3 text-sm ${
              willResetApproval
                ? "border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-300"
                : "border-border text-muted-foreground"
            }`}
            aria-live="polite"
          >
            {willResetApproval
              ? "Changing the template text or the set of declared variable names returns this template to pending approval, so it leaves the shared catalog until it is reviewed again."
              : "This template is approved. Renaming it, changing the description, or adjusting variable types and required flags leaves it approved — only the text or the set of variable names re-enters review."}
          </p>
        ) : null}

        <FormProvider {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            noValidate
            className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6"
          >
            <FieldGroup>
              <FieldSet>
                <FieldLegend>Template</FieldLegend>
                <TextField
                  label="Name"
                  registration={form.register("name")}
                  error={form.getFieldState("name").error?.message}
                />
                <TextareaField
                  label="Description"
                  placeholder="Optional notes about this template"
                  registration={form.register("description")}
                  error={form.getFieldState("description").error?.message}
                  rows={2}
                />
                <TextareaField
                  label="Template text"
                  registration={form.register("templateText")}
                  error={form.getFieldState("templateText").error?.message}
                  rows={5}
                  description="Use {{variableName}} placeholders. Every placeholder must name a variable you declare below, and no other curly braces are allowed."
                />
              </FieldSet>

              <TtsTemplateVariablesField disabled={pending} />
            </FieldGroup>

            <DialogFooter>
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
                    Saving…
                  </>
                ) : (
                  "Save changes"
                )}
              </Button>
            </DialogFooter>
          </form>
        </FormProvider>
      </DialogContent>
    </Dialog>
  );
}
