import { api } from "@/lib/api/client";
import type {
  AudioAssetResponse,
} from "@/lib/api/contracts";
import { unwrap } from "@/lib/api/auth";
import type {
  ApiResponse,
  PaginationMetadata,
} from "@/lib/api/types";

/**
 * /api/v1/audio-assets contract (verified against AudioAssetController/AudioAssetService):
 * - page zero-based; size server-clamped 1..100
 * - sort whitelist: name, fileName, createdAt, updatedAt, status; default createdAt,desc
 * - status: AudioAssetStatus name; unknown → 400
 * - search: case-insensitive contains over name
 * - DELETE is a soft delete (deletedAt stamp) returning bare 204
 */
export const AUDIO_ASSET_SORTABLE_FIELDS = [
  "name",
  "fileName",
  "createdAt",
  "updatedAt",
  "status",
] as const;

export type AudioAssetSortField = (typeof AUDIO_ASSET_SORTABLE_FIELDS)[number];

export interface AudioAssetListParams {
  page: number;
  size: number;
  sortField: AudioAssetSortField;
  sortDirection: "asc" | "desc";
  status?: AudioAssetStatus;
  search?: string;
}

/** Stable query-key factory for the Audio Assets module. */
export const audioAssetsKeys = {
  all: ["audio-assets"] as const,
  list: (params: AudioAssetListParams) => ["audio-assets", "list", params] as const,
  detail: (assetId: string) => ["audio-assets", "detail", assetId] as const,
};

export interface AudioAssetPage {
  items: AudioAssetResponse[];
  pagination: PaginationMetadata;
}

function buildSort(params: AudioAssetListParams): string {
  return `${params.sortField},${params.sortDirection}`;
}

export async function getAudioAssets(params: AudioAssetListParams): Promise<AudioAssetPage> {
  const { data } = await api.get<ApiResponse<AudioAssetResponse[]>>("/audio-assets", {
    params: {
      page: params.page,
      size: params.size,
      sort: buildSort(params),
      status: params.status || undefined,
      search: params.search || undefined,
    },
  });
  return {
    items: data.data ?? [],
    pagination:
      data.pagination ??
      {
        page: params.page,
        size: params.size,
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
        hasPrevious: false,
      },
  };
}

export function getAudioAsset(assetId: string): Promise<AudioAssetResponse> {
  return unwrap(api.get<ApiResponse<AudioAssetResponse>>(`/audio-assets/${assetId}`));
}

export function createAudioAsset(payload: import("@/lib/api/contracts").CreateAudioAssetPayload): Promise<AudioAssetResponse> {
  return unwrap(api.post<ApiResponse<AudioAssetResponse>>("/audio-assets", payload));
}

export function updateAudioAsset(assetId: string, payload: import("@/lib/api/contracts").UpdateAudioAssetPayload): Promise<AudioAssetResponse> {
  return unwrap(api.put<ApiResponse<AudioAssetResponse>>(`/audio-assets/${assetId}`, payload));
}

export async function deleteAudioAsset(assetId: string): Promise<void> {
  await api.delete(`/audio-assets/${assetId}`);
}

export function approveAudioAsset(assetId: string): Promise<AudioAssetResponse> {
  return unwrap(api.patch<ApiResponse<AudioAssetResponse>>(`/audio-assets/${assetId}/approve`));
}

export function rejectAudioAsset(assetId: string): Promise<AudioAssetResponse> {
  return unwrap(api.patch<ApiResponse<AudioAssetResponse>>(`/audio-assets/${assetId}/reject`));
}

/** com.shivang.obd.audio.AudioAssetStatus */
export type AudioAssetStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED";