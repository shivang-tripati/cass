"use client";

import { useCallback, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon, SearchIcon, SearchXIcon, Trash2Icon, UsersIcon, XIcon } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import { EmptyState, QueryErrorState } from "@/components/common/query-state";
import { Spinner } from "@/components/ui/spinner";
import {
  ContactGroupMembersTable,
  type MembersSort,
} from "@/components/contact-groups/contact-group-members-table";
import { AddGroupMemberDialog } from "@/components/contact-groups/add-group-member-dialog";
import { RemoveFromGroupDialog } from "@/components/contacts/remove-from-group-dialog";
import { useCan } from "@/lib/auth/use-can";
import { canPerformContactAction } from "@/lib/auth/contact-gates";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";
import { contactGroupsKeys } from "@/lib/api/contact-groups";
import {
  CONTACT_GROUP_MEMBER_SORTABLE_FIELDS,
  contactGroupMembersKeys,
  getContactGroupMembers,
  removeContactGroupMember,
  removeContactGroupMembersBatch,
  type ContactGroupMemberListParams,
  type ContactGroupMemberSortField,
} from "@/lib/api/contact-group-members";
import { contactsKeys } from "@/lib/api/contacts";
import { toApiError } from "@/lib/api/error";
import type {
  ContactGroupMemberResponse,
  ContactGroupResponse,
  ContactResponse,
} from "@/lib/api/contracts";

const SEARCH_DEBOUNCE_MS = 250;

/** VERIFIED: `ContactGroupMemberService.MEMBER_SORTABLE_FIELDS` and the default
 * `Sort.by(ASC, "createdAt")` — ASC, unlike the other two contact lists. */
const LIST_CONFIG = {
  sortableFields: CONTACT_GROUP_MEMBER_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "asc" as const },
  pageSizes: [20, 10, 50],
  basePathPrefix: "/contact-groups/",
  basePathSuffix: "",
};

/** Adapts a roster row into the `ContactResponse` the remove dialog takes.
 *
 * VERIFIED: `ContactGroupMemberResponse.contactId` and the embedded
 * `contact.id` are the same identity (the roster is a join of the membership to
 * its live contact), but the MEMBERSHIP's own `contactId` is the one that
 * belongs in the remove URL, so it is used explicitly rather than trusting the
 * embedded copy to match.
 */
function asContact(member: ContactGroupMemberResponse): ContactResponse | null {
  const contact = member.contact;
  if (!contact) return null;
  return { ...contact, id: member.contactId };
}

/**
 * The member roster for one group, backed by `/api/v1/contact-groups/{id}/members`.
 *
 * F2 — this is the membership surface F0 found entirely unused. It is
 * deliberately NOT a second copy of the contacts list:
 *
 *   `/contacts` answers "which contact identities are in this group, and let me
 *   create/edit/delete them", and it is the only place a NEW contact can be made.
 *   `/members` answers "who is in this group, since when, and let me add or drop
 *   the relationship", without touching any identity.
 *
 * Both show overlapping people, and that is not duplication — they are different
 * resources with different write semantics, and conflating them is how a
 * "remove from group" turns into "delete this contact everywhere".
 *
 * ## Single and batch removal are the same operation, batched
 *
 * VERIFIED: `removeMembers` never fail-fasts. Every distinct requested id gets
 * its own `BatchMemberResult`, duplicates inside one request collapse, and a
 * membership that is already gone reports `NOT_FOUND` — which is a successful
 * no-op, not a failure. So the batch path is used for BOTH one and many
 * selections: one code path, and a single-selection remove is exactly as
 * reliable as a fifty-selection remove.
 *
 * `DELETE /{id}/members/batch` is a **DELETE with a request body** and returns
 * **200** with per-item outcomes, not 204.
 *
 * No optimistic updates: a membership is a relationship whose exact outcome
 * depends on a unique constraint, so the server's per-item answer is the only
 * one worth showing (F2 §37).
 */
