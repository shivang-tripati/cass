"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { toast } from "sonner";
import { FileAudioIcon, UploadIcon, XIcon } from "lucide-react";

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
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { applyServerFieldErrors } from "@/components/auth/server-field-errors";
import { audioAssetsKeys, uploadAudioAsset } from "@/lib/api/audio-assets";
import { toApiError } from "@/lib/api/error";
import {
  AUDIO_ACCEPT_ATTRIBUTE,
  AUDIO_MAX_UPLOAD_BYTES,
  audioUploadFormSchema,
  describeUploadFileProblem,
  type AudioUploadTextValues,
} from "@/lib/schemas/audio-asset-mutation";

const EMPTY_VALUES: AudioUploadTextValues = { name: "", description: "" };

/**
 * Upload an audio recording.
 *
 * ## What this form asks for, and what it deliberately does not
 *
 * Three inputs: a name, an optional description, and a file. That is the whole
 * `@RequestParam` surface of `POST /api/v1/audio-assets/upload`.
 *
 * The form this replaces asked for `fileName`, `contentType`, `fileSize`,
 * `durationSeconds`, a 64-character SHA-256 `checksum` and a `storageReference`.
 * Every one of those is derived server-side by `AudioAssetService.upload`
 * (L201-212), and a user cannot compute or verify any of them. F3 deleted that
 * form rather than trimming it: there is no honest version of "please type the
 * SHA-256 of this file".
 *
 * ## The upload always lands in PENDING_APPROVAL
 *
 * VERIFIED: `entity.setStatus(AudioAssetStatus.PENDING_APPROVAL)` is
 * unconditional at L213. **Upload never approves.** The dialog says so up front
 * rather than letting the user discover a pending row afterwards, and the
 * success toast repeats it.
 *
 * ## File validation is a pre-flight, not a guarantee
 *
 * `AudioUploadValidator` decides the format from the file's **magic bytes** and
 * only then cross-checks the declared MIME type. A `.exe` renamed to `.mp3`
 * passes every browser-side check here and is still rejected by the server,
 * which is why the copy says the server inspects the actual audio content. The
 * local check exists to save a wasted upload of an obviously wrong file, and the
 * 5 MB check mirrors `AudioStorageProperties.maxFileSizeBytes` — a
 * server-configurable value, so the server's limit stays authoritative and the
 * local one is a fast failure.
 *
 * ## Errors
 *
 * A 400 carries a genuinely useful message (`InvalidAudioUploadException`
 * extends `BusinessException(VALIDATION_ERROR)`) and is shown verbatim. A 500
 * is the shape an unconfigured deployment returns — `AudioStorageException` has
 * no exception handler and falls through to the catch-all — so the dialog adds
 * the operational hint that audio storage may be disabled, rather than showing a
 * bare "something went wrong".
 */
