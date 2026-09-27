import { Badge } from "@/components/ui/badge";
import type { AllocationState } from "@/lib/api/contracts";

const ALLOCATION_META: Record<
  AllocationState,
  { label: string; className: string }
> = {
  AVAILABLE: {
    label: "Available",
    className:
      "bg-blue-500/10 text-blue-700 dark:text-blue-400 border-blue-500/30",
  },
  ASSIGNED: {
    label: "Assigned",
    className:
      "bg-purple-500/10 text-purple-700 dark:text-purple-400 border-purple-500/30",
  },
};

/**
 * Text + tinted badge for a backend AllocationState value.
 */
export function AllocationStateBadge({
  allocationState,
}: {
  allocationState: AllocationState;
}) {
  const meta = ALLOCATION_META[allocationState] ?? {
    label: allocationState,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}