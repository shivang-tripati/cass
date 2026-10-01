"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
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
import { FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { audioAssetsKeys, updateAudioAsset } from "@/lib/api/audio-assets";
import { toApiError } from "@/lib/api/error";
import type { AudioAssetResponse } from "@/lib/api/contracts";
import {
  toUpdateAudioAssetPayload,
  updateAudioAssetSchema,
  type UpdateAudioAssetValues,
} from "@/lib/schemas/audio-asset-mutation";

function defaultsFor(asset: AudioAssetResponse): UpdateAudioAssetValues {
  return {
    name: asset.name,
    description: asset.description ?? "",
  };
}

/**
 * Edit an audio recording's metadata.
 *
 * ## Exactly two fields, because the DTO has exactly two
 *
 * VERIFIED `UpdateAudioAssetRequest` is `record (String name, String
 * description)`. The pre-F3 dialog also showed read-only `fileName` and
 * `contentType` rows, which is a reasonable way to show context but a poor way
 * to imply they are editable-in-place — F3 keeps them out of the form and shows
 * the real file facts on the detail page instead, where nothing looks editable.
 *
 * `description` is blank-to-null in `AudioAssetMapper`, so clearing it persists
 * as null. The payload therefore omits it rather than sending `""`.
 *
 * ## Status is NOT editable here
 *
 * `AudioAssetStatus` moves only through the approve/reject PATCH endpoints, and
 * doing it from a metadata form would bypass the confirmation that shows the
 * user what approval means. The dialog says the status is unchanged.
 *
 * ## Stale form state
 *
 * The parent passes `key={asset.id}`, so switching records remounts this
 * component and `defaultValues` is recomputed. Without that key a second asset
 * would open the form pre-filled with the first one's name — the pre-F3 bug.
 */
export function EditAudioAssetDialog({
  asset,
  open,
  onOpenChange,
}: {
  asset: AudioAssetResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);

  const form = useForm<UpdateAudioAssetValues>({
    resolver: zodResolver(updateAudioAssetSchema),
    defaultValues: defaultsFor(asset),
  });

  function handleOpenChange(next: boolean) {
    if (form.formState.isSubmitting) return;
    if (!next) {
      form.reset(defaultsFor(asset));
      setAlert(null);
    }
    onOpenChange(next);
  }

  const onSubmit = async (values: UpdateAudioAssetValues) => {
    setAlert(null);
    try {
      const updated = await updateAudioAsset(
        asset.id,
        toUpdateAudioAssetPayload(values),
      );
      await queryClient.invalidateQueries({ queryKey: audioAssetsKeys.all });
      toast.success("Recording updated", {
        description: `${updated.name} was saved. Its approval status is unchanged.`,
      });
      onOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      const mapped = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(updateAudioAssetSchema.shape),
        (field, message) => {
          form.setError(field as keyof UpdateAudioAssetValues, { message });
        },
      );
      if (mapped > 0 && apiError.status === 400) return;
      setAlert(apiError.message);
    }
  };

  const pending = form.formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Edit recording</DialogTitle>
          <DialogDescription>
            Update the name and notes for <strong>{asset.name}</strong>. The
            uploaded audio and its approval status are not changed here.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {alert}
          </p>
        ) : null}

        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <TextField
              label="Name"
              registration={form.register("name")}
              error={form.getFieldState("name").error?.message}
            />
            <TextareaField
              label="Description"
              registration={form.register("description")}
              error={form.getFieldState("description").error?.message}
              rows={3}
              placeholder="Optional notes about this recording"
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
