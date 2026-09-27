import { Badge } from "@/components/ui/badge";
import type { AudioAssetStatus } from "@/lib/api/contracts";

const META: Record<AudioAssetStatus, { label: string; className: string }> = {
  PENDING_APPROVAL: { label: "Pending", className: "bg-amber-500/10 text-amber-700 border-amber-500/30" },
  APPROVED: { label: "Approved", className: "bg-emerald-500/10 text-emerald-700 border-emerald-500/30" },
  REJECTED: { label: "Rejected", className: "bg-red-500/10 text-red-700 border-red-500/30" },
};

export function AudioAssetStatusBadge({ status }: { status: AudioAssetStatus }) {
  const m = META[status] ?? { label: status, className: "" };
  return <Badge variant="outline" className={m.className}>{m.label}</Badge>;
}
