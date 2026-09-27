"use client";
import { useState } from "react";
import { useQuery, useQueryClient, useMutation } from "@tanstack/react-query";
import Link from "next/link";
import { toast } from "sonner";
import {
  ArrowLeftIcon,
  CalendarIcon,
  CopyIcon,
  EditIcon,
  GlobeIcon,
  HashIcon,
  InfoIcon,
  PlayIcon,
  RepeatIcon,
  Trash2Icon,
  TypeIcon,
  RefreshCwIcon,
  RocketIcon,
  CheckCircleIcon,
  XCircleIcon,
  ClockIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import type { ScheduleConfig, RetryPolicyConfig } from "@/lib/api/contracts";
import { formatDateTime } from "@/lib/format";
import {
  CampaignStatusBadge,
} from "@/components/common/campaign-status-badge";
import {
  CampaignTypeBadge,
} from "@/components/common/campaign-type-badge";
import {
  CampaignRunModeBadge,
} from "@/components/common/campaign-run-mode-badge";
import {
  ContentModeBadge,
} from "@/components/common/content-mode-badge";
import { getCampaign, campaignsKeys, CAMPAIGN_LEGAL_TRANSITIONS, getCampaignReadiness, listCampaignExecutions, executeCampaign } from "@/lib/api/campaigns";
import { toApiError } from "@/lib/api/error";
import { ChangeStatusDialog } from "@/components/campaigns/change-status-dialog";

interface CampaignDetailViewProps {
  campaignId: string;
}

export function CampaignDetailView({ campaignId }: CampaignDetailViewProps) {
  const [statusDialogOpen, setStatusDialogOpen] = useState(false);
  const qc = useQueryClient();

  const query = useQuery({
    queryKey: campaignsKeys.detail(campaignId),
    queryFn: () => getCampaign(campaignId),
  });

  const readinessQ = useQuery({
    queryKey: campaignsKeys.readiness(campaignId),
    queryFn: () => getCampaignReadiness(campaignId),
    enabled: !!query.data,
  });

  const executionsQ = useQuery({
    queryKey: campaignsKeys.executions(campaignId),
    queryFn: () => listCampaignExecutions(campaignId),
    enabled: !!query.data,
    refetchInterval: (q) => {
      const data = q.state.data as import("@/lib/api/contracts").CampaignExecutionResponse[] | undefined;
      const hasActive = data?.some((e) => e.status === "REQUESTED" || e.status === "RUNNING");
      return hasActive ? 4000 : false;
    },
  });

  const execMut = useMutation({
    mutationFn: async () => {
      const key = (typeof crypto !== "undefined" && "randomUUID" in crypto) ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2,8)}`;
      return executeCampaign(campaignId, { idempotencyKey: key });
    },
    onSuccess: async (data) => {
      toast.success("Execution requested", { description: `ID ${data.id} — ${data.status}` });
      await Promise.all([
        qc.invalidateQueries({ queryKey: campaignsKeys.executions(campaignId) }),
        qc.invalidateQueries({ queryKey: campaignsKeys.readiness(campaignId) }),
        qc.invalidateQueries({ queryKey: campaignsKeys.detail(campaignId) }),
      ]);
    },
    onError: (e: unknown) => {
      const ae = toApiError(e);
      toast.error(ae.message, { description: ae.requestId ? `Request ${ae.requestId}` : undefined });
    },
  });

  const campaign = query.data;

  if (query.isPending) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Campaign</h1>
            <p className="text-muted-foreground">Loading…</p>
          </div>
        </div>
        <Card>
          <CardContent className="py-12">
            <div className="flex items-center justify-center gap-3">
              <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
              <span className="text-muted-foreground">Loading campaign details…</span>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!campaign) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Campaign</h1>
            <p className="text-muted-foreground">Not found</p>
          </div>
        </div>
        <Card>
          <CardContent className="py-12 text-center">
            <p className="text-muted-foreground">Campaign not found or access denied.</p>
          </CardContent>
        </Card>
      </div>
    );
  }

  const legalTransitions = CAMPAIGN_LEGAL_TRANSITIONS[campaign.status] ?? [];
  const isTerminal = ["COMPLETED", "FAILED", "ARCHIVED"].includes(campaign.status);
  const canTransition = legalTransitions.length > 0 && !isTerminal;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" asChild>
            <Link href="/campaigns">
              <ArrowLeftIcon className="h-4 w-4" />
            </Link>
          </Button>
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{campaign.name}</h1>
            <p className="text-muted-foreground">Version {campaign.version}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <CampaignStatusBadge status={campaign.status} />
          <CampaignTypeBadge campaignType={campaign.campaignType} />
          <CampaignRunModeBadge runMode={campaign.runMode} />
          {campaign.contentMode && <ContentModeBadge contentMode={campaign.contentMode} />}
        </div>
      </div>

      {campaign.description && (
        <Card>
          <CardHeader>
            <CardTitle>Description</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="whitespace-pre-wrap">{campaign.description}</p>
          </CardContent>
        </Card>
      )}

      {/* Readiness — backend truth */}
      <Card aria-live="polite">
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">{readinessQ.isPending ? <ClockIcon className="h-4 w-4 animate-pulse" /> : readinessQ.data?.ready ? <CheckCircleIcon className="h-4 w-4 text-emerald-600" /> : <XCircleIcon className="h-4 w-4 text-amber-600" />} Readiness</CardTitle>
            <CardDescription>{readinessQ.isPending ? "Checking…" : readinessQ.data?.ready ? "Ready for execution" : "Blocked — resolve reasons below"}</CardDescription>
          </div>
          <Button variant="ghost" size="sm" onClick={() => readinessQ.refetch()} disabled={readinessQ.isFetching}><RefreshCwIcon className={readinessQ.isFetching ? "animate-spin h-4 w-4" : "h-4 w-4"} /></Button>
        </CardHeader>
        <CardContent>
          {readinessQ.isPending ? <p className="text-sm text-muted-foreground">Loading readiness…</p> : readinessQ.error ? <Alert variant="destructive"><AlertTitle>{toApiError(readinessQ.error).message}</AlertTitle></Alert> : readinessQ.data ? (
            readinessQ.data.ready ? (
              <div className="flex items-center gap-2"><Badge className="bg-emerald-600">Ready</Badge><span className="text-sm">Campaign is SCHEDULED/RUNNING and all references approved.</span></div>
            ) : (
              <div className="space-y-2">
                <Badge variant="destructive">Blocked</Badge>
                <ul className="list-disc pl-5 space-y-1">
                  {readinessQ.data.reasons.map((r) => <li key={r.code} className="text-sm"><span className="font-mono text-xs">{r.code}</span>: {r.message}</li>)}
                </ul>
              </div>
            )
          ) : null}
        </CardContent>
      </Card>

      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <HashIcon className="h-4 w-4" />
              Identity
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <dl className="space-y-2">
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">ID</dt>
                <dd className="text-sm font-mono text-right break-all">{campaign.id}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">Tenant</dt>
                <dd className="text-sm font-mono text-right break-all">{campaign.tenantId}</dd>
              </div>
              {campaign.clonedFromCampaignId && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">Cloned From</dt>
                  <dd className="text-sm font-mono text-right break-all">{campaign.clonedFromCampaignId}</dd>
                </div>
              )}
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CalendarIcon className="h-4 w-4" />
              Timestamps
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <dl className="space-y-2">
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">Created</dt>
                <dd className="text-sm text-right whitespace-nowrap">{formatDateTime(campaign.createdAt)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">Updated</dt>
                <dd className="text-sm text-right whitespace-nowrap">{formatDateTime(campaign.updatedAt)}</dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <InfoIcon className="h-4 w-4" />
              Content
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <dl className="space-y-2">
              {campaign.contentMode && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">Content Mode</dt>
                  <dd className="text-sm text-right">
                    <ContentModeBadge contentMode={campaign.contentMode} />
                  </dd>
                </div>
              )}
              {campaign.audioAssetId && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">Audio Asset</dt>
                  <dd className="text-sm font-mono text-right break-all">{campaign.audioAssetId}</dd>
                </div>
              )}
              {campaign.ttsTemplateId && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">TTS Template</dt>
                  <dd className="text-sm font-mono text-right break-all">{campaign.ttsTemplateId}</dd>
                </div>
              )}
              {campaign.contactGroupId && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">Contact Group</dt>
                  <dd className="text-sm font-mono text-right break-all">{campaign.contactGroupId}</dd>
                </div>
              )}
              {campaign.didId && (
                <div className="flex justify-between">
                  <dt className="text-sm text-muted-foreground">DID</dt>
                  <dd className="text-sm font-mono text-right break-all">{campaign.didId}</dd>
                </div>
              )}
            </dl>
          </CardContent>
        </Card>
      </div>

      {campaign.schedule && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CalendarIcon className="h-4 w-4" />
              Schedule
            </CardTitle>
          </CardHeader>
          <CardContent>
            <ScheduleDisplay schedule={campaign.schedule} />
          </CardContent>
        </Card>
      )}

      {campaign.retryPolicy && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <RepeatIcon className="h-4 w-4" />
              Retry Policy
            </CardTitle>
          </CardHeader>
          <CardContent>
            <RetryPolicyDisplay retryPolicy={campaign.retryPolicy} />
          </CardContent>
        </Card>
      )}

      {campaign.typeConfig && Object.keys(campaign.typeConfig).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <TypeIcon className="h-4 w-4" />
              Type Configuration
            </CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="text-sm bg-muted p-4 rounded overflow-auto max-h-64">
              {JSON.stringify(campaign.typeConfig, null, 2)}
            </pre>
          </CardContent>
        </Card>
      )}

      {campaign.integrationConfig && Object.keys(campaign.integrationConfig).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <GlobeIcon className="h-4 w-4" />
              Integration Configuration
            </CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="text-sm bg-muted p-4 rounded overflow-auto max-h-64">
              {JSON.stringify(campaign.integrationConfig, null, 2)}
            </pre>
          </CardContent>
        </Card>
      )}

      {/* Executions */}
      <Card>
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2"><RocketIcon className="h-4 w-4" /> Executions</CardTitle>
            <CardDescription>Ordered by requested time (newest first). Polls while REQUESTED/RUNNING.</CardDescription>
          </div>
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" onClick={() => executionsQ.refetch()} disabled={executionsQ.isFetching}><RefreshCwIcon className={executionsQ.isFetching ? "animate-spin h-4 w-4" : "h-4 w-4"} /></Button>
            <Button size="sm" disabled={execMut.isPending || readinessQ.data?.ready === false} onClick={() => execMut.mutate()} title={readinessQ.data?.ready === false ? "Blocked — see readiness reasons" : "Execute campaign"}>
              {execMut.isPending ? "Requesting…" : <><RocketIcon className="mr-1 h-4 w-4" /> Execute</>}
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {execMut.isError && <Alert variant="destructive" className="mb-3"><AlertTitle>{toApiError(execMut.error).message}</AlertTitle><AlertDescription>{toApiError(execMut.error).requestId ? `Request ${toApiError(execMut.error).requestId}` : null}</AlertDescription></Alert>}
          {executionsQ.isPending ? <p className="text-sm text-muted-foreground">Loading executions…</p> : executionsQ.error ? <Alert variant="destructive"><AlertTitle>{toApiError(executionsQ.error).message}</AlertTitle></Alert> : !executionsQ.data?.length ? <p className="text-sm text-muted-foreground">No executions yet. Execute when readiness is green.</p> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-xs"><tr><th className="text-left p-2">ID</th><th className="text-left p-2">Status</th><th className="text-left p-2">Requested</th><th className="text-left p-2">Requested by</th><th className="text-left p-2">Idempotency</th><th className="text-left p-2"></th></tr></thead>
                <tbody>
                  {executionsQ.data.map((ex) => (
                    <tr key={ex.id} className="border-t">
                      <td className="p-2 font-mono text-xs break-all">{ex.id.slice(0,8)}…</td>
                      <td className="p-2"><ExecutionBadge status={ex.status} /></td>
                      <td className="p-2 whitespace-nowrap">{formatDateTime(ex.requestedAt)}</td>
                      <td className="p-2 font-mono text-xs">{ex.requestedBy.slice(0,8)}…</td>
                      <td className="p-2 font-mono text-xs truncate max-w-[12ch]">{ex.idempotencyKey ?? "—"}</td>
                      <td className="p-2 text-right"><Button variant="ghost" size="sm" asChild><Link href={`/campaigns/${campaignId}/executions/${ex.id}`}>View</Link></Button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {executionsQ.data && executionsQ.data.some(e=> e.status==="REQUESTED"||e.status==="RUNNING") && <p className="text-xs text-muted-foreground mt-2" aria-live="polite">Polling while non-terminal…</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Actions</CardTitle>
          <CardDescription>
            Available operations for this campaign
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button variant="outline" asChild>
            <Link href={`/campaigns/${campaign.id}/edit`}>
              <EditIcon className="mr-2 h-4 w-4" />
              Edit
            </Link>
          </Button>
          <Button variant="outline" onClick={() => navigator.clipboard.writeText(campaign.id)}>
            <CopyIcon className="mr-2 h-4 w-4" />
            Copy ID
          </Button>
          {canTransition && (
            <Button
              variant="default"
              onClick={() => setStatusDialogOpen(true)}
            >
              <PlayIcon className="mr-2 h-4 w-4" />
              Change Status
            </Button>
          )}
          <Button variant="outline" onClick={() => setStatusDialogOpen(true)}>
            <CopyIcon className="mr-2 h-4 w-4" />
            Clone
          </Button>
          <Button variant="destructive" onClick={() => confirmDelete(campaign.id)}>
            <Trash2Icon className="mr-2 h-4 w-4" />
            Delete
          </Button>
        </CardContent>
      </Card>

      <ChangeStatusDialog
        campaign={campaign}
        open={statusDialogOpen}
        onOpenChange={setStatusDialogOpen}
      />
    </div>
  );
}

function ScheduleDisplay({ schedule }: { schedule: ScheduleConfig }) {
  return (
    <dl className="space-y-3">
      {schedule.startDate && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Start Date</dt>
          <dd className="text-sm font-medium">{schedule.startDate}</dd>
        </div>
      )}
      {schedule.endDate && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">End Date</dt>
          <dd className="text-sm font-medium">{schedule.endDate}</dd>
        </div>
      )}
      {schedule.startTime && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Start Time</dt>
          <dd className="text-sm font-medium">{schedule.startTime}</dd>
        </div>
      )}
      {schedule.endTime && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">End Time</dt>
          <dd className="text-sm font-medium">{schedule.endTime}</dd>
        </div>
      )}
      {schedule.timezone && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Timezone</dt>
          <dd className="text-sm font-medium">{schedule.timezone}</dd>
        </div>
      )}
      {schedule.allowedDaysOfWeek && schedule.allowedDaysOfWeek.length > 0 && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Allowed Days</dt>
          <dd className="text-sm font-medium">{schedule.allowedDaysOfWeek.join(", ")}</dd>
        </div>
      )}
      {schedule.holidayCalendarId && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Holiday Calendar</dt>
          <dd className="text-sm font-mono break-all">{schedule.holidayCalendarId}</dd>
        </div>
      )}
    </dl>
  );
}

function RetryPolicyDisplay({ retryPolicy }: { retryPolicy: RetryPolicyConfig }) {
  return (
    <dl className="space-y-3">
      <div className="flex justify-between">
        <dt className="text-sm text-muted-foreground">Max Attempts</dt>
        <dd className="text-sm font-medium">{retryPolicy.maxAttempts}</dd>
      </div>
      {retryPolicy.intervalSeconds && (
        <div className="flex justify-between">
          <dt className="text-sm text-muted-foreground">Interval</dt>
          <dd className="text-sm font-medium">
            {retryPolicy.intervalSeconds} seconds
          </dd>
        </div>
      )}
      <div className="flex justify-between">
        <dt className="text-sm text-muted-foreground">Strategy</dt>
        <dd className="text-sm font-medium">{retryPolicy.strategy}</dd>
      </div>
    </dl>
  );
}

function ExecutionBadge({ status }: { status: string }){
  const map: Record<string,string> = {
    REQUESTED: "bg-amber-500/10 text-amber-700 border-amber-500/30",
    RUNNING: "bg-blue-500/10 text-blue-700 border-blue-500/30",
    COMPLETED: "bg-emerald-500/10 text-emerald-700 border-emerald-500/30",
    FAILED: "bg-red-500/10 text-red-700 border-red-500/30",
    CANCELLED: "bg-gray-500/10 text-gray-700 border-gray-500/30",
  };
  return <Badge variant="outline" className={map[status] ?? ""}>{status}</Badge>;
}

function confirmDelete(campaignId: string) {
  if (confirm("Are you sure you want to delete this campaign? This action cannot be undone.")) {
    // In a real implementation, this would call the delete API
    // For now, just reload - the actual delete would be implemented when the API is available
    window.location.reload();
  }
  // campaignId is used in the actual implementation
  void campaignId;
}