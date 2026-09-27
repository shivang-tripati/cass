"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangleIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchXIcon,
  MegaphoneIcon,
} from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { CampaignFilterToolbar } from "@/components/common/campaign-filter-toolbar";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { CampaignResponse } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  CAMPAIGN_SORTABLE_FIELDS,
  getCampaigns,
  campaignsKeys,
  type CampaignListParams,
  type CampaignSortField,
} from "@/lib/api/campaigns";
import { CreateCampaignDialog } from "@/components/campaigns/create-campaign-dialog";
import { EditCampaignDialog } from "@/components/campaigns/edit-campaign-dialog";
import { CampaignTable } from "@/components/campaigns/campaign-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: CAMPAIGN_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/campaigns",
};

export function CampaignsView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  // Additional filters are kept in local state (not in URL) because they use
  // Campaign-specific enums that differ from the shared LifecycleStatus.
  const [campaignStatus, setCampaignStatus] = useState<
    import("@/lib/api/contracts").CampaignStatus | ""
  >("");
  const [campaignType, setCampaignType] = useState<
    import("@/lib/api/contracts").CampaignType | ""
  >("");
  const [runMode, setRunMode] = useState<
    import("@/lib/api/contracts").CampaignRunMode | ""
  >("");

  const apiParams: CampaignListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as CampaignSortField,
    sortDirection: state.sort.direction,
    status: campaignStatus || undefined,
    campaignType: campaignType || undefined,
    runMode: runMode || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: campaignsKeys.list(apiParams),
    queryFn: () => getCampaigns(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingCampaign, setEditingCampaign] = useState<CampaignResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const filtersActive =
    state.q !== "" ||
    campaignStatus !== "" ||
    campaignType !== "" ||
    runMode !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
    setCampaignStatus("");
    setCampaignType("");
    setRunMode("");
  }, [patchUrl]);

  const apiError = listQuery.error ? toApiError(listQuery.error) : null;
  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="Campaigns"
        description="Manage outbound campaigns within your authorized organization scope."
      >
        <Button onClick={() => setCreateOpen(true)}>
          <PlusIcon aria-hidden="true" />
          Create Campaign
        </Button>
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Campaigns</CardTitle>
          <CardDescription>
            Outbound campaign configurations scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <CampaignFilterToolbar
            idPrefix="campaigns"
            search={state.q}
            status={campaignStatus}
            campaignType={campaignType}
            runMode={runMode}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            onStatusChange={(status) => setCampaignStatus(status)}
            onCampaignTypeChange={(campaignType) => setCampaignType(campaignType)}
            onRunModeChange={(runMode) => setRunMode(runMode)}
            searchPlaceholder="Search name, description…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view campaigns."
                  : apiError.message}
              </AlertTitle>
              <AlertDescription>
                {apiError.status === 403
                  ? "Ask a platform administrator for access."
                  : apiError.requestId
                    ? `Request ID: ${apiError.requestId}`
                    : null}
              </AlertDescription>
              <AlertAction>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void listQuery.refetch()}
                >
                  <RefreshCwIcon aria-hidden="true" />
                  Retry
                </Button>
              </AlertAction>
            </Alert>
          ) : null}

          {listQuery.isPending ? (
            <TableSkeleton columns={7} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
              {filtersActive ? (
                <SearchXIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              ) : (
                <MegaphoneIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              )}
              <p className="text-sm font-medium">
                {filtersActive
                  ? "No campaigns match your filters"
                  : "No campaigns yet"}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? "Try adjusting or clearing the search and status filters."
                  : "Campaigns will appear here once they are created in your organization."}
              </p>
              {filtersActive ? (
                <Button variant="outline" size="sm" onClick={clearFilters}>
                  Clear filters
                </Button>
              ) : null}
            </div>
          ) : listQuery.data ? (
            <>
              <div
                data-pending={listQuery.isFetching || undefined}
                className="transition-opacity data-[pending=true]:opacity-60"
              >
                <CampaignTable
                  campaigns={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingCampaign}
                  onClone={() => {
                    // TODO: Implement clone via API
                    window.location.reload();
                  }}
                  onStatusChange={() => {
                    // TODO: Implement status change dialog
                    window.location.reload();
                  }}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="campaigns"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      <CreateCampaignDialog open={createOpen} onOpenChange={setCreateOpen} />

      {editingCampaign ? (
        <EditCampaignDialog
          key={editingCampaign.id}
          campaign={editingCampaign}
          onOpenChange={(open) => {
            if (!open) setEditingCampaign(null);
          }}
        />
      ) : null}
    </div>
  );
}