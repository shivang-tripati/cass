"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, FileAudioIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ApprovalStatusBadge } from "@/components/common/approval-status-badge";
import { AudioAssetActions } from "@/components/audio-assets/audio-asset-actions";
import type { AudioAssetResponse } from "@/lib/api/contracts";
import type { AudioAssetSortField } from "@/lib/api/audio-assets";
import { formatDateTime } from "@/lib/format";

export interface AudioSort {
  field: string;
  direction: "asc" | "desc";
}

interface AudioAssetTableProps {
  assets: AudioAssetResponse[];
  sort: AudioSort;
  onSortChange: (sort: AudioSort) => void;
  /** `AUDIO_MANAGE` — edit and delete. */
  canManage: boolean;
  /** `AUDIO_APPROVE` — approve and reject. Deliberately separate. */
  canApprove: boolean;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

/**
 * The audio asset table.
 *
 * Columns are exactly the fields the backend can sort by, plus the two that make
 * a row identifiable. `fileSize` and `durationSeconds` are NOT sortable —
 * `AudioAssetService.SORTABLE_FIELDS` is `name, fileName, createdAt, updatedAt,
 * status` — so those columns are marked unsortable and never sent as a sort
 * field.
 *
 * `updatedAt` is nullable (`AuditableEntity` leaves it null until the first
 * modification), so a never-edited recording shows a dash rather than a
 * misleading date.
 *
 * `storageReference` is deliberately absent: it is a backend storage locator,
 * not something a user acts on. It is typed in the contract and never rendered.
 */
export function AudioAssetTable({
  assets,
  sort,
  onSortChange,
  canManage,
  canApprove,
}: AudioAssetTableProps) {
  function sortableHeader(
    field: AudioAssetSortField,
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

  const columns: ColumnDef<AudioAssetResponse>[] = [
    {
      accessorKey: "name",
      header: () => sortableHeader("name", "Name"),
      cell: ({ row }) => (
        <span className="font-medium">{row.original.name}</span>
      ),
    },
    {
      accessorKey: "fileName",
      header: () => sortableHeader("fileName", "File"),
      cell: ({ row }) => (
        <span className="flex items-center gap-2">
          <FileAudioIcon
            aria-hidden="true"
            className="size-4 shrink-0 text-muted-foreground"
          />
          <code className="truncate text-xs">
            {row.original.fileName}
          </code>
        </span>
      ),
    },
    {
      accessorKey: "contentType",
      enableSorting: false,
      header: "Type",
      cell: ({ row }) => (
        <span className="text-xs text-muted-foreground">
          {row.original.contentType}
        </span>
      ),
    },
    {
      // Not sortable: the whitelist has no size column.
      id: "size",
      enableSorting: false,
      header: "Size",
      cell: ({ row }) => (
        <span className="whitespace-nowrap tabular-nums text-muted-foreground">
          {formatBytes(row.original.fileSize)}
        </span>
      ),
    },
    {
      accessorKey: "status",
      header: () => sortableHeader("status", "Status"),
      cell: ({ row }) => (
        <ApprovalStatusBadge status={row.original.status} />
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
          <span className="text-muted-foreground" title="Never edited since upload">
            —
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
            <Link href={`/audio-assets/${row.original.id}`}>View</Link>
          </Button>
          <AudioAssetActions
            asset={row.original}
            canManage={canManage}
            canApprove={canApprove}
            variant="row"
          />
        </div>
      ),
    },
  ];

  // TanStack Table's getCoreRowModel returns a function the React Compiler
  // cannot memoize. The rule below suppresses a compilation advisory, not a
  // correctness problem -- the same suppression every other table in the app uses.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: assets,
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

/** Binary size, from the non-null `fileSize`. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
