import { Badge } from "@/components/ui/badge";
import type { CampaignType } from "@/lib/api/contracts";

const TYPE_META: Record<
  CampaignType,
  { label: string; className: string }
> = {
  PLAYFILE: {
    label: "Play File",
    className:
      "bg-indigo-500/10 text-indigo-700 dark:text-indigo-400 border-indigo-500/30",
  },
  DTMF: {
    label: "DTMF",
    className:
      "bg-violet-500/10 text-violet-700 dark:text-violet-400 border-violet-500/30",
  },
  CONNECT_BY_AGENT: {
    label: "Connect by Agent",
    className:
      "bg-cyan-500/10 text-cyan-700 dark:text-cyan-400 border-cyan-500/30",
  },
};

/**
 * Text + tinted badge for a backend CampaignType value.
 */
export function CampaignTypeBadge({
  campaignType,
}: {
  campaignType: CampaignType;
}) {
  const meta = TYPE_META[campaignType] ?? {
    label: campaignType,
    className: "",
  };
  return (
    <Badge variant="outline" className={meta.className}>
      {meta.label}
    </Badge>
  );
}