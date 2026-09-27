"use client";

import { SearchIcon, XIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { LifecycleStatus } from "@/lib/api/types";

interface StatusSearchToolbarProps {
  /** Uniquely prefixes element ids when several toolbars could coexist. */
  idPrefix: string;
  /** Committed search term (URL-backed); fully controlled. */
  search: string;
  status: LifecycleStatus | "";
  onSearchInput: (search: string) => void;
  onSearchClear: () => void;
  onStatusChange: (status: LifecycleStatus | "") => void;
  searchPlaceholder?: string;
}

/**
 * Server-side filter toolbar for lifecycle-status datasets. Fully
 * controlled by URL-backed list state: every keystroke commits
 * immediately, while data fetching is debounced by the owning view.
 */
export function StatusSearchToolbar({
  idPrefix,
  search,
  status,
  onSearchInput,
  onSearchClear,
  onStatusChange,
  searchPlaceholder = "Search…",
}: StatusSearchToolbarProps) {
  const searchId = `${idPrefix}-search`;
  const statusId = `${idPrefix}-status-filter`;

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
      <div className="relative flex-1 sm:max-w-xs">
        <SearchIcon
          aria-hidden="true"
          className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
        />
        <Input
          id={searchId}
          type="search"
          value={search}
          onChange={(event) => onSearchInput(event.target.value)}
          placeholder={searchPlaceholder}
          className="pl-8 pr-8"
          aria-label={searchPlaceholder}
        />
        {search ? (
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-label="Clear search"
            className="absolute right-1 top-1/2 -translate-y-1/2"
            onClick={onSearchClear}
          >
            <XIcon aria-hidden="true" />
          </Button>
        ) : null}
      </div>

      <div className="flex items-center gap-2">
        <Label htmlFor={statusId} className="sr-only">
          Filter by status
        </Label>
        <Select
          value={status || "ALL"}
          onValueChange={(value) =>
            onStatusChange(value === "ALL" ? "" : (value as LifecycleStatus))
          }
        >
          <SelectTrigger id={statusId} size="sm" className="w-36">
            <SelectValue aria-label="Filter by status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="ACTIVE">Active</SelectItem>
            <SelectItem value="SUSPENDED">Suspended</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}
