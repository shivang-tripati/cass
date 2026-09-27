import { Badge } from "@/components/ui/badge";
import type { DidStatus } from "@/lib/api/contracts";

const STATUS_META: Record<
  DidStatus,
  { label: string; className: string }
> = {
  ACTIVE: {
    label: "Active",
    className:
      "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-500/30",
  },
  INACTIVE: {
    label: "Inactive",
    className:
      "bg-red-500/10 text-red-700 dark:text-red-400 border-red-500/30",
  },
};

/**
 * Text + tinted badge for a backend DidStatus value.
 */
export function DidStatusBadge({
  status,
}: {
  status: DidStatus;
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