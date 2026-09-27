"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  AlertTriangleIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchXIcon,
  UsersIcon,
} from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { ContactGroupFilterToolbar } from "@/components/contact-groups/contact-group-filter-toolbar";
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
import type { ContactGroupResponse } from "@/lib/api/contracts";
import { toApiError } from "@/lib/api/error";
import {
  CONTACT_GROUP_SORTABLE_FIELDS,
  getContactGroups,
  contactGroupsKeys,
  type ContactGroupListParams,
  type ContactGroupSortField,
} from "@/lib/api/contact-groups";
import { CreateContactGroupDialog } from "@/components/contact-groups/create-contact-group-dialog";
import { EditContactGroupDialog } from "@/components/contact-groups/edit-contact-group-dialog";
import { ImportContactsDialog } from "@/components/contact-groups/import-contacts-dialog";
import { ContactGroupTable } from "@/components/contact-groups/contact-group-table";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";
import { deleteContactGroup } from "@/lib/api/contact-groups";
import { toast } from "sonner";
import { useQueryClient } from "@tanstack/react-query";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: CONTACT_GROUP_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/contact-groups",
};

export function ContactGroupsView() {
  const queryClient = useQueryClient();
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);

  // Typing commits to the URL instantly; only fetching is debounced.
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  const apiParams: ContactGroupListParams = {
    page: state.page,
    size: state.size,
    // Whitelist-validated by the shared state parser.
    sortField: state.sort.field as ContactGroupSortField,
    sortDirection: state.sort.direction,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: contactGroupsKeys.list(apiParams),
    queryFn: () => getContactGroups(apiParams),
    placeholderData: (previous) => previous,
  });

  // Dialog state is intentionally local (not in the URL).
  const [editingGroup, setEditingGroup] = useState<ContactGroupResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [importGroup, setImportGroup] = useState<ContactGroupResponse | null>(null);

  const filtersActive = state.q !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
  }, [patchUrl]);

  const apiError = listQuery.error ? toApiError(listQuery.error) : null;
  const pagination = listQuery.data?.pagination;

  async function handleDelete(group: ContactGroupResponse) {
    if (!confirm(`Are you sure you want to delete "${group.name}"? This action cannot be undone. The group must be empty (no contacts).`)) {
      return;
    }
    try {
      await deleteContactGroup(group.id);
      toast.success("Contact group deleted", { description: `${group.name} was deleted.` });
      await queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all });
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.status === 409) {
        alert(apiError.message);
      } else {
        toast.error(apiError.message);
      }
    }
  }

  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader
        title="Contact Groups"
        description="Manage contact groups within your authorized organization scope."
      >
        <Button onClick={() => setCreateOpen(true)}>
          <PlusIcon aria-hidden="true" />
          Create group
        </Button>
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Contact Groups</CardTitle>
          <CardDescription>
            Groups scoped to your organizational boundary.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <ContactGroupFilterToolbar
            idPrefix="contact-groups"
            search={state.q}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            searchPlaceholder="Search name, description…"
          />

          {apiError ? (
            <Alert variant="destructive">
              <AlertTriangleIcon aria-hidden="true" />
              <AlertTitle>
                {apiError.status === 403
                  ? "You don't have permission to view contact groups."
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
                <UsersIcon
                  aria-hidden="true"
                  className="size-8 text-muted-foreground"
                />
              )}
              <p className="text-sm font-medium">
                {filtersActive
                  ? "No contact groups match your filters"
                  : "No contact groups yet"}
              </p>
              <p className="max-w-sm text-sm text-muted-foreground">
                {filtersActive
                  ? "Try adjusting or clearing the search filter."
                  : "Contact groups will appear here once they are created in your organization."}
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
                <ContactGroupTable
                  groups={listQuery.data.items}
                  sort={state.sort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  onEdit={setEditingGroup}
                  onImport={setImportGroup}
                  onExport={() => {}}
                  onDelete={handleDelete}
                />
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="contact groups"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      <CreateContactGroupDialog open={createOpen} onOpenChange={setCreateOpen} />

      {editingGroup ? (
        <EditContactGroupDialog
          key={editingGroup.id}
          group={editingGroup}
          onOpenChange={(open) => {
            if (!open) setEditingGroup(null);
          }}
        />
      ) : null}
      {importGroup ? (
        <ImportContactsDialog
          key={importGroup.id}
          group={importGroup}
          open={true}
          onOpenChange={(open) => {
            if (!open) setImportGroup(null);
          }}
        />
      ) : null}
    </div>
  );
}