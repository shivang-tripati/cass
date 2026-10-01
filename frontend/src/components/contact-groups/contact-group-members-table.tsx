"use client";

import Link from "next/link";
import { useMemo } from "react";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, Link2OffIcon, PhoneIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { ContactGroupMemberResponse } from "@/lib/api/contracts";
import type { ContactGroupMemberSortField } from "@/lib/api/contact-group-members";
import { formatDateTime } from "@/lib/format";

export interface MembersSort {
  field: string;
  direction: "asc" | "desc";
}

interface ContactGroupMembersTableProps {
  members: ContactGroupMemberResponse[];
  groupId: string;
  sort: MembersSort;
  onSortChange: (sort: MembersSort) => void;
  /** Selected contact ids for the batch-remove action. */
  selected: ReadonlySet<string>;
  onSelectedChange: (selected: Set<string>) => void;
  /** `CONTACT_MANAGE`. Reads stay available; membership writes are gated. */
  canManage: boolean;
  onRemove: (member: ContactGroupMemberResponse) => void;
}

/** Roster label for a membership row, which embeds the live contact. */
function memberLabel(member: ContactGroupMemberResponse): string {
  return (
    member.contact?.firstName ??
    member.contact?.lastName ??
    member.contact?.phoneNumber ??
    member.contactId
  );
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

/**
 * The `/members` roster — one row per MEMBERSHIP, not per contact.
 *
 * F2 — F0 recorded this API as "entirely unused by the frontend"; F2 adopts it
 * for membership management. It is kept separate from the `/contacts` list
 * because the two answer different questions:
 *
 *   `/contacts`  → "which contact IDENTITIES are in this group, and let me
 *                   create / edit / delete them"
 *   `/members`   → "who is in this group, since when, and let me add or remove
 *                   the RELATIONSHIP"
 *
 * Two consequences visible in this table:
 *
 *  - the sortable column is **Joined** (`createdAt` of the membership row), which
 *    is not a contact field and does not exist on `ContactResponse`;
 *  - the sort default is **ASC** (`DEFAULT_MEMBER_SORT`), unlike the contacts
 *    list (`firstName,asc`) and the groups list (`createdAt,desc`).
 *
 * `member.contact` is typed nullable: the roster query joins the live contact, so
 * in practice it is always present, but a null is rendered honestly rather than
 * dereferenced.
 */
export function ContactGroupMembersTable({
  members,
  groupId,
  sort,
  onSortChange,
  selected,
  onSelectedChange,
  canManage,
  onRemove,
}: ContactGroupMembersTableProps) {
  const selectableIds = useMemo(
    () => members.map((member) => member.contactId),
    [members],
  );
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selected.has(id));
  const someSelected = selectableIds.some((id) => selected.has(id));

  function toggleAll(checked: boolean) {
    onSelectedChange(checked ? new Set(selectableIds) : new Set());
  }

  function toggleOne(contactId: string, checked: boolean) {
    const next = new Set(selected);
    if (checked) next.add(contactId);
    else next.delete(contactId);
    onSelectedChange(next);
  }

  function sortableHeader(
    field: ContactGroupMemberSortField,
    label: string,
  ): React.ReactNode {
    const isActive = sort.field === field;
    return (
      <Button
        variant="ghost"
        size="sm"
        className="-ml-2 h-7"
        data-active={isActive || undefined}
        onClick={() =>
          onSortChange({
            field,
            direction: isActive ? nextDirection(sort.direction) : "asc",
          })
        }
        aria-label={`Sort by ${label}`}
      >
        {label}
        {isActive ? (
          sort.direction === "asc" ? (
            <ArrowUpIcon aria-hidden="true" />
          ) : (
            <ArrowDownIcon aria-hidden="true" />
          )
        ) : null}
      </Button>
    );
  }

  const columns: ColumnDef<ContactGroupMemberResponse>[] = [
    ...(canManage
      ? [
          {
            id: "select",
            enableSorting: false,
            header: () => (
              <Checkbox
                checked={allSelected}
                // "indeterminate" is the honest state for a partial selection;
                // a plain unchecked box would hide it.
                aria-checked={allSelected ? true : someSelected ? "mixed" : false}
                onCheckedChange={(value) => toggleAll(value === true)}
                aria-label="Select all members on this page"
              />
            ),
            cell: ({ row }: { row: { original: ContactGroupMemberResponse } }) => (
              <Checkbox
                checked={selected.has(row.original.contactId)}
                onCheckedChange={(value) =>
                  toggleOne(row.original.contactId, value === true)
                }
                aria-label={`Select ${memberLabel(row.original)}`}
              />
            ),
          } satisfies ColumnDef<ContactGroupMemberResponse>,
        ]
      : []),
    {
      id: "name",
      accessorKey: "firstName",
      header: () => sortableHeader("firstName", "Name"),
      cell: ({ row }) => {
        const contact = row.original.contact;
        return (
          <span className="font-medium">
            {contact?.firstName ?? <span className="text-muted-foreground">—</span>}
            {contact?.lastName ? ` ${contact.lastName}` : ""}
          </span>
        );
      },
    },
    {
      accessorKey: "phoneNumber",
      header: () => sortableHeader("phoneNumber", "Phone"),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <PhoneIcon aria-hidden="true" className="size-4 text-muted-foreground" />
          {row.original.contact?.phoneNumber ? (
            <code className="font-mono text-sm">{row.original.contact.phoneNumber}</code>
          ) : (
            <span className="text-muted-foreground">—</span>
          )}
        </div>
      ),
    },
    {
      // The membership's own timestamp — when this contact joined THIS group.
      // Distinct from `contact.createdAt`, which is when the identity was created
      // and can be much earlier.
      accessorKey: "createdAt",
      header: () => sortableHeader("createdAt", "Joined"),
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-muted-foreground">
          {formatDateTime(row.original.createdAt)}
        </span>
      ),
    },
    {
      id: "actions",
      enableSorting: false,
      header: () => <span className="sr-only">Actions</span>,
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
          {row.original.contact ? (
            <Button variant="ghost" size="sm" asChild>
              <Link href={`/contact-groups/${groupId}/contacts/${row.original.contactId}`}>
                View
              </Link>
            </Button>
          ) : null}
          {canManage ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onRemove(row.original)}
              title="Remove this contact from this group. The contact itself is kept."
            >
              <Link2OffIcon aria-hidden="true" className="mr-1 h-4 w-4" />
              Remove
            </Button>
          ) : null}
        </div>
      ),
    },
  ];

  // eslint-disable-next-line react-hooks/incompatible-library -- see above
  const table = useReactTable({
    data: members,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualSorting: true,
    manualPagination: true,
  });

  return (
    <Table>
      <TableHeader>
        {table.getHeaderGroups().map((headerGroup) => (
          <TableRow key={headerGroup.id} className="group/th">
            {headerGroup.headers.map((header) => {
              const sortedByThisColumn = header.column.id === sort.field;
              return (
                <TableHead
                  key={header.id}
                  aria-sort={
                    sortedByThisColumn
                      ? sort.direction === "asc"
                        ? "ascending"
                        : "descending"
                      : undefined
                  }
                >
                  {header.isPlaceholder
                    ? null
                    : flexRender(
                        header.column.columnDef.header,
                        header.getContext(),
                      )}
                </TableHead>
              );
            })}
          </TableRow>
        ))}
      </TableHeader>
      <TableBody>
        {table.getRowModel().rows.map((row) => (
          <TableRow key={row.original.memberId} data-state={selected.has(row.original.contactId) ? "selected" : undefined}>
            {row.getVisibleCells().map((cell) => (
              <TableCell key={cell.id}>
                {flexRender(cell.column.columnDef.cell, cell.getContext())}
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
