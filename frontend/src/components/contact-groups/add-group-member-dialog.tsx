"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { QueryErrorState } from "@/components/common/query-state";
import { toApiError } from "@/lib/api/error";
import type {
  ContactGroupMemberResponse,
  ContactGroupResponse,
} from "@/lib/api/contracts";
import {
  contactGroupsKeys,
  getContactGroups,
} from "@/lib/api/contact-groups";
import {
  addContactGroupMember,
  addContactGroupMembersBatch,
  contactGroupMembersKeys,
  getContactGroupMembers,
} from "@/lib/api/contact-group-members";
import { contactsKeys } from "@/lib/api/contacts";

/** VERIFIED: `BatchMemberRequest` is `@Size(max = 500)`. */
const MAX_BATCH_ITEMS = 500;
/** Picker page size. The server clamps to 1..100; this is the max. */
const PICKER_PAGE_SIZE = 100;

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const NO_SOURCE = "__none__";

/**
 * Add existing contacts to a group.
 *
 * ## Why this dialog is shaped the way it is
 *
 * `POST /api/v1/contact-groups/{id}/members` takes exactly one thing:
 * `AddMemberRequest { contactId }`. VERIFIED, it does **not** take a phone
 * number, and it does **not** create contacts — `addMember` looks the contact up
 * with `findByIdAndTenantIdAndDeletedAtIsNull` and throws a 404 if it is not a
 * live identity of the group's tenant. To create a new identity you use
 * `POST /{id}/contacts` (find-or-create) or the CSV import, not this endpoint.
 *
 * That creates a real constraint, and it is a **backend limitation, not a
 * frontend choice**: there is no `GET /api/v1/contacts` and no tenant-wide
 * contact search, so a user cannot be offered "search all contacts in my
 * organization". The only places a contact id is discoverable are group rosters
 * the caller is already allowed to read. So this dialog offers the two paths
 * that actually work:
 *
 *  1. **Pick from another group** you can see — the normal route.
 *  2. **Paste a contact id** — for a contact that exists in the tenant but sits
 *     only in a group you cannot read.
 *
 * A tenant/tenantId parameter is deliberately absent: the group determines the
 * tenant server-side.
 *
 * ## Idempotency is reported, not hidden
 *
 * Add is idempotent and the STATUS carries the distinction: 201 when the
 * membership was created, 200 when the contact was already a member
 * (`AddMemberOutcome.created`). The batch endpoint returns one
 * `BatchMemberResult` per distinct requested id, in request order, and never
 * fail-fasts. Both are surfaced, including `EXISTS` (already a member) and
 * `NOT_FOUND_CONTACT` (not a live contact of this tenant — deliberately
 * indistinguishable from a wrong id).
 */
