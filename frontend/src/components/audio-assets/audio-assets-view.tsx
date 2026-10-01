"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MusicIcon, PlusIcon, SearchXIcon, XIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import { EmptyState, QueryErrorState } from "@/components/common/query-state";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
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
  AUDIO_ASSET_SORTABLE_FIELDS,
  AUDIO_ASSET_STATUSES,
  audioAssetsKeys,
  getAudioAssets,
  type AudioAssetListParams,
  type AudioAssetSortField,
} from "@/lib/api/audio-assets";
import type { AudioAssetStatus } from "@/lib/api/contracts";
import { AudioAssetTable, type AudioSort } from "@/components/audio-assets/audio-asset-table";
import { UploadAudioAssetDialog } from "@/components/audio-assets/upload-audio-asset-dialog";
import { useCan } from "@/lib/auth/use-can";
import { canCreateAudioAsset, canPerformAudioAction } from "@/lib/auth/content-gates";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const STATUS_LABELS: Record<AudioAssetStatus, string> = {
  PENDING_APPROVAL: "Pending approval",
  APPROVED: "Approved",
  REJECTED: "Rejected",
};

const ALL_STATUSES = "__all__";

const LIST_CONFIG = {
  sortableFields: AUDIO_ASSET_SORTABLE_FIELDS,
  // VERIFIED `AudioAssetService.DEFAULT_SORT` = `createdAt` DESC.
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50] as const,
  basePath: "/audio-assets" as const,
};

/**
 * Audio assets list.
 *
 * ## Authorization
 *
 * Three distinct decisions, resolved once here through the domain gate table
 * rather than at each call site:
 *
 *  - **Upload** needs `AUDIO_MANAGE` **and** a TENANT context. VERIFIED:
 *    `AudioAssetService.upload` L191-195 throws "A tenant must be specified for
 *    this operation." when `Scope.tenantId` is null, and the request DTO has no
 *    tenant field. A `SUPER_ADMIN` holds `AUDIO_MANAGE` and still cannot upload;
 *    a `RESELLER_ADMIN` cannot either, for the other reason. A capability-only
 *    check would offer both a button that can only fail.
 *  - **Edit / delete** need `AUDIO_MANAGE` and are visible to anyone holding it,
 *    on any asset they can already see.
 *  - **Approve / reject** need `AUDIO_APPROVE` — a genuinely different key. A
 *    `RESELLER_ADMIN` has `AUDIO_APPROVE` but not `AUDIO_MANAGE`, so they see
 *    Approve/Reject and not Edit/Delete. Both halves of that split are tested.
 *
 * ## Search and status
 *
 * Both are real server-side parameters. `search` is a contains over `name` and
 * `fileName` (`AudioAssetSpecifications.search`) — the placeholder says exactly
 * that, where the pre-F3 one promised nothing useful. `status` is an
 * `AudioAssetStatus` name; an unrecognised value is a 400, so the select only
 * ever offers the three real ones.
 *
 * The status filter lives in component state rather than the URL because
 * `useUrlListState`'s `status` slot is hard-coded to the users domain's
 * `ACTIVE`/`SUSPENDED` lifecycle. Adding a second status concept to that shared
 * hook would be an F1/F2 cross-cutting change for one screen, so F3 keeps the
 * filter local. Every status change resets to page 0 — the pre-F3 code left the
 * page untouched, so applying a filter while on page 5 produced an empty list
 * and a "no matches" message that looked like a bug.
 */
