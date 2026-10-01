"use client";

import { useCallback, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, SearchXIcon, UsersIcon } from "lucide-react";

import { PageHeader } from "@/components/layout/page-header";
import { ContactFilterToolbar } from "@/components/contacts/contact-filter-toolbar";
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
import type { ContactResponse } from "@/lib/api/contracts";
import {
  CONTACT_SORTABLE_FIELDS,
  getContacts,
  contactsKeys,
  type ContactListParams,
  type ContactSortField,
} from "@/lib/api/contacts";
import { contactGroupsKeys, getContactGroup } from "@/lib/api/contact-groups";
import { contactGroupMembersKeys } from "@/lib/api/contact-group-members";
import { CreateContactDialog } from "@/components/contacts/create-contact-dialog";
import { EditContactDialog } from "@/components/contacts/edit-contact-dialog";
import { DeleteContactDialog } from "@/components/contacts/delete-contact-dialog";
import { RemoveFromGroupDialog } from "@/components/contacts/remove-from-group-dialog";
import { ContactTable } from "@/components/contacts/contact-table";
import { useCan } from "@/lib/auth/use-can";
import { canPerformContactAction } from "@/lib/auth/contact-gates";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";

const SEARCH_DEBOUNCE_MS = 250;

const LIST_CONFIG = {
  sortableFields: CONTACT_SORTABLE_FIELDS,
  defaultSort: { field: "firstName", direction: "asc" as const },
  pageSizes: [20, 10, 50],
  /**
   * F2 FIX — this was the literal string `"/contact-groups/[contactGroupId]/contacts"`.
   * `useUrlListState` feeds `basePath` straight to `router.replace`, so every
   * search keystroke, page change, page-size change and sort click navigated to
   * a URL containing a literal `[contactGroupId]` segment — a 404. This is the
   * only nested list view in the app, which is why no other list was affected.
   * The group id comes from the route, so it is interpolated here; the config
   * object is memoised because the hook uses it as a `useMemo`/`useCallback`
   * dependency and a fresh object each render would discard both memos.
   */
  basePathPrefix: "/contact-groups/",
  basePathSuffix: "/contacts",
};

/**
 * Contacts within one group.
 *
 * F2 changes:
 *  - the group's real `memberCount` and name are shown, fetched once (the
 *    backend returns `memberCount` on `ContactGroupResponse`, so no client-side
 *    counting is needed and none is done);
 *  - every mutation invalidates `contactsKeys.forGroup(groupId)` rather than one
 *    reconstructed page/sort key;
 *  - writes are gated on `CONTACT_MANAGE`, the same capability the backend
 *    enforces;
 *  - the two deletions are separated: "Remove from group" (membership) and
 *    "Delete" (identity, across all groups);
 *  - the hand-copied 403 alert block is replaced by the F1 shared
 *    `QueryErrorState`, so 403 is a forbidden state and never a sign-in redirect.
 */
