"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { MegaphoneIcon, PlusIcon, SearchXIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { CampaignFilterToolbar } from "@/components/campaigns/campaign-filter-toolbar";
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
import type {
  CampaignResponse,
  CampaignRunMode,
  CampaignStatus,
  CampaignType,
} from "@/lib/api/contracts";
import {
  CAMPAIGN_SORTABLE_FIELDS,
  getCampaigns,
  campaignsKeys,
  type CampaignListParams,
  type CampaignSortField,
} from "@/lib/api/campaigns";
import { CreateCampaignDialog } from "@/components/campaigns/create-campaign-dialog";
import { EditCampaignDialog } from "@/components/campaigns/edit-campaign-dialog";
import { CloneCampaignDialog } from "@/components/campaigns/clone-campaign-dialog";
import { ChangeStatusDialog } from "@/components/campaigns/change-status-dialog";
import { CampaignTable } from "@/components/campaigns/campaign-table";
import { canPerformCampaignAction, canCreateCampaign } from "@/lib/auth/campaign-gates";
import { useCan } from "@/lib/auth/use-can";
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

  /**
   * F4: the campaign-status, campaign-type and run-mode filters live in
   * component state rather than the URL, because `useUrlListState`'s `status`
   * slot is hard-coded to the users domain's `ACTIVE | SUSPENDED` and its
   * `patch` writes that slot back out. F3 recorded the same limitation for
   * audio and TTS.
   *
   * The change made here is the part that was a real bug: F1 reset to page 1
   * for the search box but NOT for these three, so changing a filter while
   * sitting on page 3 left the view on page 3 of a smaller result set — often
   * an empty one. `patchUrl(..., true)` now returns to the first page whenever
   * any filter changes.
   *
   * The shared hook is deliberately NOT redesigned: doing so is a cross-domain
   * change touching users, tenants, resellers, DIDs, contacts, contact groups,
   * audio and TTS, and it is not required to make Campaign correct.
   */
  const [campaignStatus, setCampaignStatus] = useState<CampaignStatus | "">("");
  const [campaignType, setCampaignType] = useState<CampaignType | "">("");
  const [runMode, setRunMode] = useState<CampaignRunMode | "">("");

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
  const [cloningCampaign, setCloningCampaign] = useState<CampaignResponse | null>(null);
  const [statusCampaign, setStatusCampaign] = useState<CampaignResponse | null>(null);
  const { user } = useCan();

  // VERIFIED `CampaignService.create` requires `CAMPAIGN_MANAGE` AND a resolvable
  // target tenant; see `campaign-gates.ts` for why the capability alone is not
  // sufficient.
  const mayCreate = canCreateCampaign(user);
  const mayWrite = canPerformCampaignAction(user, "write");
  const mayExecute = canPerformCampaignAction(user, "execute");

  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
    setCampaignStatus("");
    setCampaignType("");
    setRunMode("");
  }, [patchUrl]);

  const pagination = listQuery.data?.pagination;
  const filtersActive =
    state.q !== "" || campaignStatus !== "" || campaignType !== "" || runMode !== "";

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="Campaigns"
        description="Outbound campaigns within your authorized organization scope."
      >
        {mayCreate ? (
          <Button onClick={() => setCreateOpen(true)}>
            <PlusIcon aria-hidden="true" />
            Create campaign
          </Button>
        ) : null}
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Campaigns</CardTitle>
          <CardDescription>
            VERIFIED scoping: a tenant caller sees its own campaigns, a reseller
            caller sees its own hierarchy&apos;s ACTIVE tenants, and a platform
            caller sees every non-deleted campaign. A campaign outside that
            boundary is reported as not found rather than forbidden.
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
            onStatusChange={(status) => {
              setCampaignStatus(status);
              patchUrl({}, true);
            }}
            onCampaignTypeChange={(next) => {
              setCampaignType(next);
              patchUrl({}, true);
            }}
            onRunModeChange={(next) => {
              setRunMode(next);
              patchUrl({}, true);
            }}
            searchPlaceholder="Search name, description…"
          />

          {listQuery.isError ? (
            <QueryErrorState
              error={listQuery.error}
              entityLabel="campaigns"
              onRetry={() => void listQuery.refetch()}
            />
          ) : null}

          {listQuery.isPending ? (
            <TableSkeleton columns={7} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <EmptyState
              icon={filtersActive ? SearchXIcon : MegaphoneIcon}
              title={
                filtersActive
                  ? "No campaigns match your filters"
                  : "No campaigns yet"
              }
              description={
                filtersActive
                  ? "Try adjusting or clearing the search and filters."
                  : "A campaign is created as a draft, configured, and then scheduled."
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
              <div
                data-pending={listQuery.isFetching || undefined}
                className="transition-opacity data-[pending=true]:opacity-60"
              >
                <CampaignTable
                  campaigns={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingCampaign}
                  onClone={setCloningCampaign}
                  onStatusChange={setStatusCampaign}
                  canManage={mayWrite}
                  canExecute={mayExecute}
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
          open
          onOpenChange={(open) => {
            if (!open) setEditingCampaign(null);
          }}
        />
      ) : null}

      {cloningCampaign ? (
        <CloneCampaignDialog
          key={cloningCampaign.id}
          campaign={cloningCampaign}
          open
          onOpenChange={(open) => {
            if (!open) setCloningCampaign(null);
          }}
        />
      ) : null}

      {statusCampaign ? (
        <ChangeStatusDialog
          key={statusCampaign.id}
          campaign={statusCampaign}
          open
          onOpenChange={(open) => {
            if (!open) setStatusCampaign(null);
          }}
        />
      ) : null}
    </div>
  );
}
