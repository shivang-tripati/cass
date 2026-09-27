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
import type { CampaignStatus, CampaignType, CampaignRunMode } from "@/lib/api/contracts";

interface CampaignFilterToolbarProps {
  idPrefix: string;
  search: string;
  status: CampaignStatus | "";
  campaignType: CampaignType | "";
  runMode: CampaignRunMode | "";
  onSearchInput: (search: string) => void;
  onSearchClear: () => void;
  onStatusChange: (status: CampaignStatus | "") => void;
  onCampaignTypeChange: (campaignType: CampaignType | "") => void;
  onRunModeChange: (runMode: CampaignRunMode | "") => void;
  searchPlaceholder?: string;
}

export function CampaignFilterToolbar({
  idPrefix,
  search,
  status,
  campaignType,
  runMode,
  onSearchInput,
  onSearchClear,
  onStatusChange,
  onCampaignTypeChange,
  onRunModeChange,
  searchPlaceholder = "Search name, description…",
}: CampaignFilterToolbarProps) {
  const searchId = `${idPrefix}-search`;
  const statusId = `${idPrefix}-status-filter`;
  const typeId = `${idPrefix}-type-filter`;
  const runModeId = `${idPrefix}-runmode-filter`;

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

      <div className="flex flex-wrap items-center gap-2">
        <div className="flex items-center gap-2">
          <Label htmlFor={statusId} className="sr-only">
            Filter by status
          </Label>
          <Select
            value={status || "ALL"}
            onValueChange={(value) =>
              onStatusChange(value === "ALL" ? "" : (value as CampaignStatus))
            }
          >
            <SelectTrigger id={statusId} size="sm" className="w-36">
              <SelectValue aria-label="Filter by status" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All statuses</SelectItem>
              <SelectItem value="DRAFT">Draft</SelectItem>
              <SelectItem value="SCHEDULED">Scheduled</SelectItem>
              <SelectItem value="RUNNING">Running</SelectItem>
              <SelectItem value="PAUSED">Paused</SelectItem>
              <SelectItem value="COMPLETED">Completed</SelectItem>
              <SelectItem value="FAILED">Failed</SelectItem>
              <SelectItem value="ARCHIVED">Archived</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-2">
          <Label htmlFor={typeId} className="sr-only">
            Filter by type
          </Label>
          <Select
            value={campaignType || "ALL"}
            onValueChange={(value) =>
              onCampaignTypeChange(value === "ALL" ? "" : (value as CampaignType))
            }
          >
            <SelectTrigger id={typeId} size="sm" className="w-36">
              <SelectValue aria-label="Filter by type" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All types</SelectItem>
              <SelectItem value="PLAYFILE">Play File</SelectItem>
              <SelectItem value="DTMF">DTMF</SelectItem>
              <SelectItem value="CONNECT_BY_AGENT">Connect by Agent</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-2">
          <Label htmlFor={runModeId} className="sr-only">
            Filter by run mode
          </Label>
          <Select
            value={runMode || "ALL"}
            onValueChange={(value) =>
              onRunModeChange(value === "ALL" ? "" : (value as CampaignRunMode))
            }
          >
            <SelectTrigger id={runModeId} size="sm" className="w-36">
              <SelectValue aria-label="Filter by run mode" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">All modes</SelectItem>
              <SelectItem value="ONE_TIME">One Time</SelectItem>
              <SelectItem value="RECURRING">Recurring</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>
  );
}