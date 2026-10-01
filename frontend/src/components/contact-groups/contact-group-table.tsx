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
  DownloadIcon,
  PencilIcon,
  Trash2Icon,
  UploadIcon,
  UsersIcon,
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
import type { ContactGroupResponse } from "@/lib/api/contracts";
import type { ContactGroupSortField } from "@/lib/api/contact-groups";
import { formatDateTime } from "@/lib/format";

export interface ContactGroupsSort {
  field: string;
  direction: "asc" | "desc";
}

interface ContactGroupTableProps {
  groups: ContactGroupResponse[];
  sort: ContactGroupsSort;
  onSortChange: (sort: ContactGroupsSort) => void;
  onEdit: (group: ContactGroupResponse) => void;
  onImport: (group: ContactGroupResponse) => void;
  onExport: (group: ContactGroupResponse) => void;
  onDelete: (group: ContactGroupResponse) => void;
  /**
   * F2 capability gating.
   *
   * `canImport` is NOT `CONTACT_IMPORT`: that capability key exists in the
   * catalogue and is granted to the same roles, but VERIFIED against
   * `ContactGroupService.importContacts` the import endpoint enforces
   * `CONTACT_MANAGE`. Gating on the un-enforced key would show a button to a
   * role that then receives a 403 — the exact "invented permission" failure the
   * brief forbids. The same reasoning applies to export, which is enforced on
   * `CONTACT_VIEW`, so export is always shown and `canExport` is unused.
   */
  canManage: boolean;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

export function ContactGroupTable({
  groups,
  sort,
  onSortChange,
  onEdit,
  onImport,
  onExport,
  onDelete,
  canManage,
}: ContactGroupTableProps) {
  function sortableHeader(
    field: ContactGroupSortField,
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

  const columns: ColumnDef<ContactGroupResponse>[] = [
    {
      accessorKey: "name",
      header: () => sortableHeader("name", "Name"),
      cell: ({ row }) => (
        <span className="font-medium">{row.original.name}</span>
      ),
    },
    {
      accessorKey: "description",
      enableSorting: false,
      header: "Description",
      cell: ({ row }) =>
        row.original.description ? (
          <span className="text-sm text-muted-foreground max-w-48 truncate block">
            {row.original.description}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      // F2: `memberCount` is a VERIFIED field on ContactGroupResponse and the
      // backend computes it in ONE grouped query for the whole page
      // (ContactGroupService.withMemberCounts → memberCounts). It is NOT
      // sortable — `name, createdAt, updatedAt` is the server's allowlist — so
      // it is never sent as a sort field.
      accessorKey: "memberCount",
      enableSorting: false,
      header: "Contacts",
      cell: ({ row }) => (
        <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
          <UsersIcon aria-hidden="true" className="size-4 text-muted-foreground" />
          <span className="tabular-nums">{row.original.memberCount}</span>
          <span className="sr-only">contacts in this group</span>
        </span>
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
      accessorKey: "updatedAt",
      header: () => sortableHeader("updatedAt", "Updated"),
      cell: ({ row }) =>
        row.original.updatedAt ? (
          <span className="whitespace-nowrap text-muted-foreground">
            {formatDateTime(row.original.updatedAt)}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: "actions",
      enableSorting: false,
      header: () => <span className="sr-only">Actions</span>,
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
          <Button variant="ghost" size="sm" asChild>
            <Link href={`/contact-groups/${row.original.id}`}>View</Link>
          </Button>
          <Button variant="ghost" size="sm" asChild>
            {/* F2: the id comes from the API record itself, so this link can
                never contain `undefined`. F0 found the sibling contact table
                building `/contact-groups/undefined/contacts/…`. */}
            <Link href={`/contact-groups/${row.original.id}/contacts`}>
              Contacts
            </Link>
          </Button>
          {/* VERIFIED: export is enforced on CONTACT_VIEW, which is the
              capability required to see the list at all — so it is always
              available to anyone who can see this row. */}
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onExport(row.original)}
            title={`Export ${row.original.name} as a file`}
          >
            <DownloadIcon aria-hidden="true" className="mr-1 h-4 w-4" />
            Export
          </Button>
          {canManage ? (
            <>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onImport(row.original)}
              >
                <UploadIcon aria-hidden="true" className="mr-1 h-4 w-4" />
                Import
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onEdit(row.original)}
              >
                <PencilIcon aria-hidden="true" className="mr-1 h-4 w-4" />
                Edit
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="text-destructive hover:bg-destructive/10"
                onClick={() => onDelete(row.original)}
                title="Delete this group"
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
    data: groups,
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
