"use client";

import { useId } from "react";
import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { PaginationMetadata } from "@/lib/api/types";

interface TablePaginationProps {
  pagination: PaginationMetadata;
  pageSize: number;
  /** Plural entity label for the range summary, e.g. "users". */
  entityLabel: string;
  onPageSizeChange?: (size: number) => void;
  onPageChange: (page: number) => void;
}

/** Accessible server-pagination controls driven by backend metadata. */
export function TablePagination({
  pagination,
  pageSize,
  entityLabel,
  onPageSizeChange,
  onPageChange,
}: TablePaginationProps) {
  const selectId = `page-size-${useId()}`;
  const { page, totalElements, totalPages, hasNext, hasPrevious } = pagination;

  const rangeStart = totalElements === 0 ? 0 : page * pageSize + 1;
  const rangeEnd = Math.min((page + 1) * pageSize, totalElements);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p
        className="text-sm text-muted-foreground"
        aria-live="polite"
        aria-atomic="true"
      >
        {totalElements === 0
          ? `No ${entityLabel}`
          : `Showing ${rangeStart}–${rangeEnd} of ${totalElements} ${entityLabel}`}
      </p>

      <div className="flex items-center gap-4">
        {onPageSizeChange ? (
          <div className="flex items-center gap-2">
            <label
              htmlFor={selectId}
              className="text-sm text-muted-foreground"
            >
              Rows
            </label>
            <Select
              value={String(pageSize)}
              onValueChange={(value) => onPageSizeChange(Number(value))}
            >
              <SelectTrigger id={selectId} size="sm" className="w-18">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {[10, 20, 50].map((size) => (
                  <SelectItem key={size} value={String(size)}>
                    {size}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        ) : null}

        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground tabular-nums">
            Page {totalPages === 0 ? 0 : page + 1} of {totalPages}
          </span>
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="Previous page"
            disabled={!hasPrevious}
            onClick={() => onPageChange(page - 1)}
          >
            <ChevronLeftIcon aria-hidden="true" />
          </Button>
          <Button
            variant="outline"
            size="icon-sm"
            aria-label="Next page"
            disabled={!hasNext}
            onClick={() => onPageChange(page + 1)}
          >
            <ChevronRightIcon aria-hidden="true" />
          </Button>
        </div>
      </div>
    </div>
  );
}
