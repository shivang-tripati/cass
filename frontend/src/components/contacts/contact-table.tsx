"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import {
  ArrowDownIcon,
  ArrowUpIcon,
  Link2OffIcon,
  MailIcon,
  PencilIcon,
  PhoneIcon,
  Trash2Icon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { ContactResponse } from "@/lib/api/contracts";
import type { ContactSortField } from "@/lib/api/contacts";
import { formatDateTime } from "@/lib/format";

export interface ContactsSort {
  field: string;
  direction: "asc" | "desc";
}

interface ContactTableProps {
  contacts: ContactResponse[];
  /**
   * F2: taken from the ROUTE, never from the contact.
   *
   * VERIFIED: `contact/dto/ContactResponse.java` has nine components and none
   * is a group id — a contact is a tenant-level identity and a group is the
   * parent segment of its REST route. F0 declared `contactGroupId` on the
   * contact; it was always `undefined` and every per-contact link resolved to
   * `/contact-groups/undefined/contacts/…`. F1 removed the phantom field; F2
   * makes sure the group id actually arrives, from the one place it exists.
   */
  contactGroupId: string;
  sort: ContactsSort;
  onSortChange: (sort: ContactsSort) => void;
  onEdit: (contact: ContactResponse) => void;
  /** Removes the contact from EVERY group (identity delete). */
  onDelete: (contact: ContactResponse) => void;
  /** Removes the contact from THIS group only. */
  onRemoveFromGroup: (contact: ContactResponse) => void;
  /** F2: F1 capability gating. Reads are always shown; writes are gated. */
  canManage: boolean;
  /** F2: the group's display name, so the delete dialog can name it. */
  groupName: string;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

export function ContactTable({
  contacts,
  contactGroupId,
  sort,
  onSortChange,
  onEdit,
  onDelete,
  onRemoveFromGroup,
  canManage,
  groupName,
}: ContactTableProps) {
  function sortableHeader(
    field: ContactSortField,
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

  const columns: ColumnDef<ContactResponse>[] = [
    {
      accessorKey: "firstName",
      header: () => sortableHeader("firstName", "First Name"),
      cell: ({ row }) => (
        <span className="font-medium">
          {row.original.firstName ?? <span className="text-muted-foreground">—</span>}
        </span>
      ),
    },
    {
      accessorKey: "lastName",
      enableSorting: false,
      header: "Last Name",
      cell: ({ row }) =>
        row.original.lastName ? (
          <span className="font-medium">{row.original.lastName}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      accessorKey: "phoneNumber",
      header: () => sortableHeader("phoneNumber", "Phone"),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <PhoneIcon aria-hidden="true" className="size-4 text-muted-foreground" />
          <code className="font-mono text-sm">{row.original.phoneNumber}</code>
        </div>
      ),
    },
    {
      accessorKey: "email",
      enableSorting: false,
      header: "Email",
      cell: ({ row }) =>
        row.original.email ? (
          <div className="flex items-center gap-2">
            <MailIcon aria-hidden="true" className="size-4 text-muted-foreground" />
            <span className="break-all">{row.original.email}</span>
          </div>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      accessorKey: "createdAt",
      header: () => sortableHeader("createdAt", "Created"),
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
          <Button variant="ghost" size="sm" asChild>
            <Link href={`/contact-groups/${contactGroupId}/contacts/${row.original.id}`}>
              View
            </Link>
          </Button>
          {canManage ? (
            <>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onEdit(row.original)}
              >
                <PencilIcon aria-hidden="true" className="mr-1 h-4 w-4" />
                Edit
              </Button>
              {/* F2: distinct from Delete. "Remove from group" keeps the
                  contact; "Delete" removes it from every group. */}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onRemoveFromGroup(row.original)}
                title={`Remove from ${groupName}, keeping the contact`}
              >
                <Link2OffIcon aria-hidden="true" className="mr-1 h-4 w-4" />
                <span className="sr-only sm:not-sr-only">Remove from group</span>
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="text-destructive hover:bg-destructive/10"
                onClick={() => onDelete(row.original)}
                title="Delete the contact from every group"
              >
                <Trash2Icon aria-hidden="true" className="mr-1 h-4 w-4" />
                Delete
              </Button>
            </>
          ) : null}
        </div>
      ),
    },
  ];

  // eslint-disable-next-line react-hooks/incompatible-library -- see above
  const table = useReactTable({
    data: contacts,
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
          <TableRow key={row.original.id}>
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
