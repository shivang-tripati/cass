"use client";

import { SearchIcon, XIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

interface ContactFilterToolbarProps {
  idPrefix: string;
  search: string;
  onSearchInput: (search: string) => void;
  onSearchClear: () => void;
  searchPlaceholder?: string;
}

export function ContactFilterToolbar({
  idPrefix,
  search,
  onSearchInput,
  onSearchClear,
  searchPlaceholder = "Search name, phone, email…",
}: ContactFilterToolbarProps) {
  const searchId = `${idPrefix}-search`;

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
    </div>
  );
}