"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, PencilIcon, PhoneIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { DidResponse } from "@/lib/api/contracts";
import type { DidSortField } from "@/lib/api/dids";
import { formatDateTime } from "@/lib/format";
import {
  DidStatusBadge,
} from "@/components/common/did-status-badge";
import {
  AllocationStateBadge,
} from "@/components/common/allocation-state-badge";
import {
  NumberTypeBadge,
} from "@/components/common/number-type-badge";

export interface DidsSort {
  field: string;
  direction: "asc" | "desc";
}

interface DidTableProps {
  dids: DidResponse[];
  sort: DidsSort;
  onSortChange: (sort: DidsSort) => void;
  onEdit: (did: DidResponse) => void;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

export function DidTable({ dids, sort, onSortChange, onEdit }: DidTableProps) {
  function sortableHeader(
    field: DidSortField,
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

  const columns: ColumnDef<DidResponse>[] = [
    {
      accessorKey: "e164Number",
      header: () => sortableHeader("e164Number", "E.164 Number"),
      cell: ({ row }) => (
        <div className="flex items-center gap-2">
          <PhoneIcon aria-hidden="true" className="size-4 text-muted-foreground" />
          <code className="font-mono text-sm">{row.original.e164Number}</code>
        </div>
      ),
    },
    {
      accessorKey: "numberType",
      header: () => sortableHeader("numberType", "Type"),
      cell: ({ row }) => (
        <NumberTypeBadge numberType={row.original.numberType} />
      ),
    },
    {
      accessorKey: "provider",
      header: () => sortableHeader("provider", "Provider"),
      cell: ({ row }) => (
        <span className="text-sm">{row.original.provider}</span>
      ),
    },
    {
      accessorKey: "circle",
      enableSorting: false,
      header: "Circle",
      cell: ({ row }) =>
        row.original.circle ? (
          <span className="text-sm text-muted-foreground">{row.original.circle}</span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      accessorKey: "status",
      header: () => sortableHeader("status", "Status"),
      cell: ({ row }) => <DidStatusBadge status={row.original.status} />,
    },
    {
      accessorKey: "allocationState",
      header: () => sortableHeader("allocationState", "Allocation"),
      cell: ({ row }) => (
        <AllocationStateBadge allocationState={row.original.allocationState} />
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
            <Link href={`/dids/${row.original.id}`}>View</Link>
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
    data: dids,
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