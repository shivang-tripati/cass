"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, PencilIcon, CopyIcon, PlayIcon, PauseIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { CampaignResponse } from "@/lib/api/contracts";
import type { CampaignSortField } from "@/lib/api/campaigns";
import { formatDateTime } from "@/lib/format";
import {
  CampaignStatusBadge,
} from "@/components/common/campaign-status-badge";
import {
  CampaignTypeBadge,
} from "@/components/common/campaign-type-badge";
import {
  CampaignRunModeBadge,
} from "@/components/common/campaign-run-mode-badge";

export interface CampaignsSort {
  field: string;
  direction: "asc" | "desc";
}

interface CampaignTableProps {
  campaigns: CampaignResponse[];
  sort: CampaignsSort;
  onSortChange: (sort: CampaignsSort) => void;
  onEdit: (campaign: CampaignResponse) => void;
  onClone: (campaign: CampaignResponse) => void;
  onStatusChange: (campaign: CampaignResponse) => void;
}

function nextDirection(current: "asc" | "desc"): "asc" | "desc" {
  return current === "asc" ? "desc" : "asc";
}

export function CampaignTable({
  campaigns,
  sort,
  onSortChange,
  onEdit,
  onClone,
  onStatusChange,
}: CampaignTableProps) {
  function sortableHeader(
    field: CampaignSortField,
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

  const columns: ColumnDef<CampaignResponse>[] = [
    {
      accessorKey: "name",
      header: () => sortableHeader("name", "Name"),
      cell: ({ row }) => (
        <span className="font-medium">{row.original.name}</span>
      ),
    },
    {
      accessorKey: "campaignType",
      header: () => sortableHeader("campaignType", "Type"),
      cell: ({ row }) => (
        <CampaignTypeBadge campaignType={row.original.campaignType} />
      ),
    },
    {
      accessorKey: "runMode",
      enableSorting: false,
      header: "Run Mode",
      cell: ({ row }) => (
        <CampaignRunModeBadge runMode={row.original.runMode} />
      ),
    },
    {
      accessorKey: "status",
      header: () => sortableHeader("status", "Status"),
      cell: ({ row }) => <CampaignStatusBadge status={row.original.status} />,
    },
    {
      accessorKey: "version",
      enableSorting: false,
      header: "Version",
      cell: ({ row }) => (
        <span className="text-sm text-muted-foreground font-mono">
          v{row.original.version}
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
      id: "actions",
      enableSorting: false,
      header: () => <span className="sr-only">Actions</span>,
      cell: ({ row }) => {
        const campaign = row.original;
        const legalTransitions = CAMPAIGN_LEGAL_TRANSITIONS[campaign.status] ?? [];
        const isTerminal = campaign.status === "COMPLETED" || campaign.status === "FAILED" || campaign.status === "ARCHIVED";
        const canTransition = legalTransitions.length > 0 && !isTerminal;

        return (
          <div className="flex items-center justify-end gap-1">
            <Button variant="ghost" size="sm" asChild>
              <Link href={`/campaigns/${campaign.id}`}>View</Link>
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onEdit(campaign)}
            >
              <PencilIcon aria-hidden="true" />
              Edit
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => onClone(campaign)}
            >
              <CopyIcon aria-hidden="true" />
              Clone
            </Button>
            {canTransition && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => onStatusChange(campaign)}
              >
                {campaign.status === "DRAFT" ? (
                  <>
                    <PlayIcon aria-hidden="true" />
                    Schedule
                  </>
                ) : campaign.status === "SCHEDULED" ? (
                  <>
                    <PauseIcon aria-hidden="true" />
                    Pause
                  </>
                ) : campaign.status === "RUNNING" ? (
                  <>
                    <PauseIcon aria-hidden="true" />
                    Pause
                  </>
                ) : campaign.status === "PAUSED" ? (
                  <>
                    <PlayIcon aria-hidden="true" />
                    Resume
                  </>
                ) : (
                  <span>Archive</span>
                )}
              </Button>
            )}
          </div>
        );
      },
    },
  ];

  // eslint-disable-next-line react-hooks/incompatible-library -- see above
  const table = useReactTable({
    data: campaigns,
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

// Legal transitions mirror backend (used for action buttons)
const CAMPAIGN_LEGAL_TRANSITIONS: Record<string, string[]> = {
  DRAFT: ["SCHEDULED"],
  SCHEDULED: ["RUNNING", "PAUSED", "DRAFT", "ARCHIVED"],
  RUNNING: ["PAUSED", "COMPLETED", "FAILED"],
  PAUSED: ["SCHEDULED", "RUNNING", "ARCHIVED"],
  COMPLETED: ["ARCHIVED"],
  FAILED: ["ARCHIVED"],
  ARCHIVED: [],
};