export function AudioAssetsView() {
  const { state, patch } = useUrlListState(LIST_CONFIG);
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);
  const [status, setStatus] = useState<AudioAssetStatus | "">("");
  const [uploadOpen, setUploadOpen] = useState(false);

  const { user } = useCan();
  const canManage = canPerformAudioAction(user, "write");
  const canApprove = canPerformAudioAction(user, "approve");
  const canUpload = canCreateAudioAsset(user);

  const apiParams: AudioAssetListParams = {
    page: state.page,
    size: state.size,
    sortField: state.sort.field as AudioAssetSortField,
    sortDirection: state.sort.direction,
    status: status || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: audioAssetsKeys.list(apiParams),
    queryFn: () => getAudioAssets(apiParams),
    placeholderData: (previous) => previous,
  });

  const filtersActive = state.q !== "" || status !== "";
  const clearFilters = useCallback(() => {
    setStatus("");
    patch({ q: "" }, true);
  }, [patch]);

  const onStatusChange = useCallback(
    (next: string) => {
      setStatus(next === ALL_STATUSES ? "" : (next as AudioAssetStatus));
      // Filter changes alter the dataset, so page 5 of the old result set is
      // meaningless — return to the first page.
      patch({ page: 0 }, false);
    },
    [patch],
  );

  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="Audio Assets"
        description="Recordings your organization can use in campaigns. Only approved recordings can be used."
      >
        {canUpload ? (
          <Button onClick={() => setUploadOpen(true)}>
            <PlusIcon aria-hidden="true" />
            Upload recording
          </Button>
        ) : null}
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Audio Assets</CardTitle>
          <CardDescription>
            Uploads start as pending approval. An approved recording becomes
            available to campaigns in your organization.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative flex-1 sm:max-w-xs">
              <Label htmlFor="audio-assets-search" className="sr-only">
                Search recordings by name or file name
              </Label>
              <Input
                id="audio-assets-search"
                type="search"
                value={state.q}
                onChange={(event) => patch({ q: event.target.value }, true)}
                placeholder="Search name or file…"
                aria-label="Search recordings by name or file name"
                className="pr-8"
              />
              {state.q ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Clear search"
                  className="absolute right-1 top-1/2 -translate-y-1/2"
                  onClick={() => patch({ q: "" }, true)}
                >
                  <XIcon aria-hidden="true" />
                </Button>
              ) : null}
            </div>

            <div className="flex items-center gap-2">
              <Label
                htmlFor="audio-assets-status"
                className="whitespace-nowrap text-sm text-muted-foreground"
              >
                Status
              </Label>
              <Select value={status || ALL_STATUSES} onValueChange={onStatusChange}>
                <SelectTrigger id="audio-assets-status" className="w-48">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_STATUSES}>All statuses</SelectItem>
                  {AUDIO_ASSET_STATUSES.map((value) => (
                    <SelectItem key={value} value={value}>
                      {STATUS_LABELS[value]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {filtersActive ? (
              <Button variant="ghost" onClick={clearFilters}>
                Clear filters
              </Button>
            ) : null}
          </div>

          {listQuery.isError ? (
            <QueryErrorState
              error={listQuery.error}
              entityLabel="audio assets"
              onRetry={() => void listQuery.refetch()}
            />
          ) : null}

          {listQuery.isPending ? (
            <TableSkeleton columns={8} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <EmptyState
              icon={filtersActive ? SearchXIcon : MusicIcon}
              title={
                filtersActive
                  ? "No recordings match your filters"
                  : "No recordings yet"
              }
              description={
                filtersActive
                  ? "Search matches the recording name and its file name. Try a different term, or clear the status filter."
                  : canUpload
                    ? "Upload a WAV or MP3 recording to get started. It will need approval before campaigns can use it."
                    : "Recordings appear here once they are uploaded to your organization."
              }
              action={
                filtersActive ? (
                  <Button variant="outline" size="sm" onClick={clearFilters}>
                    Clear filters
                  </Button>
                ) : undefined
              }
            />
          ) : listQuery.data ? (
            <>
              <div className="overflow-x-auto">
                <div
                  data-pending={listQuery.isFetching || undefined}
                  className="transition-opacity data-[pending=true]:opacity-60"
                >
                  <AudioAssetTable
                    assets={listQuery.data.items}
                    sort={state.sort as AudioSort}
                    onSortChange={(sort) => patch({ sort }, false)}
                    canManage={canManage}
                    canApprove={canApprove}
                  />
                </div>
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="recordings"
                  onPageSizeChange={(size) => patch({ size }, true)}
                  onPageChange={(page) => patch({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      {canUpload ? (
        <UploadAudioAssetDialog
          open={uploadOpen}
          onOpenChange={setUploadOpen}
        />
      ) : null}
    </div>
  );
}
