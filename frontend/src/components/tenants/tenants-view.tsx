"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangleIcon,
  BuildingIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchXIcon,
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
import type { TenantResponse } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  TENANT_SORTABLE_FIELDS,
  getTenants,
  tenantsKeys,
  type TenantListParams,
  type TenantSortField,
} from "@/lib/api/tenants";
import { CreateTenantDialog } from "@/components/tenants/create-tenant-dialog";
import { EditTenantDialog } from "@/components/tenants/edit-tenant-dialog";
import { TenantTable } from "@/components/tenants/tenant-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: TENANT_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/tenants",
};

export function TenantsView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  const apiParams: TenantListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as TenantSortField,
    sortDirection: state.sort.direction,
    status: state.status || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: tenantsKeys.list(apiParams),
    queryFn: () => getTenants(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingTenant, setEditingTenant] = useState<TenantResponse | null>(null);
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
        title="Tenants"
        description="Provision and administer tenants within your authorized organization scope."
      >
        <Button onClick={() => setCreateOpen(true)}>
          <PlusIcon aria-hidden="true" />
          Create tenant
        </Button>
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Tenants</CardTitle>
          <CardDescription>
            Organizations scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <StatusSearchToolbar
            idPrefix="tenants"
            search={state.q}
            status={state.status}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            onStatusChange={(status) => patchUrl({ status }, true)}
            searchPlaceholder="Search name or slug…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view tenants."
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
            <TableSkeleton columns={5} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
              {filtersActive ? (
                <SearchXIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              ) : (
                <BuildingIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              )}
              <p className="text-sm font-medium">
                {filtersActive
                  ? "No tenants match your filters"
                  : "No tenants yet"}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? "Try adjusting or clearing the search and status filters."
                  : "Tenants will appear here once they are provisioned in your organization."}
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
                <TenantTable
                  tenants={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingTenant}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="tenants"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      <CreateTenantDialog open={createOpen} onOpenChange={setCreateOpen} />

      {editingTenant ? (
        <EditTenantDialog
          key={editingTenant.id}
          tenant={editingTenant}
          onOpenChange={(open) => {
            if (!open) setEditingTenant(null);
          }}
        />
      ) : null}
    </div>
  );
}