export function UploadAudioAssetDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  // The file input is reset by CHANGING ITS KEY rather than through a ref.
  // A file input's `value` is not writable, so the usual `ref.current.value = ""`
  // trick does not work; bumping the key remounts the element and clears it.
  const [fileInputKey, setFileInputKey] = useState(0);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [alert, setAlert] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const form = useForm<AudioUploadTextValues>({
    resolver: zodResolver(audioUploadFormSchema),
    defaultValues: EMPTY_VALUES,
  });

  function handleOpenChange(next: boolean) {
    if (uploading) return;
    if (!next) {
      form.reset(EMPTY_VALUES);
      setFile(null);
      setFileError(null);
      setAlert(null);
      setFileInputKey((key) => key + 1);
    }
    onOpenChange(next);
  }

  function selectFile(next: File | null) {
    if (!next) {
      setFile(null);
      setFileError(null);
      return;
    }
    const problem = describeUploadFileProblem(next);
    if (problem) {
      setFile(null);
      setFileError(problem);
      setFileInputKey((key) => key + 1);
      return;
    }
    setFileError(null);
    setFile(next);
    // A name is the only required text field, and typing "welcome" beats typing
    // "welcome.mp3". Prefill it from the file name the moment one is chosen,
    // while leaving it fully editable.
    if (form.getValues("name").trim() === "") {
      form.setValue("name", next.name.replace(/\.[^.]+$/, ""), {
        shouldValidate: false,
      });
    }
  }

  async function onSubmit(values: AudioUploadTextValues) {
    setAlert(null);
    if (!file) {
      setFileError("Choose a WAV or MP3 recording to upload.");
      return;
    }
    setUploading(true);
    try {
      const asset = await uploadAudioAsset({
        name: values.name,
        description: values.description || undefined,
        file,
      });
      await queryClient.invalidateQueries({ queryKey: audioAssetsKeys.all });
      toast.success("Recording uploaded", {
        description: `${asset.name} is waiting for approval. Campaigns cannot use it until it is approved.`,
      });
      handleOpenChange(false);
    } catch (error) {
      const apiError = toApiError(error);
      const mapped = applyServerFieldErrors(
        apiError.fieldErrors,
        Object.keys(audioUploadFormSchema.shape),
        (field, message) => {
          form.setError(field as keyof AudioUploadTextValues, { message });
        },
      );
      if (mapped > 0 && apiError.status === 400) return;
      if (apiError.status >= 500) {
        // The documented-but-unimplemented 409 "Audio storage is disabled" is
        // really a 500: `AudioStorageException` is a bare RuntimeException with
        // no handler. `audio.storage.enabled` defaults to false, so this is the
        // expected response on a deployment that has not enabled storage, and a
        // generic "something went wrong" would send the user hunting.
        setAlert(
          "The server could not store the recording. If this deployment has not enabled audio storage, an administrator needs to set audio.storage.enabled.",
        );
        return;
      }
      setAlert(apiError.message);
    } finally {
      setUploading(false);
    }
  }

  const busy = uploading;
  const fileId = "audio-upload-file";

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex max-h-[85vh] max-w-xl flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>Upload recording</DialogTitle>
          <DialogDescription>
            Add a WAV or MP3 recording to your organization&apos;s library. It is
            created as <strong>pending approval</strong> and cannot be used by
            campaigns until someone with approval rights approves it.
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

        <form
          onSubmit={form.handleSubmit(onSubmit)}
          noValidate
          className="min-h-0 flex-1 overflow-y-auto px-6"
        >
          <FieldGroup>
            <FieldSet>
              <FieldLegend>Recording</FieldLegend>

              <div className="space-y-2">
                <Label htmlFor={fileId}>Audio file</Label>
                <input
                  key={fileInputKey}
                  id={fileId}
                  type="file"
                  accept={AUDIO_ACCEPT_ATTRIBUTE}
                  disabled={busy}
                  aria-invalid={fileError ? true : undefined}
                  aria-describedby={`${fileId}-help`}
                  onChange={(event) => selectFile(event.target.files?.[0] ?? null)}
                  className="block w-full text-sm disabled:opacity-60"
                />
                <p id={`${fileId}-help`} className="text-xs text-muted-foreground">
                  WAV or MP3, up to{" "}
                  {Math.round(AUDIO_MAX_UPLOAD_BYTES / (1024 * 1024))} MB. The
                  server checks the actual audio content, not just the file
                  name.
                </p>
                {fileError ? (
                  <p
                    role="alert"
                    className="text-sm font-medium text-destructive"
                  >
                    {fileError}
                  </p>
                ) : null}
                {file ? (
                  <div className="flex items-center gap-2 rounded border px-3 py-2 text-sm">
                    <FileAudioIcon
                      aria-hidden="true"
                      className="size-4 shrink-0 text-muted-foreground"
                    />
                    <span className="min-w-0 flex-1 truncate">{file.name}</span>
                    <span className="shrink-0 tabular-nums text-muted-foreground">
                      {(file.size / 1024).toFixed(0)} KB
                    </span>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Remove ${file.name}`}
                      disabled={busy}
                      onClick={() => selectFile(null)}
                    >
                      <XIcon aria-hidden="true" />
                    </Button>
                  </div>
                ) : null}
              </div>

              <TextField
                label="Name"
                placeholder="Welcome message"
                registration={form.register("name")}
                error={form.getFieldState("name").error?.message}
                description="How this recording appears when you pick it for a campaign."
              />
              <TextareaField
                label="Description"
                placeholder="Optional notes about this recording"
                registration={form.register("description")}
                error={form.getFieldState("description").error?.message}
                rows={3}
              />
            </FieldSet>

            <FieldSet>
              <FieldLegend>What the server fills in</FieldLegend>
              <FieldDescription>
                These are recorded automatically from the file and cannot be
                edited afterwards: the stored file name, the detected content
                type, the file size, a SHA-256 checksum of the bytes, and — for
                WAV — the duration. They are shown on the recording&apos;s page
                so you can confirm the upload is the file you expected.
              </FieldDescription>
            </FieldSet>

            <DialogFooter className="mt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => handleOpenChange(false)}
                disabled={busy}
              >
                Cancel
              </Button>
              <Button type="submit" disabled={busy || !file}>
                {busy ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Uploading…
                  </>
                ) : (
                  <>
                    <UploadIcon aria-hidden="true" />
                    Upload
                  </>
                )}
              </Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
