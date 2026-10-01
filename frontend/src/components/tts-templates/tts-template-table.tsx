"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon } from "lucide-react";

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
import { TtsTemplateScopeBadge } from "@/components/tts-templates/tts-template-scope-badge";
import { TtsTemplateActions } from "@/components/tts-templates/tts-template-actions";
import type { TtsTemplateResponse } from "@/lib/api/contracts";
import type { TtsTemplateSortField } from "@/lib/api/tts-templates";
import { formatDateTime } from "@/lib/format";

export interface TtsSort {
  field: string;
  direction: "asc" | "desc";
}

interface TtsTemplateTableProps {
  templates: TtsTemplateResponse[];
  sort: TtsSort;
  onSortChange: (sort: TtsSort) => void;
  /**
   * Per-row capability resolution. These are FUNCTIONS rather than booleans
   * because the answer depends on the row's scope: a GLOBAL template needs
   * platform scope for every write, while a TENANT template needs the tenant
   * capability. A single `canManage` boolean cannot express that, and computing
   * it per row in the view would spread the scope rule across the file.
   */
  canManage: (template: TtsTemplateResponse) => boolean;
  canApprove: (template: TtsTemplateResponse) => boolean;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

/**
 * The TTS template table.
 *
 * ## Scope is a column, not a filter
 *
 * The list endpoint returns a MERGED page — a tenant sees its own rows plus the
 * approved global catalog, a reseller sees its hierarchy's rows plus the same
 * catalog — and it has **no `scope` query parameter**. A scope filter would
 * therefore be evaluated over one page of a paginated merged result, so a page
 * with no global rows would look like "no global templates exist" while hundreds
 * sit on other pages. F3 adds no such control and shows the scope per row
 * instead, which is what actually distinguishes the resources.
 *
 * Sortable columns are exactly the four the backend whitelists
 * (`name, createdAt, updatedAt, status`). Scope and template text are not
 * sortable and are never sent as a sort field.
 */
export function TtsTemplateTable({
  templates,
  sort,
  onSortChange,
  canManage,
  canApprove,
}: TtsTemplateTableProps) {
  function sortableHeader(
    field: TtsTemplateSortField,
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

  const columns: ColumnDef<TtsTemplateResponse>[] = [
    {
      accessorKey: "name",
      header: () => sortableHeader("name", "Name"),
      cell: ({ row }) => (
        <span className="font-medium">{row.original.name}</span>
      ),
    },
    {
      // Not sortable — `scope` is not in the backend's allowlist.
      id: "scope",
      enableSorting: false,
      header: "Scope",
      cell: ({ row }) => (
        <TtsTemplateScopeBadge scope={row.original.scope} />
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
      // Not sortable — `templateText` is not in the allowlist either.
      id: "templateText",
      enableSorting: false,
      header: "Template text",
      cell: ({ row }) => (
        <span
          className="block max-w-[32ch] truncate text-sm text-muted-foreground"
          title={row.original.templateText}
        >
          {row.original.templateText}
        </span>
      ),
    },
    {
      id: "variables",
      enableSorting: false,
      header: "Variables",
      cell: ({ row }) => {
        const count = row.original.variables?.length ?? 0;
        return (
          <span className="whitespace-nowrap tabular-nums text-muted-foreground">
            {count}
          </span>
        );
      },
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
            <Link href={`/tts-templates/${row.original.id}`}>View</Link>
          </Button>
          <TtsTemplateActions
            template={row.original}
            canManage={canManage(row.original)}
            canApprove={canApprove(row.original)}
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
    data: templates,
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
