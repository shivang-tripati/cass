"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertTriangleIcon, RefreshCwIcon, SearchXIcon, UsersIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
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
import type { UserResponse } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  USER_SORTABLE_FIELDS,
  getUsers,
  usersKeys,
  type UserListParams,
  type UserSortField,
} from "@/lib/api/users";
import { EditUserDialog } from "@/components/users/edit-user-dialog";
import {
  StatusSearchToolbar,
} from "@/components/common/status-search-toolbar";
import { TableSkeleton } from "@/components/common/table-skeleton";
import {
  TablePagination,
} from "@/components/common/table-pagination";
import { UserTable } from "@/components/users/user-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

/** View configuration for the shared URL list state. */
const LIST_CONFIG = {
  sortableFields: USER_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/users",
};

export function UsersView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  const apiParams: UserListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as UserSortField,
    sortDirection: state.sort.direction,
    status: state.status || undefined,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: usersKeys.list(apiParams),
    queryFn: () => getUsers(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingUser, setEditingUser] = useState<UserResponse | null>(null);

  const filtersActive = state.q !== "" || state.status !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "", status: "" }, true);
  }, [patchUrl]);

  const error = listQuery.error;
  const apiError = error ? toApiError(error) : null;
  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader
        title="Users"
        description="Manage users within your authorized organization scope."
      />

      <Card>
        <CardHeader>
          <CardTitle>Directory</CardTitle>
          <CardDescription>
            Accounts scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <StatusSearchToolbar
            idPrefix="users"
            search={state.q}
            status={state.status}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            onStatusChange={(status) => patchUrl({ status }, true)}
            searchPlaceholder="Search email or name…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view users."
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
            <UsersEmptyState
              filtered={filtersActive}
              onClearFilters={clearFilters}
            />
          ) : listQuery.data ? (
            <>
              <div
                data-pending={listQuery.isFetching || undefined}
                className="data-[pending=true]:opacity-60 transition-opacity"
              >
                <UserTable
                  users={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingUser}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="users"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      {editingUser ? (
        <EditUserDialog
          key={editingUser.id}
          user={editingUser}
          onOpenChange={(open) => {
            if (!open) setEditingUser(null);
          }}
        />
      ) : null}
    </div>
  );
}

function UsersEmptyState({
  filtered,
  onClearFilters,
}: {
  filtered: boolean;
  onClearFilters: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center">
      {filtered ? (
        <SearchXIcon aria-hidden="true" className="size-8 text-muted-foreground" />
      ) : (
        <UsersIcon aria-hidden="true" className="size-8 text-muted-foreground" />
      )}
      <p className="text-sm font-medium">
        {filtered ? "No users match your filters" : "No users yet"}
      </p>
      <p className="max-w-sm text-sm text-muted-foreground">
        {filtered
          ? "Try adjusting or clearing the search and status filters."
          : "Users will appear here once accounts are provisioned in your organization."}
      </p>
      {filtered ? (
        <Button variant="outline" size="sm" onClick={onClearFilters}>
          Clear filters
        </Button>
      ) : null}
    </div>
  );
}
