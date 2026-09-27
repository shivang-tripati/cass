import { Badge } from "@/components/ui/badge";
import type { CampaignRunMode } from "@/lib/api/contracts";

const RUN_MODE_META: Record<
  CampaignRunMode,
  { label: string; className: string }
> = {
  ONE_TIME: {
    label: "One Time",
    className:
      "bg-slate-500/10 text-slate-700 dark:text-slate-400 border-slate-500/30",
  },
  RECURRING: {
    label: "Recurring",
    className:
      "bg-purple-500/10 text-purple-700 dark:text-purple-400 border-purple-500/30",
  },
};

/**
 * Text + tinted badge for a backend CampaignRunMode value.
 */
export function CampaignRunModeBadge({
  runMode,
}: {
  runMode: CampaignRunMode;
}) {
  const meta = RUN_MODE_META[runMode] ?? {
    label: runMode,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}