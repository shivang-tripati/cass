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
import type { DidStatus } from "@/lib/api/contracts";

interface DidFilterToolbarProps {
  idPrefix: string;
  search: string;
  status: DidStatus | "";
  onSearchInput: (search: string) => void;
  onSearchClear: () => void;
  onStatusChange: (status: DidStatus | "") => void;
  searchPlaceholder?: string;
}

export function DidFilterToolbar({
  idPrefix,
  search,
  status,
  onSearchInput,
  onSearchClear,
  onStatusChange,
  searchPlaceholder = "Search…",
}: DidFilterToolbarProps) {
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
            onStatusChange(value === "ALL" ? "" : (value as DidStatus))
          }
        >
          <SelectTrigger id={statusId} size="sm" className="w-36">
            <SelectValue aria-label="Filter by status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="ACTIVE">Active</SelectItem>
            <SelectItem value="INACTIVE">Inactive</SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}