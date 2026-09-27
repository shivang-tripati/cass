"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, CheckIcon, PlayIcon, XCircleIcon, XIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { CallAttemptResponse } from "@/lib/api/contracts";
import type { CallAttemptSortField } from "@/lib/api/call-attempts";
import { formatDateTime } from "@/lib/format";
import {
  CallAttemptStatusBadge,
} from "@/components/common/call-attempt-status-badge";
import {
  markAttemptInProgress,
  markAttemptCompleted,
  markAttemptFailed,
  cancelCallAttempt,
} from "@/lib/api/call-attempts";

export interface CallAttemptsSort {
  field: string;
  direction: "asc" | "desc";
}

interface CallAttemptTableProps {
  attempts: CallAttemptResponse[];
  sort: CallAttemptsSort;
  onSortChange: (sort: CallAttemptsSort) => void;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

function getLegalTransitions(status: string): string[] {
  const transitions: Record<string, string[]> = {
    QUEUED: ["IN_PROGRESS", "FAILED", "CANCELLED"],
    IN_PROGRESS: ["COMPLETED", "FAILED", "CANCELLED"],
    COMPLETED: [],
    FAILED: [],
    CANCELLED: [],
  };
  return transitions[status] ?? [];
}

function isTerminalStatus(status: string): boolean {
  return ["COMPLETED", "FAILED", "CANCELLED"].includes(status);
}

function ActionButtons({ attempt }: { attempt: CallAttemptResponse }) {
  const transitions = getLegalTransitions(attempt.status);
  const canTransition = transitions.length > 0 && !isTerminalStatus(attempt.status);

  const viewLink = (
    <Button variant="ghost" size="sm" asChild>
      <Link href={`/campaigns/${attempt.campaignId}/executions/${attempt.executionId}/attempts/${attempt.id}`}>
        View
      </Link>
    </Button>
  );

  if (!canTransition) {
    return (
      <div className="flex items-center justify-end gap-1">
        {viewLink}
      </div>
    );
  }

  return (
    <div className="flex items-center justify-end gap-1">
      {viewLink}
      <div className="flex gap-1">
        {attempt.status === "QUEUED" && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => markAttemptInProgress(attempt.campaignId, attempt.executionId, attempt.id)}
          >
            <PlayIcon className="mr-1 h-4 w-4" />
            Start
          </Button>
        )}
        {attempt.status === "IN_PROGRESS" && (
          <div className="flex gap-1">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => markAttemptCompleted(attempt.campaignId, attempt.executionId, attempt.id)}
            >
              <CheckIcon className="mr-1 h-4 w-4" />
              Complete
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => markAttemptFailed(attempt.campaignId, attempt.executionId, attempt.id)}
            >
              <XCircleIcon className="mr-1 h-4 w-4" />
              Fail
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="text-destructive"
              onClick={() => cancelCallAttempt(attempt.campaignId, attempt.executionId, attempt.id)}
            >
              <XIcon className="mr-1 h-4 w-4" />
              Cancel
            </Button>
          </div>
        )}
        {attempt.status === "FAILED" && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => cancelCallAttempt(attempt.campaignId, attempt.executionId, attempt.id)}
          >
            <XIcon className="mr-1 h-4 w-4" />
            Cancel
          </Button>
        )}
      </div>
    </div>
  );
}

function sortableHeader(
  field: CallAttemptSortField,
  label: string,
  sort: CallAttemptsSort,
  onSortChange: (sort: CallAttemptsSort) => void
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

export function CallAttemptTable({
  attempts,
  sort,
  onSortChange,
}: CallAttemptTableProps) {
  const columns: ColumnDef<CallAttemptResponse>[] = [
    {
      accessorKey: "attemptNumber",
      header: () => sortableHeader("attemptNumber", "Attempt #", sort, onSortChange),
      cell: ({ row }) => (
        <span className="font-medium">{row.original.attemptNumber}</span>
      ),
    },
    {
      accessorKey: "scheduledAt",
      header: () => sortableHeader("scheduledAt", "Scheduled", sort, onSortChange),
      cell: ({ row }) => (
        <span className="whitespace-nowrap text-muted-foreground text-sm">
          {formatDateTime(row.original.scheduledAt)}
        </span>
      ),
    },
    {
      accessorKey: "status",
      header: () => sortableHeader("status", "Status", sort, onSortChange),
      cell: ({ row }) => (
        <CallAttemptStatusBadge status={row.original.status} />
      ),
    },
    {
      accessorKey: "startedAt",
      header: () => sortableHeader("startedAt", "Started", sort, onSortChange),
      cell: ({ row }) =>
        row.original.startedAt ? (
          <span className="whitespace-nowrap text-muted-foreground text-sm">
            {formatDateTime(row.original.startedAt!)}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      accessorKey: "completedAt",
      header: () => sortableHeader("completedAt", "Completed", sort, onSortChange),
      cell: ({ row }) =>
        row.original.completedAt ? (
          <span className="whitespace-nowrap text-muted-foreground text-sm">
            {formatDateTime(row.original.completedAt!)}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: "failure",
      enableSorting: false,
      header: "Failure",
      cell: ({ row }) =>
        row.original.failureCode || row.original.failureReason ? (
          <span className="text-sm text-destructive">
            {row.original.failureCode ?? ""}
            {row.original.failureCode && row.original.failureReason ? ": " : ""}
            {row.original.failureReason ?? ""}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: "actions",
      enableSorting: false,
      header: () => <span className="sr-only">Actions</span>,
      cell: ({ row }) => <ActionButtons attempt={row.original} />,
    },
  ];

  const table = useReactTable({
    data: attempts,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualSorting: true,
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
                    : flexRender(header.column.columnDef.header, header.getContext())}
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