import { Badge } from "@/components/ui/badge";
import type { LifecycleStatus } from "@/lib/api/types";

const STATUS_META: Record<
  LifecycleStatus,
  { label: string; className: string }
> = {
  ACTIVE: {
    label: "Active",
    className:
      "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/30",
  },
  SUSPENDED: {
    label: "Suspended",
    className:
      "bg-red-500/10 text-red-700 dark:text-red-400 border-red-500/30",
  },
};

/**
 * Text + tinted badge for a backend LifecycleStatus value. Shared by every
 * module that exposes the platform's common lifecycle enum.
 */
export function LifecycleStatusBadge({
  status,
}: {
  status: LifecycleStatus;
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