export function ContactGroupMembersPanel({
  group,
}: {
  group: ContactGroupResponse;
}) {
  const groupId = group.id;
  const queryClient = useQueryClient();
  const { user } = useCan();
  // F2: resolved through the domain gate table so CONTACT_IMPORT /
  // CONTACT_EXPORT are never substituted for the keys the service
  // actually enforces.
  const canManage = canPerformContactAction(user, "write");

  const listConfig = useMemo(
    () => ({
      ...LIST_CONFIG,
      basePath: `${LIST_CONFIG.basePathPrefix}${groupId}${LIST_CONFIG.basePathSuffix}`,
    }),
    [groupId],
  );
  const { state, patch: patchUrl } = useUrlListState(listConfig);
  const debouncedSearch = useDebouncedValue(state.q, SEARCH_DEBOUNCE_MS);

  const apiParams: ContactGroupMemberListParams = {
    groupId,
    page: state.page,
    size: state.size,
    sortField: state.sort.field as ContactGroupMemberSortField,
    sortDirection: state.sort.direction,
    search: debouncedSearch || undefined,
  };

  const listQuery = useQuery({
    queryKey: contactGroupMembersKeys.list(apiParams),
    queryFn: () => getContactGroupMembers(apiParams),
    placeholderData: (previous) => previous,
  });

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [removingMember, setRemovingMember] =
    useState<ContactGroupMemberResponse | null>(null);
  const [addOpen, setAddOpen] = useState(false);

  const filtersActive = state.q !== "";
  const clearFilters = useCallback(() => {
    patchUrl({ q: "" }, true);
  }, [patchUrl]);

  /** Everything a membership change can move: the roster, the contacts list,
   * and the group's server-computed `memberCount`. */
  const invalidateMembership = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
      queryClient.invalidateQueries({ queryKey: contactsKeys.forGroup(groupId) }),
      queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
    ]);
  }, [queryClient, groupId]);

  const batchRemove = useMutation({
    mutationFn: (contactIds: string[]) =>
      contactIds.length === 1
        ? removeContactGroupMember(groupId, contactIds[0]).then(() => null)
        : removeContactGroupMembersBatch(groupId, { contactIds }),
    onSuccess: async (result) => {
      await invalidateMembership();
      setSelected(new Set());
      if (result) {
        // VERIFIED: `removeMembers` never fails a whole batch. `NOT_FOUND` means
        // the row was already gone, which is a success, not an error.
        const removed = result.results.filter(
          (item) => item.status !== "ERROR",
        ).length;
        const failed = result.results.filter(
          (item) => item.status === "ERROR",
        ).length;
        toast.success(
          failed > 0 ? "Some members were not removed" : "Members removed",
          {
            description:
              failed > 0
                ? `${removed} removed, ${failed} could not be removed.`
                : `${removed} contact${removed === 1 ? "" : "s"} no longer in this group. The contacts themselves were kept.`,
          },
        );
      } else {
        toast.success("Removed from group", {
          description:
            "The contact is no longer in this group. The contact itself was kept.",
        });
      }
    },
    onError: (e: unknown) => {
      toast.error(toApiError(e).message);
    },
  });

  const pagination = listQuery.data?.pagination;
  /** Null when the roster row somehow has no embedded contact; the remove
   * dialog is not rendered at all rather than with a fabricated contact. */
  const removingContact = removingMember ? asContact(removingMember) : null;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center justify-between gap-2">
          Members
          {canManage ? (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setAddOpen(true)}
                disabled={batchRemove.isPending}
              >
                <PlusIcon aria-hidden="true" />
                Add existing contact
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={selected.size === 0 || batchRemove.isPending}
                onClick={() => batchRemove.mutate([...selected])}
              >
                {batchRemove.isPending ? (
                  <Spinner className="size-4" />
                ) : (
                  <Trash2Icon aria-hidden="true" />
                )}
                {selected.size > 0
                  ? `Remove ${selected.size} selected`
                  : "Remove selected"}
              </Button>
            </div>
          ) : null}
        </CardTitle>
        <CardDescription>
          Each row is this group&apos;s membership of a contact that already
          exists in your organization. Removing a membership keeps the contact.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative flex-1 sm:max-w-xs">
            <SearchIcon
              aria-hidden="true"
              className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              id="members-search"
              type="search"
              value={state.q}
              onChange={(event) => patchUrl({ q: event.target.value }, true)}
              placeholder="Search name or phone…"
              aria-label="Search members by name or phone number"
              className="pl-8 pr-8"
            />
            {state.q ? (
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label="Clear search"
                className="absolute right-1 top-1/2 -translate-y-1/2"
                onClick={clearFilters}
              >
                <XIcon aria-hidden="true" />
              </Button>
            ) : null}
          </div>
        </div>

        {listQuery.isError ? (
          <QueryErrorState
            error={listQuery.error}
            entityLabel="this group's members"
            onRetry={() => void listQuery.refetch()}
          />
        ) : null}

        {listQuery.isPending ? (
          <TableSkeleton columns={5} rows={Math.min(state.size, 8)} />
        ) : listQuery.data && listQuery.data.items.length === 0 ? (
          <EmptyState
            icon={filtersActive ? SearchXIcon : UsersIcon}
            title={
              filtersActive
                ? "No members match your search"
                : "This group has no members"
            }
            description={
              filtersActive
                ? "Search matches first name, last name and phone number."
                : canManage
                  ? "Add contacts one at a time from the Contacts tab, import a file, or add contacts that already exist in another group."
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
                <ContactGroupMembersTable
                  members={listQuery.data.items}
                  groupId={groupId}
                  sort={state.sort as MembersSort}
                  onSortChange={(sort) => patchUrl({ sort }, false)}
                  selected={selected}
                  onSelectedChange={setSelected}
                  canManage={canManage}
                  onRemove={setRemovingMember}
                />
              </div>
            </div>
            {pagination ? (
              <TablePagination
                pagination={pagination}
                pageSize={state.size}
                entityLabel="members"
                onPageSizeChange={(size) => patchUrl({ size }, true)}
                onPageChange={(page) => patchUrl({ page }, false)}
              />
            ) : null}
          </>
        ) : null}
      </CardContent>

      {addOpen ? (
        <AddGroupMemberDialog
          group={group}
          open={addOpen}
          onOpenChange={(open) => {
            if (!open) setAddOpen(false);
          }}
        />
      ) : null}

      {removingContact && removingMember ? (
        <RemoveFromGroupDialog
          key={`remove-${removingMember.contactId}`}
          groupId={groupId}
          groupName={group.name}
          contact={removingContact}
          open={true}
          onOpenChange={(open) => {
            if (!open) setRemovingMember(null);
          }}
        />
      ) : null}
    </Card>
  );
}
