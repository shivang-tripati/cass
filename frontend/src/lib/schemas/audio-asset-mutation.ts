import { z } from "zod";

import type { UpdateAudioAssetPayload } from "@/lib/api/contracts";

/**
 * Audio asset form schemas.
 *
 * VERIFIED (F3) against `AudioAssetController.upload`,
 * `AudioAssetService.upload`, `AudioUploadValidator` and the V20
 * `audio_assets` table.
 *
 * ## The creation form is an UPLOAD form
 *
 * `POST /api/v1/audio-assets/upload` is the only creation path this UI uses.
 * The pre-F3 dialog was built on `POST /api/v1/audio-assets` and asked the user
 * to type `fileName`, `contentType`, `fileSize`, `durationSeconds`, a
 * 64-character SHA-256 `checksum` and a `storageReference`. Every one of those
 * is either derived server-side by `/upload` or unverifiable by a human. F3
 * removed that form, its schema and its API call; there is no replacement for
 * the technical fields, because there is nothing to fill in.
 *
 * ## Which limits come from where
 *
 * This matters because the upload endpoint is NOT annotated the way the sibling
 * JSON endpoint is: `upload(...)` carries no `@Valid` and no `@Size` on any
 * `@RequestParam`. So:
 *
 *  - **`name` — 150 is a DATABASE limit, not a request-validation limit.**
 *    `audio_assets.name VARCHAR(150) NOT NULL` (V20). The only in-code check is
 *    the blank check at `AudioAssetService.upload` L198-200, which returns 400.
 *    A 200-character name therefore passes every controller annotation and fails
 *    at the database — as a **500**, not a field error. Enforcing 150 here is
 *    respecting the schema, not inventing a rule, and it is the reason this cap
 *    exists despite the absence of `@Size`.
 *  - **`description` — genuinely unbounded.** The column is `TEXT` and the
 *    upload path does not validate it, so F3 applies no length rule beyond a
 *    generous sanity cap. Adding the `@Size(max = 5000)` that
 *    `CreateAudioAssetRequest` carries would be a frontend-only restriction on a
 *    value the server accepts at any length.
 *  - **file size — enforced, but server-configurable.** `AudioUploadValidator`
 *    rejects anything above `audio.storage.max-file-size-bytes`, default
 *    5 MiB (`AudioStorageProperties`). The default is checked here as a
 *    pre-flight so an oversized file fails instantly instead of after a full
 *    upload, and the UI says the server's configured limit is authoritative.
 *  - **file type — the server is authoritative and F3 does not pretend to
 *    match it.** `AudioUploadValidator` detects format from MAGIC BYTES
 *    (RIFF…WAVE or an ID3/frame sync) and additionally requires the declared
 *    MIME to agree. The extension and MIME checks here are an advisory filter
 *    that saves a round trip; a `.exe` renamed to `.mp3` passes them and is
 *    still rejected server-side, which the dialog states outright.
 */
export const AUDIO_NAME_MAX = 150;
/** Sanity cap only — see the `description` note above. */
export const AUDIO_DESCRIPTION_MAX = 20_000;

/** VERIFIED default of `AudioStorageProperties.maxFileSizeBytes`. */
export const AUDIO_MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

/** VERIFIED: `AudioUploadValidator.ALLOWED_MIME`. */
export const AUDIO_ACCEPTED_MIME_TYPES = [
  "audio/wav",
  "audio/x-wav",
  "audio/wave",
  "audio/vnd.wave",
  "audio/mpeg",
  "audio/mp3",
] as const;

export const AUDIO_ACCEPT_ATTRIBUTE = ".wav,.mp3";

/** Lower-case extension, or `""` when the name has none. */
function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot === -1 ? "" : fileName.slice(dot).toLowerCase();
}

/**
 * Advisory pre-flight for a chosen file. Returns a message, or `null` when the
 * file passes.
 *
 * Deliberately advisory: extension and declared MIME are the two signals the
 * browser can produce, and the backend treats the first as low-trust
 * ("client-supplied, low trust") in favour of the magic-byte signature. The UI
 * must therefore say "the server checks the actual audio content" rather than
 * presenting this as validation that decides anything.
 */
export function describeUploadFileProblem(
  file: Pick<File, "name" | "size" | "type">,
): string | null {
  if (file.size === 0) {
    return `"${file.name}" is empty. Choose a WAV or MP3 recording that contains audio.`;
  }
  if (file.size > AUDIO_MAX_UPLOAD_BYTES) {
    return `"${file.name}" is ${(file.size / (1024 * 1024)).toFixed(1)} MB. The limit is 5 MB.`;
  }
  const extension = extensionOf(file.name);
  if (extension !== ".wav" && extension !== ".mp3") {
    return `"${extension || file.name}" is not a supported file type. Upload a .wav or .mp3 recording.`;
  }
  // Some browsers report an empty type for a valid file, and a few report
  // `application/octet-stream`. Rejecting on that would be a false negative, so
  // an absent or generic type is allowed through to the server.
  const declared = file.type.trim().toLowerCase();
  if (
    declared &&
    declared !== "application/octet-stream" &&
    !AUDIO_ACCEPTED_MIME_TYPES.includes(declared as (typeof AUDIO_ACCEPTED_MIME_TYPES)[number])
  ) {
    return `The browser reports this file as ${file.type}. The server only accepts WAV and MP3 audio.`;
  }
  return null;
}

/** The text half of the upload form. The chosen `File` is held alongside it
 *  rather than inside it, so the file picker and the text fields report their
 *  own errors independently — a 6 MB file should not blank out a valid name. */
export const audioUploadFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(
      AUDIO_NAME_MAX,
      `Name must be at most ${AUDIO_NAME_MAX} characters.`,
    ),
  description: z.string().trim().max(AUDIO_DESCRIPTION_MAX).optional(),
});

export type AudioUploadTextValues = z.infer<typeof audioUploadFormSchema>;

/** PUT /api/v1/audio-assets/{id} — `UpdateAudioAssetRequest` has exactly two
 *  components. `name` is `@NotBlank @Size(max = 150)`; `description` is
 *  `@Size(max = 5000)` and blank-to-null in `AudioAssetMapper`, so clearing it
 *  persists as null. */
export const updateAudioAssetSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Name is required.")
    .max(
      AUDIO_NAME_MAX,
      `Name must be at most ${AUDIO_NAME_MAX} characters.`,
    ),
  description: z.string().trim().max(AUDIO_DESCRIPTION_MAX).optional().nullable(),
});
export type UpdateAudioAssetValues = z.infer<typeof updateAudioAssetSchema>;

export function toUpdateAudioAssetPayload(
  values: UpdateAudioAssetValues,
): UpdateAudioAssetPayload {
  return {
    name: values.name,
    description: values.description || undefined,
  };
}
