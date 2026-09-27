"use client";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { ArrowLeftIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { campaignsKeys, getCampaignExecution } from "@/lib/api/campaigns";
import { toApiError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

const badge: Record<string,string> = {
  REQUESTED: "bg-amber-500/10 text-amber-700 border-amber-500/30",
  RUNNING: "bg-blue-500/10 text-blue-700 border-blue-500/30",
  COMPLETED: "bg-emerald-500/10 text-emerald-700 border-emerald-500/30",
  FAILED: "bg-red-500/10 text-red-700 border-red-500/30",
  CANCELLED: "bg-gray-500/10 text-gray-700 border-gray-500/30",
};

export function ExecutionDetailView({ campaignId, executionId }: { campaignId: string; executionId: string }){
  const q = useQuery({
    queryKey: campaignsKeys.execution(campaignId, executionId),
    queryFn: () => getCampaignExecution(campaignId, executionId),
    refetchInterval: (query) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const s = (query.state.data as any)?.status;
      return s === "REQUESTED" || s === "RUNNING" ? 3000 : false;
    },
  });
  if (q.isPending) return <div className="p-8" aria-busy="true">Loading execution…</div>;
  if (q.error) {
    const ae = toApiError(q.error);
    return <div className="p-8"><Alert variant="destructive"><AlertTitle>{ae.status===403?"No permission":ae.message}</AlertTitle></Alert><Button asChild variant="ghost" className="mt-4"><Link href={`/campaigns/${campaignId}`}><ArrowLeftIcon/> Back</Link></Button></div>;
  }
  const ex = q.data!;
  const isActive = ex.status === "REQUESTED" || ex.status === "RUNNING";
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="flex items-center gap-2">
        <Button variant="ghost" asChild><Link href={`/campaigns/${campaignId}`}><ArrowLeftIcon/> Back to campaign</Link></Button>
        <Button variant="outline" asChild><Link href={`/campaigns/${campaignId}/executions/${executionId}/attempts`}>Call Attempts</Link></Button>
      </div>
      <div className="flex items-center gap-2"><h1 className="text-2xl font-semibold">Execution</h1><Badge variant="outline" className={badge[ex.status] ?? ""}>{ex.status}</Badge>{isActive && <span className="text-xs text-muted-foreground" aria-live="polite">polling…</span>}</div>
      <Card><CardHeader><CardTitle>Identity</CardTitle></CardHeader><CardContent className="text-sm space-y-1">
        <div className="flex justify-between"><span className="text-muted-foreground">Execution ID</span><span className="font-mono break-all">{ex.id}</span></div>
        <div className="flex justify-between"><span className="text-muted-foreground">Campaign</span><span className="font-mono break-all">{ex.campaignId}</span></div>
        <div className="flex justify-between"><span className="text-muted-foreground">Tenant</span><span className="font-mono break-all">{ex.tenantId}</span></div>
        <div className="flex justify-between"><span className="text-muted-foreground">Idempotency</span><span className="font-mono">{ex.idempotencyKey ?? "—"}</span></div>
      </CardContent></Card>
      <Card><CardHeader><CardTitle>Timestamps</CardTitle></CardHeader><CardContent className="text-sm space-y-1">
        <div className="flex justify-between"><span className="text-muted-foreground">Requested</span><span>{formatDateTime(ex.requestedAt)} by {ex.requestedBy.slice(0,8)}…</span></div>
        <div className="flex justify-between"><span className="text-muted-foreground">Started</span><span>{ex.startedAt ? formatDateTime(ex.startedAt) : "—"}</span></div>
        <div className="flex justify-between"><span className="text-muted-foreground">Completed</span><span>{ex.completedAt ? formatDateTime(ex.completedAt) : "—"}</span></div>
      </CardContent></Card>
      {ex.failureReason && <Card><CardHeader><CardTitle>Failure reason</CardTitle></CardHeader><CardContent><p className="text-sm whitespace-pre-wrap">{ex.failureReason}</p></CardContent></Card>}
    </div>
  );
}
