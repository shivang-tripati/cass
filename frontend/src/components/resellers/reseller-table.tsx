"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, PencilIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { ResellerResponse } from "@/lib/api/contracts";
import type { ResellerSortField } from "@/lib/api/resellers";
import { formatDateTime } from "@/lib/format";
import {
  LifecycleStatusBadge,
} from "@/components/common/lifecycle-status-badge";

export interface ResellersSort {
  field: string;
  direction: "asc" | "desc";
}

interface ResellerTableProps {
  resellers: ResellerResponse[];
  sort: ResellersSort;
  onSortChange: (sort: ResellersSort) => void;
  onEdit: (reseller: ResellerResponse) => void;
}

/** Cycle: asc → desc → asc … */
function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

export function ResellerTable({
  resellers,
  sort,
  onSortChange,
  onEdit,
}: ResellerTableProps) {
  function sortableHeader(
    field: ResellerSortField,
    label: string,
  ): React.ReactNode {
    const isActive = sort.field === field;
    return (
      <Button
        variant="ghost"
        size="sm"
        className="-ml-2 h-7 data-[active=true]:text-foreground"
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

  const columns: ColumnDef<ResellerResponse>[] = [
    {
      accessorKey: "name",
      header: () => sortableHeader("name", "Name"),
      cell: ({ row }) => (
        <span className="font-medium">
          {row.original.displayName ?? row.original.name}
        </span>
      ),
    },
    {
      accessorKey: "slug",
      header: () => sortableHeader("slug", "Slug"),
      cell: ({ row }) => (
        <code className="text-xs text-muted-foreground">
          {row.original.slug}
        </code>
      ),
    },
    {
      accessorKey: "status",
      header: () => sortableHeader("status", "Status"),
      cell: ({ row }) => (
        <LifecycleStatusBadge status={row.original.status} />
      ),
    },
    {
      accessorKey: "supportEmail",
      enableSorting: false,
      header: "Support email",
      cell: ({ row }) =>
        row.original.supportEmail ?? (
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
            <Link href={`/resellers/${row.original.id}`}>View</Link>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onEdit(row.original)}
          >
            <PencilIcon aria-hidden="true" />
            Edit
          </Button>
        </div>
      ),
    },
  ];

  // React Compiler skips memoization for this component because
  // useReactTable returns an instance with methods; the instance stays
  // local to this component, so skipping is safe and intended.
  // eslint-disable-next-line react-hooks/incompatible-library -- see above
  const table = useReactTable({
    data: resellers,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualSorting: true,
    manualPagination: true,
  });

  return (
    <Table>
      <TableHeader>
        {table.getHeaderGroups().map((headerGroup) => (
          <TableRow key={headerGroup.id}>
            {headerGroup.headers.map((header) => {
              // Sortable column ids match ResellerSortField values 1:1.
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
