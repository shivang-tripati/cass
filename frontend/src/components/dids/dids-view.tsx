"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangleIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchXIcon,
  PhoneIcon,
} from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { DidFilterToolbar } from "@/components/common/did-filter-toolbar";
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
import type { DidResponse, DidStatus } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  DID_SORTABLE_FIELDS,
  getDids,
  didsKeys,
  type DidListParams,
  type DidSortField,
} from "@/lib/api/dids";
import { CreateDidDialog } from "@/components/dids/create-did-dialog";
import { EditDidDialog } from "@/components/dids/edit-did-dialog";
import { DidTable } from "@/components/dids/did-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: DID_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/dids",
};

export function DidsView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);
  
  // Status filter is local (not in URL) because DIDs use DidStatus (ACTIVE/INACTIVE)
  // while the shared URL state expects LifecycleStatus (ACTIVE/SUSPENDED)
  const [didStatus, setDidStatus] = useState<DidStatus | "">("");

  const apiParams: DidListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as DidSortField,
    sortDirection: state.sort.direction,
    status: didStatus || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: didsKeys.list(apiParams),
    queryFn: () => getDids(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingDid, setEditingDid] = useState<DidResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const filtersActive = state.q !== "" || didStatus !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
    setDidStatus("");
  }, [patchUrl]);

  const apiError = listQuery.error ? toApiError(listQuery.error) : null;
  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="DIDs"
        description="Manage phone number (DID) inventory within your authorized organization scope."
      >
        <Button onClick={() => setCreateOpen(true)}>
          <PlusIcon aria-hidden="true" />
          Register DID
        </Button>
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>DID Inventory</CardTitle>
          <CardDescription>
            Managed phone numbers scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <DidFilterToolbar
            idPrefix="dids"
            search={state.q}
            status={didStatus}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            onStatusChange={(status) => setDidStatus(status)}
            searchPlaceholder="Search E.164, provider, circle…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view DIDs."
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
            <TableSkeleton columns={8} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
              {filtersActive ? (
                <SearchXIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              ) : (
                <PhoneIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              )}
              <p className="text-sm font-medium">
                {filtersActive
                  ? "No DIDs match your filters"
                  : "No DIDs yet"}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? "Try adjusting or clearing the search and status filters."
                  : "DIDs will appear here once they are registered in your organization."}
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
                <DidTable
                  dids={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingDid}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="DIDs"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      <CreateDidDialog open={createOpen} onOpenChange={setCreateOpen} />

      {editingDid ? (
        <EditDidDialog
          key={editingDid.id}
          did={editingDid}
          onOpenChange={(open) => {
            if (!open) setEditingDid(null);
          }}
        />
      ) : null}
    </div>
  );
}