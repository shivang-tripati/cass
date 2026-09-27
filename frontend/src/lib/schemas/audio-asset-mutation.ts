import { z } from "zod";
import type { CreateAudioAssetPayload, UpdateAudioAssetPayload } from "@/lib/api/contracts";

export const AUDIO_NAME_MAX = 150;
export const AUDIO_DESC_MAX = 5000;

const fileName = z.string().trim().min(1, "File name required.").max(255).regex(/^[^/\\]+$/, "No path separators.");
const contentType = z.string().trim().min(1, "Content type required.").max(100);
const fileSize = z.coerce.number().int().positive("Must be positive.");
const durationSeconds = z.coerce.number().int().positive().optional().nullable();
const checksum = z.string().trim().regex(/^[a-fA-F0-9]{64}$/, "64-char SHA-256 hex.").optional().nullable().or(z.literal("").transform(() => undefined));
const storageRef = z.string().trim().max(500).optional().nullable().or(z.literal("").transform(() => undefined));

export const createAudioAssetSchema = z.object({
  name: z.string().trim().min(1, "Name required.").max(AUDIO_NAME_MAX),
  description: z.string().trim().max(AUDIO_DESC_MAX).optional().nullable(),
  fileName,
  contentType,
  fileSize,
  durationSeconds,
  checksum,
  storageReference: storageRef,
});
export type CreateAudioAssetValues = z.infer<typeof createAudioAssetSchema>;
export function toCreatePayload(v: CreateAudioAssetValues): CreateAudioAssetPayload {
  return {
    name: v.name,
    description: v.description ?? undefined,
    fileName: v.fileName,
    contentType: v.contentType,
    fileSize: v.fileSize,
    durationSeconds: v.durationSeconds ?? undefined,
    checksum: (v.checksum as string) || undefined,
    storageReference: (v.storageReference as string) || undefined,
  };
}

export const updateAudioAssetSchema = z.object({
  name: z.string().trim().min(1, "Name required.").max(AUDIO_NAME_MAX),
  description: z.string().trim().max(AUDIO_DESC_MAX).optional().nullable(),
});
export type UpdateAudioAssetValues = z.infer<typeof updateAudioAssetSchema>;
export function toUpdatePayload(v: UpdateAudioAssetValues): UpdateAudioAssetPayload {
  return { name: v.name, description: v.description ?? undefined };
}
