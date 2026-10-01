"use client";

import Link from "next/link";
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from "@tanstack/react-table";
import { ArrowDownIcon, ArrowUpIcon, PencilIcon, CopyIcon } from "lucide-react";

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
import { CampaignStatusBadge } from "@/components/common/campaign-status-badge";
import { CampaignTypeBadge } from "@/components/common/campaign-type-badge";
import { CampaignRunModeBadge } from "@/components/common/campaign-run-mode-badge";
import {
  availableTransitions,
  isEditable,
} from "@/lib/domain/campaign-lifecycle";

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
  /**
   * F1 — action gating, decided once in the view.
   * VERIFIED split: PUT /campaigns/{id}, DELETE and POST /clone all require
   * CAMPAIGN_MANAGE (CampaignController L119, L136, L178); PATCH /status and
   * POST /executions require CAMPAIGN_EXECUTE (L157, L215). These are different
   * capabilities and V1 grants both to SUPER_ADMIN/RESELLER_ADMIN/TENANT_ADMIN,
   * so the UI must not conflate them.
   */
  canManage: boolean;
  canExecute: boolean;
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
  canManage,
  canExecute,
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
        // F4: this used to be
        //   const isTerminal = ["COMPLETED","FAILED","ARCHIVED"].includes(status)
        //   const canTransition = CAMPAIGN_LEGAL_TRANSITIONS[status].length > 0 && !isTerminal
        // which was wrong in two directions at once. `LEGAL_TRANSITIONS` is the
        // backend's LEGAL set and still contains the three edges the service
        // refuses with 409 because the execution engine owns them, so a RUNNING
        // campaign was offered Pause, Complete and Fail — and Complete and Fail
        // can only ever 409. The file also carried its own private copy of the
        // transition table, which is a second source of truth for a state
        // machine. Both now come from `@/lib/domain/campaign-lifecycle`.
        // See F4 doc §12, drift items D3 and D4.
        const transitions = availableTransitions(campaign.status);
        return (
          <div className="flex items-center justify-end gap-1">
            <Button variant="ghost" size="sm" asChild>
              <Link href={`/campaigns/${campaign.id}`}>View</Link>
            </Button>
            {/* F4: Edit requires DRAFT, not merely CAMPAIGN_MANAGE. VERIFIED
                `CampaignLifecyclePolicy.assertEditable` throws 409 for every
                other status, so offering Edit elsewhere produced a form the
                user could complete and then be rejected by. */}
            {canManage && isEditable(campaign.status) && (
              <Button variant="ghost" size="sm" onClick={() => onEdit(campaign)}>
                <PencilIcon aria-hidden="true" />
                Edit
              </Button>
            )}
            {canManage && (
              <Button variant="ghost" size="sm" onClick={() => onClone(campaign)}>
                <CopyIcon aria-hidden="true" />
                Clone
              </Button>
            )}
            {/* F4: the transition needs CAMPAIGN_EXECUTE, not CAMPAIGN_MANAGE
                (VERIFIED `CampaignService.changeStatus` L277), so it is gated
                separately, and it is offered only when a legal, non-engine-driven
                edge actually exists. */}
            {canExecute && transitions.length > 0 && (
              <Button variant="ghost" size="sm" onClick={() => onStatusChange(campaign)}>
                {transitionVerb(campaign.status)}
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
/**
 * A short verb for the single most likely transition from `status`.
 *
 * VERIFIED against the backend's legal edges: DRAFT can only become SCHEDULED,
 * so "Schedule" is exact. SCHEDULED and RUNNING can become PAUSED, and PAUSED
 * can become SCHEDULED or RUNNING. Everything else reaches only ARCHIVED, which
 * is why the fallback names the state rather than implying a schedule.
 *
 * The dialog that opens is the authority on which edges exist; this is only the
 * button label, and it is derived from the same `availableTransitions` set so it
 * cannot name a transition the server would refuse.
 */
function transitionVerb(status: CampaignResponse["status"]): string {
  switch (status) {
    case "DRAFT":
      return "Schedule";
    case "SCHEDULED":
    case "RUNNING":
      return "Pause";
    case "PAUSED":
      return "Resume";
    default:
      return `Archive`;
  }
}