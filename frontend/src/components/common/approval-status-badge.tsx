import { Badge } from "@/components/ui/badge";
import {
  APPROVAL_STATUS_LABEL,
  type ApprovalStatus,
} from "@/lib/domain/approval";

/**
 * One status badge for the three-state approval lifecycle.
 *
 * ## Why this replaces two components
 *
 * `audio-asset-status-badge.tsx` and `tts-template-status-badge.tsx` were
 * byte-equivalent apart from the type name they accepted: same three states,
 * same labels, same colour classes, same fallback. `AudioAssetStatus` and
 * `TtsTemplateStatus` are the same three literals, and `AudioAssetStatus` and
 * `TtsTemplateStatus` are structurally the same enum, so the duplication bought
 * nothing and guaranteed the two domains would eventually disagree.
 *
 * ## Accessibility
 *
 * The status is carried by the **text** in the badge, not by its colour, so it
 * is readable without colour perception and survives a high-contrast or
 * monochrome rendering. The colours are an accent on top of an already
 * complete signal, never the signal itself.
 */
const META: Record<
  ApprovalStatus,
  { className: string; title: string }
> = {
  PENDING_APPROVAL: {
    className: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400",
    title:
      "Waiting for review. Campaigns cannot use this until it is approved.",
  },
  APPROVED: {
    className:
      "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
    title: "Approved. Campaigns can use this.",
  },
  REJECTED: {
    className: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400",
    title:
      "Rejected. Campaigns cannot use this, and campaigns that still reference it will fail to start.",
  },
};

export function ApprovalStatusBadge({
  status,
}: {
  status: ApprovalStatus;
}) {
  const meta = META[status];
  return (
    <Badge
      variant="outline"
      // `title` gives the full sentence on hover; the visible label stays short
      // so a table column is not dominated by it.
      title={meta?.title}
      className={meta?.className}
    >
      {APPROVAL_STATUS_LABEL[status] ?? status}
    </Badge>
  );
}
