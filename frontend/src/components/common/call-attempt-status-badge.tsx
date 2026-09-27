import { Badge } from "@/components/ui/badge";
import type { CallAttemptStatus } from "@/lib/api/contracts";

const META: Record<
  CallAttemptStatus,
  { label: string; className: string }
> = {
  QUEUED: {
    label: "Queued",
    className:
      "bg-amber-500/10 text-amber-700 dark:text-amber-400 border-amber-500/30",
  },
  IN_PROGRESS: {
    label: "In Progress",
    className:
      "bg-blue-500/10 text-blue-700 dark:text-blue-400 border-blue-500/30",
  },
  COMPLETED: {
    label: "Completed",
    className:
      "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/30",
  },
  FAILED: {
    label: "Failed",
    className:
      "bg-red-500/10 text-red-700 dark:text-red-400 border-red-500/30",
  },
  CANCELLED: {
    label: "Cancelled",
    className:
      "bg-gray-500/10 text-gray-700 dark:text-gray-400 border-gray-500/30",
  },
};

export function CallAttemptStatusBadge({
  status,
}: {
  status: CallAttemptStatus;
}) {
  const meta = META[status] ?? {
    label: status,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}