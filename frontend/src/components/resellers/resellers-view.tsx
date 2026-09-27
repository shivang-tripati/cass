"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangleIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchXIcon,
  StoreIcon,
} from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { StatusSearchToolbar } from "@/components/common/status-search-toolbar";
import { TableSkeleton } from "@/components/common/table-skeleton";
import {
  TablePagination,
} from "@/components/common/table-pagination";
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
import type { ResellerResponse } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  RESELLER_SORTABLE_FIELDS,
  getResellers,
  resellersKeys,
  type ResellerListParams,
  type ResellerSortField,
} from "@/lib/api/resellers";
import { CreateResellerDialog } from "@/components/resellers/create-reseller-dialog";
import { EditResellerDialog } from "@/components/resellers/edit-reseller-dialog";
import {
  ResellerTable,
} from "@/components/resellers/reseller-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: RESELLER_SORTABLE_FIELDS,
  // Backend default verified in ResellerService.buildPageable.
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/resellers",
};

export function ResellersView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  const apiParams: ResellerListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as ResellerSortField,
    sortDirection: state.sort.direction,
    status: state.status || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: resellersKeys.list(apiParams),
    queryFn: () => getResellers(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingReseller, setEditingReseller] =
    useState<ResellerResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const filtersActive = state.q !== "" || state.status !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "", status: "" }, true);
  }, [patchUrl]);

  const apiError = listQuery.error ? toApiError(listQuery.error) : null;
  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader
        title="Resellers"
        description="Administer reseller organizations on the platform."
      >
        <Button onClick={() => setCreateOpen(true)}>
          <PlusIcon aria-hidden="true" />
          Create reseller
        </Button>
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Resellers</CardTitle>
          <CardDescription>
            Organizations scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <StatusSearchToolbar
            idPrefix="resellers"
            search={state.q}
            status={state.status}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            onStatusChange={(status) => patchUrl({ status }, true)}
            searchPlaceholder="Search name, slug or display name…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view resellers."
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
            <TableSkeleton columns={6} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
              {filtersActive ? (
                <SearchXIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              ) : (
                <StoreIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              )}
              <p className="text-sm font-medium">
                {filtersActive
                  ? "No resellers match your filters"
                  : "No resellers yet"}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? "Try adjusting or clearing the search and status filters."
                  : "Resellers will appear here once they are provisioned on the platform."}
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
                <ResellerTable
                  resellers={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingReseller}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="resellers"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      <CreateResellerDialog open={createOpen} onOpenChange={setCreateOpen} />

      {editingReseller ? (
        <EditResellerDialog
          key={editingReseller.id}
          reseller={editingReseller}
          onOpenChange={(open) => {
            if (!open) setEditingReseller(null);
          }}
        />
      ) : null}
    </div>
  );
}
