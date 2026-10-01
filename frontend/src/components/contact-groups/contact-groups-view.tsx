"use client";

import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { PlusIcon, SearchXIcon, UsersIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { ContactGroupFilterToolbar } from "@/components/contact-groups/contact-group-filter-toolbar";
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
import type { ContactGroupResponse } from "@/lib/api/contracts";
import {
  CONTACT_GROUP_SORTABLE_FIELDS,
  getContactGroups,
  contactGroupsKeys,
  type ContactGroupListParams,
  type ContactGroupSortField,
} from "@/lib/api/contact-groups";
import { CreateContactGroupDialog } from "@/components/contact-groups/create-contact-group-dialog";
import { EditContactGroupDialog } from "@/components/contact-groups/edit-contact-group-dialog";
import { DeleteContactGroupDialog } from "@/components/contact-groups/delete-contact-group-dialog";
import { ImportContactsDialog } from "@/components/contacts/import-contacts-dialog";
import { ExportContactsDialog } from "@/components/contacts/export-contacts-dialog";
import { ContactGroupTable } from "@/components/contact-groups/contact-group-table";
import { useCan } from "@/lib/auth/use-can";
import {
  canCreateContactGroup,
  canPerformContactAction,
} from "@/lib/auth/contact-gates";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: CONTACT_GROUP_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50],
  basePath: "/contact-groups",
};

/**
 * Contact group list.
 *
 * F2 changes, each traceable to a verified backend fact or a concrete defect:
 *
 *  - **Capability gating.** The list itself is `CONTACT_VIEW`; creating, editing,
 *    importing and deleting a group are all `CONTACT_MANAGE`
 *    (`ContactGroupService.createGroup` L106, `updateGroup` L156,
 *    `deleteGroup` L173). Previously every action button rendered for every
 *    user, so a read-only user was offered "Create group" and then received a
 *    403. Export is `CONTACT_VIEW` and therefore always available to anyone who
 *    can see the row.
 *  - **403 is a forbidden state, not an error string.** The hand-copied
 *    `<Alert>` block is replaced by the F1 shared `QueryErrorState`, so a
 *    permission failure is never mistaken for "no groups exist" and never
 *    triggers a sign-in redirect.
 *  - **Delete is a real dialog.** It replaces a bare `confirm()` whose text
 *    claimed *"The group must be empty (no contacts)"* — a requirement that does
 *    not exist, and the reason it was there was a stale 409 annotation in the
 *    controller. `DeleteContactGroupDialog` states the verified consequence.
 *  - **Export was a no-op.** `onExport={() => {}}` was passed to the table, so
 *    the Export button did nothing at all. It now opens the export dialog.
 *  - **The `memberCount` column** is shown because the backend computes it for
 *    the whole page in one grouped query; there is no client-side counting.
 */
export function ContactGroupsView() {
  const { state, patch: patchUrl } = useUrlListState(LIST_CONFIG);
  const { user } = useCan();
  // F2: resolved through the domain gate table so CONTACT_IMPORT /
  // CONTACT_EXPORT are never substituted for the keys the service
  // actually enforces.
  const canManage = canPerformContactAction(user, "write");
  // `POST /contact-groups` additionally requires a TENANT context server-side:
  // `createGroup` throws "A tenant must be specified for this operation." when
  // `Scope.tenantId` is null, and a RESELLER_ADMIN holds CONTACT_MANAGE anyway.
  // Editing and deleting are NOT restricted this way — they go through
  // `authorizedGroup`, which only needs the group to be visible.
  const canCreate = canCreateContactGroup(user);

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
  const [exportGroup, setExportGroup] = useState<ContactGroupResponse | null>(null);
  const [deletingGroup, setDeletingGroup] = useState<ContactGroupResponse | null>(null);

  const filtersActive = state.q !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
  }, [patchUrl]);

  const pagination = listQuery.data?.pagination;

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="Contact Groups"
        description="Audience containers for your contacts. A group becomes a campaign target through its members."
      >
        {canCreate && (
          <Button onClick={() => setCreateOpen(true)}>
            <PlusIcon aria-hidden="true" />
            Create group
          </Button>
        )}
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Contact Groups</CardTitle>
          <CardDescription>
            Groups scoped to your organization. Contacts belong to the
            organization, not to a group — the same contact can be in several.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <ContactGroupFilterToolbar
            idPrefix="contact-groups"
            search={state.q}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            searchPlaceholder="Search group name…"
          />

          {listQuery.isError ? (
            <QueryErrorState
              error={listQuery.error}
              entityLabel="contact groups"
              onRetry={() => void listQuery.refetch()}
            />
          ) : null}

          {listQuery.isPending ? (
            <TableSkeleton columns={6} rows={Math.min(state.size, 8)} />
          ) : listQuery.data && listQuery.data.items.length === 0 ? (
            <EmptyState
              icon={filtersActive ? SearchXIcon : UsersIcon}
              title={
                filtersActive
                  ? "No contact groups match your search"
                  : "No contact groups yet"
              }
              description={
                filtersActive
                  ? "Try a different name, or clear the search."
                  : canCreate
                    ? "Create a group, then add contacts to it individually or by importing a file."
                    : "Contact groups appear here once they exist in your organization."
              }
              action={
                filtersActive ? (
                  <Button variant="outline" size="sm" onClick={clearFilters}>
                    Clear search
                  </Button>
                ) : canCreate ? (
                  <Button size="sm" onClick={() => setCreateOpen(true)}>
                    <PlusIcon aria-hidden="true" />
                    Create group
                  </Button>
                ) : undefined
              }
            />
          ) : listQuery.data ? (
            <>
              {/* F2 §39: the table scrolls horizontally instead of letting the
                  page layout overflow on a narrow viewport. */}
              <div className="overflow-x-auto">
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
                    onExport={setExportGroup}
                    onDelete={setDeletingGroup}
                    canManage={canManage}
                  />
                </div>
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

      {canCreate && (
        <CreateContactGroupDialog open={createOpen} onOpenChange={setCreateOpen} />
      )}

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
          key={`import-${importGroup.id}`}
          group={importGroup}
          open={true}
          canImport={canManage}
          onOpenChange={(open) => {
            if (!open) setImportGroup(null);
          }}
        />
      ) : null}

      {exportGroup ? (
        <ExportContactsDialog
          key={`export-${exportGroup.id}`}
          group={exportGroup}
          open={true}
          onOpenChange={(open) => {
            if (!open) setExportGroup(null);
          }}
        />
      ) : null}

      {deletingGroup ? (
        <DeleteContactGroupDialog
          key={`delete-${deletingGroup.id}`}
          group={deletingGroup}
          open={true}
          onOpenChange={(open) => {
            if (!open) setDeletingGroup(null);
          }}
        />
      ) : null}
    </div>
  );
}