export function AddGroupMemberDialog({
  group,
  open,
  onOpenChange,
}: {
  group: ContactGroupResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const [sourceGroupId, setSourceGroupId] = useState<string>(NO_SOURCE);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [pastedId, setPastedId] = useState("");
  const [error, setError] = useState<string | null>(null);
  /** Per-item outcomes from the last batch add, so nothing fails silently. */
  const [outcomes, setOutcomes] = useState<
    { contactId: string; status: string; errorDetail: string | null }[] | null
  >(null);

  // The source-group list is a read of the caller's own scope: no tenant param,
  // no arbitrary scope. It is the same call the groups page makes.
  const sourceGroupsQuery = useQuery({
    queryKey: contactGroupsKeys.list({
      page: 0,
      size: PICKER_PAGE_SIZE,
      sortField: "name",
      sortDirection: "asc",
    }),
    queryFn: () =>
      getContactGroups({
        page: 0,
        size: PICKER_PAGE_SIZE,
        sortField: "name",
        sortDirection: "asc",
      }),
    enabled: open,
  });

  const candidateGroups: ContactGroupResponse[] = useMemo(
    () =>
      (sourceGroupsQuery.data?.items ?? []).filter(
        (candidate) => candidate.id !== group.id,
      ),
    [sourceGroupsQuery.data, group.id],
  );

  // The candidate roster. Only the FIRST page is offered: a picker that silently
  // cannot reach contacts on page 3 would be a worse lie than a stated limit.
  const candidatesQuery = useQuery({
    queryKey: contactGroupMembersKeys.list({
      groupId: sourceGroupId,
      page: 0,
      size: PICKER_PAGE_SIZE,
      sortField: "firstName",
      sortDirection: "asc",
    }),
    queryFn: () =>
      getContactGroupMembers({
        groupId: sourceGroupId,
        page: 0,
        size: PICKER_PAGE_SIZE,
        sortField: "firstName",
        sortDirection: "asc",
      }),
    enabled: open && sourceGroupId !== NO_SOURCE,
  });

  const trimmedPastedId = pastedId.trim().toLowerCase();
  const pastedIdValid = UUID_REGEX.test(trimmedPastedId);

  const mutation = useMutation({
    mutationFn: async () => {
      // The pasted id is always sent on its own: mixing a hand-typed id into a
      // batch would let a typo fail a whole batch of good picks.
      if (pastedIdValid) {
        const outcome = await addContactGroupMember(group.id, {
          contactId: trimmedPastedId,
        });
        return { created: outcome.created, results: [] };
      }
      const ids = [...selected];
      if (ids.length === 1) {
        const outcome = await addContactGroupMember(group.id, { contactId: ids[0] });
        return { created: outcome.created, results: [] };
      }
      const batch = await addContactGroupMembersBatch(group.id, { contactIds: ids });
      return { created: false, results: batch.results };
    },
    onSuccess: async ({ created, results }) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: contactGroupMembersKeys.list({
            groupId: group.id,
            page: 0,
            size: PICKER_PAGE_SIZE,
            sortField: "firstName",
            sortDirection: "asc",
          }),
        }),
        queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
        queryClient.invalidateQueries({
          queryKey: contactsKeys.forGroup(group.id),
        }),
        queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
      ]);

      if (results.length === 0) {
        toast.success(
          created ? "Contact added" : "Already a member",
          {
            description: created
              ? `${group.name} now includes this contact.`
              : "That contact was already in this group. Nothing changed.",
          },
        );
      } else {
        setOutcomes(results);
        const added = results.filter((r) => r.status === "CREATED").length;
        const already = results.filter((r) => r.status === "EXISTS").length;
        const failed = results.filter(
          (r) => r.status === "ERROR" || r.status === "NOT_FOUND_CONTACT",
        ).length;
        toast.success("Add finished", {
          description: `${added} added, ${already} already members${
            failed > 0 ? `, ${failed} could not be added` : ""
          }.`,
        });
      }

      setSelected(new Set());
      setPastedId("");
      setError(null);
      onOpenChange(false);
    },
    onError: (e: unknown) => {
      setError(toApiError(e).message);
    },
  });

  const candidates: ContactGroupMemberResponse[] =
    candidatesQuery.data?.items.filter((member) => member.contact) ?? [];

  /** How many membership adds the current selection will send. */
  const totalToAdd = pastedIdValid ? 1 : selected.size;

  /** F2 §38: a dismiss mid-request would leave a half-applied membership with
   * no feedback, so the dialog refuses to close while a request is in flight. */
  function handleOpenChange(next: boolean) {
    if (mutation.isPending) return;
    if (!next) {
      setSelected(new Set());
      setPastedId("");
      setError(null);
      setOutcomes(null);
    }
    onOpenChange(next);
  }

  function handleAdd() {
    if (totalToAdd === 0) {
      setError("Choose a contact to add, or paste a contact id.");
      return;
    }
    if (totalToAdd > MAX_BATCH_ITEMS) {
      setError(
        `Select at most ${MAX_BATCH_ITEMS} contacts at once — the batch endpoint is capped at ${MAX_BATCH_ITEMS}.`,
      );
      return;
    }
    setError(null);
    mutation.mutate();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex max-h-[85vh] max-w-2xl flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>Add existing contacts</DialogTitle>
          <DialogDescription>
            Add contacts that already exist in your organization to{" "}
            <strong>{group.name}</strong>. This never creates new contacts — use
            Add Contact or Import for that.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-6">
          {error ? (
            <p role="alert" className="rounded bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {error}
            </p>
          ) : null}

          {outcomes && outcomes.length > 0 ? (
            <ul className="space-y-1 rounded bg-muted/60 p-3 text-sm">
              {outcomes.map((result) => (
                <li key={result.contactId} className="flex flex-wrap gap-2">
                  <code className="font-mono text-xs">{result.contactId}</code>
                  <span
                    className={
                      result.status === "CREATED"
                        ? "text-muted-foreground"
                        : "text-destructive"
                    }
                  >
                    {result.status}
                    {result.errorDetail ? ` — ${result.errorDetail}` : ""}
                  </span>
                </li>
              ))}
            </ul>
          ) : null}

          <div className="space-y-2">
            <Label htmlFor="add-member-source">Pick contacts from</Label>
            <Select
              value={sourceGroupId}
              onValueChange={(value) => {
                setSourceGroupId(value);
                setSelected(new Set());
              }}
              disabled={mutation.isPending}
            >
              <SelectTrigger id="add-member-source">
                <SelectValue placeholder="Choose a group…" />
              </SelectTrigger>
              <SelectContent>
                {candidateGroups.map((candidate) => (
                  <SelectItem key={candidate.id} value={candidate.id}>
                    {candidate.name} ({candidate.memberCount})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {sourceGroupsQuery.isPending ? (
              <p className="text-sm text-muted-foreground">Loading groups…</p>
            ) : null}
            {sourceGroupsQuery.isError ? (
              <QueryErrorState
                error={sourceGroupsQuery.error}
                entityLabel="your contact groups"
                onRetry={() => void sourceGroupsQuery.refetch()}
              />
            ) : null}
            {candidateGroups.length === 0 && !sourceGroupsQuery.isPending && !sourceGroupsQuery.isError ? (
              <p className="text-sm text-muted-foreground">
                You have no other group to copy from. Add contacts by pasting a
                contact id below, or import a file.
              </p>
            ) : null}
          </div>

          {sourceGroupId !== NO_SOURCE ? (
            candidatesQuery.isPending ? (
              <TableSkeleton columns={2} rows={4} />
            ) : candidatesQuery.isError ? (
              <QueryErrorState
                error={candidatesQuery.error}
                entityLabel="that group's members"
                onRetry={() => void candidatesQuery.refetch()}
              />
            ) : candidates.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                That group has no contacts to copy.
              </p>
            ) : (
              <ul className="max-h-64 space-y-1 overflow-y-auto rounded border p-2">
                {candidates.map((member) => {
                  const contact = member.contact;
                  if (!contact) return null;
                  const label =
                    contact.firstName ?? contact.lastName ?? contact.phoneNumber;
                  return (
                    <li key={member.contactId} className="flex items-center gap-3">
                      <Checkbox
                        id={`candidate-${member.contactId}`}
                        checked={selected.has(member.contactId)}
                        onCheckedChange={(value) => {
                          const next = new Set(selected);
                          if (value === true) next.add(member.contactId);
                          else next.delete(member.contactId);
                          setSelected(next);
                        }}
                      />
                      <Label
                        htmlFor={`candidate-${member.contactId}`}
                        className="flex-1 cursor-pointer font-normal"
                      >
                        {label}
                        {contact.lastName && contact.firstName
                          ? ` ${contact.lastName}`
                          : ""}{" "}
                        <span className="text-muted-foreground">
                          · {contact.phoneNumber}
                        </span>
                      </Label>
                    </li>
                  );
                })}
              </ul>
            )
          ) : null}

          {candidatesQuery.data &&
          candidatesQuery.data.pagination.totalElements > PICKER_PAGE_SIZE ? (
            <p className="text-xs text-muted-foreground">
              Showing the first {PICKER_PAGE_SIZE} of{" "}
              {candidatesQuery.data.pagination.totalElements} contacts. Narrow the
              source group, or add a contact by id below.
            </p>
          ) : null}

          <div className="space-y-2 border-t pt-4">
            <Label htmlFor="add-member-paste">Or paste a contact id</Label>
            <Input
              id="add-member-paste"
              value={pastedId}
              onChange={(event) => setPastedId(event.target.value)}
              placeholder="00000000-0000-0000-0000-000000000000"
              disabled={mutation.isPending}
              aria-describedby="add-member-paste-help"
            />
            <p id="add-member-paste-help" className="text-xs text-muted-foreground">
              Useful for a contact that exists in your organization but sits only
              in a group you cannot open. An id that is not a live contact of this
              tenant is reported as not found — the backend deliberately does not
              distinguish it from a wrong id.
            </p>
          </div>
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="button"
            onClick={handleAdd}
            disabled={mutation.isPending || totalToAdd === 0}
          >
            {mutation.isPending ? (
              <>
                <Spinner className="size-4" />
                Adding…
              </>
            ) : totalToAdd > 1 ? (
              `Add ${totalToAdd} contacts`
            ) : (
              "Add contact"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
