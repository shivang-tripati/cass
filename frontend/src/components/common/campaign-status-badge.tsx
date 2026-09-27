import { Badge } from "@/components/ui/badge";
import type { CampaignStatus } from "@/lib/api/contracts";

const STATUS_META: Record<
  CampaignStatus,
  { label: string; className: string }
> = {
  DRAFT: {
    label: "Draft",
    className:
      "bg-slate-500/10 text-slate-700 dark:text-slate-400 border-slate-500/30",
  },
  SCHEDULED: {
    label: "Scheduled",
    className:
      "bg-blue-500/10 text-blue-700 dark:text-blue-400 border-blue-500/30",
  },
  RUNNING: {
    label: "Running",
    className:
      "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/30",
  },
  PAUSED: {
    label: "Paused",
    className:
      "bg-amber-500/10 text-amber-700 dark:text-amber-400 border-amber-500/30",
  },
  COMPLETED: {
    label: "Completed",
    className:
      "bg-teal-500/10 text-teal-700 dark:text-teal-400 border-teal-500/30",
  },
  FAILED: {
    label: "Failed",
    className:
      "bg-red-500/10 text-red-700 dark:text-red-400 border-red-500/30",
  },
  ARCHIVED: {
    label: "Archived",
    className:
      "bg-gray-500/10 text-gray-700 dark:text-gray-400 border-gray-500/30",
  },
};

/**
 * Text + tinted badge for a backend CampaignStatus value.
 */
export function CampaignStatusBadge({
  status,
}: {
  status: CampaignStatus;
}) {
  const meta = STATUS_META[status] ?? {
    label: status,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}