export function ContactsView({
  contactGroupId,
}: {
  contactGroupId: string;
}) {
  const queryClient = useQueryClient();
  const listConfig = useMemo(
    () => ({
      ...LIST_CONFIG,
      basePath: `${LIST_CONFIG.basePathPrefix}${contactGroupId}${LIST_CONFIG.basePathSuffix}`,
    }),
    [contactGroupId],
  );
  const { state, patch: patchUrl } = useUrlListState(listConfig);
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);
  const { user } = useCan();
  // F2: resolved through the domain gate table so CONTACT_IMPORT /
  // CONTACT_EXPORT are never substituted for the keys the service
  // actually enforces.
  const canManage = canPerformContactAction(user, "write");

  // The group is fetched for its name and its authoritative `memberCount`.
  // This is ONE extra request per page, not an N+1: the group id comes from the
  // route, so the query is shared and cached by TanStack Query.
  const groupQuery = useQuery({
    queryKey: contactGroupsKeys.detail(contactGroupId),
    queryFn: () => getContactGroup(contactGroupId),
  });

  const apiParams: ContactListParams = {
    contactGroupId,
    page: state.page,
    size: state.size,
    sortField: state.sort.field as ContactSortField,
    sortDirection: state.sort.direction,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: contactsKeys.list(apiParams),
    queryFn: () => getContacts(apiParams),
    placeholderData: (previous) => previous,
  });

  const [editingContact, setEditingContact] = useState<ContactResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [deletingContact, setDeletingContact] = useState<ContactResponse | null>(null);
  const [removingContact, setRemovingContact] = useState<ContactResponse | null>(null);

  const filtersActive = state.q !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
  }, [patchUrl]);

  const pagination = listQuery.data?.pagination;
  const groupName = groupQuery.data?.name;

  /** F2: one place that refreshes everything a contact mutation can change —
   * this group's contact list, any member roster, and the group's memberCount. */
  const invalidateContacts = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: contactsKeys.forGroup(contactGroupId),
      }),
      queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
      queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
    ]);
  }, [queryClient, contactGroupId]);

  return (
    <div className="mx-auto w-full max-w-7xl">
      <PageHeader
        title="Contacts"
        description={
          groupQuery.data
            ? `Members of ${groupQuery.data.name} — ${groupQuery.data.memberCount} in this group.`
            : "Members of this group."
        }
      >
        {canManage && (
          <Button onClick={() => setCreateOpen(true)}>
            <PlusIcon aria-hidden="true" />
            Add Contact
          </Button>
        )}
      </PageHeader>

      <Card>
        <CardHeader>
          <CardTitle>Contacts</CardTitle>
          <CardDescription>
            A contact belongs to your organization and may appear in more than one
            group. Deleting one removes it from every group.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <ContactFilterToolbar
            idPrefix="contacts"
            search={state.q}
            onSearchInput={(search) => patchUrl({ q: search }, true)}
            onSearchClear={() => patchUrl({ q: "" }, true)}
            searchPlaceholder="Search name or phone…"
          />

          {listQuery.isError ? (
            <QueryErrorState
              error={listQuery.error}
              entityLabel="contacts in this group"
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
                  ? "No contacts match your search"
                  : "This group has no contacts yet"
              }
              description={
                filtersActive
                  ? "Try a different name or phone number."
                  : canManage
                    ? "Add a contact one at a time, or import a CSV, XLSX or JSON file from the group page."
                    : "Contacts appear here once they are added to this group."
              }
              action={
                filtersActive ? (
                  <Button variant="outline" size="sm" onClick={clearFilters}>
                    Clear search
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
                  <ContactTable
                    contacts={listQuery.data.items}
                    contactGroupId={contactGroupId}
                    sort={state.sort}
                    onSortChange={(sort) => patchUrl({ sort }, false)}
                    onEdit={setEditingContact}
                    onDelete={setDeletingContact}
                    onRemoveFromGroup={setRemovingContact}
                    canManage={canManage}
                    groupName={groupName ?? "this group"}
                  />
                </div>
              </div>
              {pagination ? (
                <TablePagination
                  pagination={pagination}
                  pageSize={state.size}
                  entityLabel="contacts"
                  onPageSizeChange={(size) => patchUrl({ size }, true)}
                  onPageChange={(page) => patchUrl({ page }, false)}
                />
              ) : null}
            </>
          ) : null}
        </CardContent>
      </Card>

      {canManage && (
        <CreateContactDialog
          contactGroupId={contactGroupId}
          open={createOpen}
          onOpenChange={setCreateOpen}
          onCreated={invalidateContacts}
        />
      )}

      {editingContact ? (
        <EditContactDialog
          key={editingContact.id}
          contact={editingContact}
          contactGroupId={contactGroupId}
          onOpenChange={(open) => {
            if (!open) setEditingContact(null);
          }}
          onSaved={invalidateContacts}
        />
      ) : null}

      {deletingContact ? (
        <DeleteContactDialog
          key={`delete-${deletingContact.id}`}
          groupId={contactGroupId}
          groupName={groupName ?? "this group"}
          contact={deletingContact}
          open={true}
          onOpenChange={(open) => {
            if (!open) setDeletingContact(null);
          }}
        />
      ) : null}

      {removingContact ? (
        <RemoveFromGroupDialog
          key={`remove-${removingContact.id}`}
          groupId={contactGroupId}
          groupName={groupName ?? "this group"}
          contact={removingContact}
          open={true}
          onOpenChange={(open) => {
            if (!open) setRemovingContact(null);
          }}
        />
      ) : null}
    </div>
  );
}
