"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { toast } from "sonner";
import {
  ArrowLeftIcon,
  CalendarIcon,
  CopyIcon,
  HashIcon,
  InfoIcon,
  PencilIcon,
  PlayIcon,
  RocketIcon,
  Trash2Icon,
  TriangleAlertIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { EmptyState, QueryErrorState } from "@/components/common/query-state";
import { formatDateTime } from "@/lib/format";
import { CampaignStatusBadge } from "@/components/common/campaign-status-badge";
import { CampaignTypeBadge } from "@/components/common/campaign-type-badge";
import { CampaignRunModeBadge } from "@/components/common/campaign-run-mode-badge";
import { ContentModeBadge } from "@/components/common/content-mode-badge";
import {
  CAMPAIGN_EXECUTION_STATUS_LABEL,
  CampaignExecutionStatusBadge,
} from "@/components/common/campaign-execution-status-badge";
import {
  EXECUTION_POLL_MS,
  IN_FLIGHT_EXECUTION_CONFLICT_MESSAGE,
  findInFlightExecution,
  shouldPollExecutionList,
} from "@/lib/domain/campaign-execution";
import {
  getCampaign,
  campaignsKeys,
  getCampaignReadiness,
  listCampaignExecutions,
  executeCampaign,
} from "@/lib/api/campaigns";
import { toApiError } from "@/lib/api/error";
import { ChangeStatusDialog } from "@/components/campaigns/change-status-dialog";
import { CloneCampaignDialog } from "@/components/campaigns/clone-campaign-dialog";
import { EditCampaignDialog } from "@/components/campaigns/edit-campaign-dialog";
import { DeleteCampaignDialog } from "@/components/campaigns/delete-campaign-dialog";
import { CampaignReadinessPanel } from "@/components/campaigns/campaign-readiness-panel";
import {
  CampaignIntegrationCard,
  CampaignRetryPolicyCard,
  CampaignScheduleCard,
  CampaignTypeConfigCard,
} from "@/components/campaigns/campaign-config-cards";
import {
  canPerformCampaignAction,
  requiresTargetTenant,
} from "@/lib/auth/campaign-gates";
import {
  CAMPAIGN_STATUS_DESCRIPTION,
  availableTransitions,
  isEditable,
} from "@/lib/domain/campaign-lifecycle";
import { useCan } from "@/lib/auth/use-can";
import { useOperatingContext } from "@/lib/auth/operating-context";

interface CampaignDetailViewProps {
  campaignId: string;
}

/**
 * F5 CORRECTED A REAL DUPLICATION HERE. This component used to declare its own
 * private `EXECUTION_POLL_MS = 5_000` and its own
 * `DISPATCHABLE_EXECUTION_STATUSES` copy, while `execution-detail-view.tsx` and
 * `execution-attempts-view.tsx` each declared their own too.
 * `@/lib/domain/campaign-execution` was introduced as the single home for the
 * poll model, but no production component imported it — so the "single home" was
 * enforced only by a test that exercised the module directly, and the three live
 * pollers could drift from the contract without any test noticing. All three now
 * import from it.
 *
 * The interval is still derived from the engine's tick rather than chosen: the
 * orchestrator runs `@Scheduled(fixedDelay = 30000)`, and `EXECUTION_POLL_MS` is
 * a sixth of that, so a transition is visible within one poll without polling
 * ten times per engine cycle.
 */

export function CampaignDetailView({ campaignId }: CampaignDetailViewProps) {
  const [statusDialogOpen, setStatusDialogOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const queryClient = useQueryClient();
  const { user } = useCan();
  const { scope } = useOperatingContext();

  const query = useQuery({
    queryKey: campaignsKeys.detail(campaignId),
    queryFn: () => getCampaign(campaignId),
  });

  // Readiness is only meaningful once the campaign itself has loaded, and it is
  // a separate request: `CampaignReadinessService` re-resolves every reference.
  const readinessQuery = useQuery({
    queryKey: campaignsKeys.readiness(campaignId),
    queryFn: () => getCampaignReadiness(campaignId),
    enabled: query.isSuccess,
  });

  /**
   * F4 CORRECTED THIS MID-PHASE. The poll was removed here on the grounds that
   * no execution engine existed — the `CampaignExecutionService` only wrote a
   * REQUESTED row, and the controller said execution "is performed by the future
   * execution engine".
   *
   * That is no longer true. VERIFIED against the backend as it stands now:
   * `CampaignExecutionOrchestrator` is a `@Service` with
   * `@Scheduled(fixedDelay = 30000) scheduledTick()`, `@EnableScheduling` is on
   * `ObdApplication`, and the tick starts REQUESTED executions, dials due
   * attempts and settles RUNNING ones — writing REQUESTED -> RUNNING and
   * RUNNING -> COMPLETED | FAILED. `OutboundDialService.processDueAttempts()` is
   * real dialling.
   *
   * So execution status changes on its own about once every 30 s and the list
   * must follow it. The guard (`shouldPollExecutionList`) and the interval both
   * come from `@/lib/domain/campaign-execution`; the poll stops once no
   * execution is dispatchable any more.
   */
  const executionsQuery = useQuery({
    queryKey: campaignsKeys.executions(campaignId),
    queryFn: () => listCampaignExecutions(campaignId),
    enabled: query.isSuccess,
    refetchInterval: (current) => {
      const rows = current.state.data;
      if (!rows || rows.length === 0) return false;
      return shouldPollExecutionList(rows.map((row) => row.status))
        ? EXECUTION_POLL_MS
        : false;
    },
  });

  const campaign = query.data;

  const canRead = canPerformCampaignAction(user, "read");
  const canWrite = canPerformCampaignAction(user, "write");
  const canExecute = canPerformCampaignAction(user, "execute");
  const transitions = campaign ? availableTransitions(campaign.status) : [];
  const editable = campaign ? isEditable(campaign.status) : false;

  /**
   * VERIFIED `CampaignExecutionService.execute`: it calls readiness first and
   * throws `BUSINESS_RULE_VIOLATION` (**422**, not 400 and not 409) listing
   * every blocking reason. Offering the button while readiness is known-false is
   * offering a guaranteed failure, so it is disabled with the reason shown.
   *
   * F4.1: this is only a PRE-EXECUTION gate, and passing it does NOT guarantee
   * the execution succeeds. The engine re-evaluates readiness on its own tick
   * and will fail an execution whose resources degraded in between — see
   * `doStartExecution` and `isDeferredRatherThanFailed`. The toast says so
   * rather than implying a completed run.
   */
  const ready = readinessQuery.data?.ready;
  const executeDisabledReason =
    ready === false
      ? "This campaign is not ready to execute. Resolve the reasons above first."
      : null;

  /**
   * F5 ADDED THIS — the backend has a one-in-flight-execution rule the UI was
   * ignoring entirely.
   *
   * VERIFIED `CampaignExecutionService.execute`:
   *
   * ```java
   * if (executionRepository.existsActiveByCampaignId(campaignId)) {
   *     throw new BusinessException(BUSINESS_RULE_VIOLATION,
   *         "Campaign already has an execution in progress. Wait for it to "
   *         + "reach a terminal state, or pause it, before starting another.");
   * }
   * ```
   *
   * `existsActiveByCampaignId` is a native query restricting to
   * `CampaignExecutionStatus.REQUESTED` and `RUNNING` — i.e. exactly
   * `DISPATCHABLE`. So at most one execution per campaign may be in flight, and
   * a second create request is a **422** ("business rule violation"), not a 409
   * conflict. `CommonErrorCode.BUSINESS_RULE_VIOLATION` maps to
   * `HttpStatus.UNPROCESSABLE_CONTENT`.
   *
   * Two things follow, and both are honoured below:
   *
   *  1. The control is disabled while an execution is in flight, with the
   *     in-flight one named. This is UX protection against a guaranteed 422 —
   *     it grants nothing, and the backend still re-checks under a row lock
   *     (`lockCampaignRow`, `FOR UPDATE`), so two racing creators serialise
   *     server-side regardless.
   *  2. The 422 is still handled if it happens, because a client cannot enforce
   *     uniqueness. `onError` shows the backend's own message rather than
   *     inventing one, and re-reads the execution list — which is the only way
   *     to learn that a racing creator won.
   */
  const inFlightExecution = findInFlightExecution(executionsQuery.data);
  const inFlightReason = inFlightExecution
    ? `${IN_FLIGHT_EXECUTION_CONFLICT_MESSAGE} This one is ${CAMPAIGN_EXECUTION_STATUS_LABEL[inFlightExecution.status].toLowerCase()}, requested ${formatDateTime(inFlightExecution.requestedAt)}.`
    : null;
  const executeBlockedReason = executeDisabledReason ?? inFlightReason;

  const executeMutation = useMutation({
    mutationFn: () => executeCampaign(campaignId, { idempotencyKey: crypto.randomUUID() }),
    onSuccess: async () => {
      toast.success("Execution requested", {
        description:
          "The engine picks it up on its next 30-second cycle. It is requested, not running — the engine re-checks readiness then, so a request can still fail if something changed in between.",
      });
      await queryClient.invalidateQueries({ queryKey: campaignsKeys.executions(campaignId) });
    },
    onError: async (error: unknown) => {
      const apiError = toApiError(error);
      toast.error(apiError.message, {
        description: apiError.requestId ? `Request ${apiError.requestId}` : undefined,
      });
      // F5: a 422 here can be the one-in-flight rule (a racing creator, or an
      // execution this client could not see). Re-reading the list is the only
      // way to learn which, so do it on error rather than only on success.
      if (apiError.status === 422) {
        await queryClient.invalidateQueries({
          queryKey: campaignsKeys.executions(campaignId),
        });
      }
    },
  });

  if (query.isPending) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <h1 className="text-3xl font-bold tracking-tight">Campaign</h1>
        <TableSkeleton columns={2} rows={6} />
      </div>
    );
  }

  // F4: the F1 view collapsed "no data" into a "Not found" card, so a 403 and a
  // 500 rendered as "Campaign not found or access denied". `QueryErrorState`
  // distinguishes them, and its 404 wording respects the backend's deliberate
  // scope-cloaking rather than claiming the object does not exist.
  if (query.isError) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <Button variant="ghost" size="icon" asChild>
          <Link href="/campaigns" aria-label="Back to campaigns">
            <ArrowLeftIcon className="h-4 w-4" />
          </Link>
        </Button>
        <h1 className="text-3xl font-bold tracking-tight">Campaign</h1>
        {!canRead ? (
          <Alert>
            <TriangleAlertIcon aria-hidden="true" />
            <AlertTitle>You do not have permission to view campaigns</AlertTitle>
            <AlertDescription>
              Ask an administrator for the campaign view capability.
            </AlertDescription>
          </Alert>
        ) : (
          <QueryErrorState
            error={query.error}
            entityLabel="this campaign"
            onRetry={() => void query.refetch()}
          />
        )}
      </div>
    );
  }

  if (!campaign) return null;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" asChild>
            <Link href="/campaigns" aria-label="Back to campaigns">
              <ArrowLeftIcon className="h-4 w-4" />
            </Link>
          </Button>
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{campaign.name}</h1>
            <p className="text-muted-foreground text-sm">
              Version {campaign.version} · {CAMPAIGN_STATUS_DESCRIPTION[campaign.status]}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <CampaignStatusBadge status={campaign.status} />
          <CampaignTypeBadge campaignType={campaign.campaignType} />
          <CampaignRunModeBadge runMode={campaign.runMode} />
          {campaign.contentMode ? <ContentModeBadge contentMode={campaign.contentMode} /> : null}
        </div>
      </div>

      {campaign.description ? (
        <Card>
          <CardHeader>
            <CardTitle>Description</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="whitespace-pre-wrap">{campaign.description}</p>
          </CardContent>
        </Card>
      ) : null}

      {/* The four call-site-gated controls. VERIFIED capability split in
          `campaign-gates.ts`: MANAGE for create/update/delete/clone, EXECUTE
          for status and execution, VIEW for reads.

          F4.1: the status summary states the operator-driven nature of campaign
          state explicitly. VERIFIED the only production writer of
          `CampaignEntity.status` is `CampaignService.changeStatus`, so a
          scheduled campaign stays scheduled — the engine advances *executions*,
          not campaigns. Saying so prevents the reasonable but wrong inference
          that the lifecycle will progress on its own. */}
      <Card>
        <CardHeader>
          <CardTitle>Actions</CardTitle>
          <CardDescription>
            {editable
              ? "This campaign is a draft, so its configuration can be changed."
              : `Configuration is locked while this campaign is ${campaign.status}.`}
            {" "}
            Campaign state only changes when you change it here — the execution
            engine advances executions and calls, not the campaign itself.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          {canWrite && editable ? (
            <Button variant="outline" onClick={() => setEditOpen(true)}>
              <PencilIcon className="mr-2 h-4 w-4" />
              Edit
            </Button>
          ) : null}
          {canExecute && transitions.length > 0 ? (
            <Button onClick={() => setStatusDialogOpen(true)}>
              <PlayIcon className="mr-2 h-4 w-4" />
              Change status
            </Button>
          ) : null}
          {canExecute ? (
            <Button
              variant="outline"
              onClick={() => executeMutation.mutate()}
              disabled={
                executeMutation.isPending ||
                executeBlockedReason !== null
              }
              title={executeBlockedReason ?? "Request an execution of this campaign"}
            >
              <RocketIcon className="mr-2 h-4 w-4" />
              {executeMutation.isPending ? "Requesting…" : "Request execution"}
            </Button>
          ) : null}
          {canWrite ? (
            <Button variant="outline" onClick={() => setCloneOpen(true)}>
              <CopyIcon className="mr-2 h-4 w-4" />
              Clone
            </Button>
          ) : null}
          {/* F4: Delete now calls the API. VERIFIED there is no lifecycle gate
              on it — a running or archived campaign can be deleted — so the
              button is not hidden for terminal states. */}
          {canWrite ? (
            <Button variant="destructive" onClick={() => setDeleteOpen(true)}>
              <Trash2Icon className="mr-2 h-4 w-4" />
              Delete
            </Button>
          ) : null}
          <Button
            variant="ghost"
            onClick={() => {
              void navigator.clipboard.writeText(campaign.id);
              toast.success("Campaign id copied");
            }}
          >
            <CopyIcon className="mr-2 h-4 w-4" />
            Copy id
          </Button>
        </CardContent>
        {executeBlockedReason ? (
          <CardContent>
            <p className="text-muted-foreground text-sm">{executeBlockedReason}</p>
          </CardContent>
        ) : null}
      </Card>

      <CampaignReadinessPanel
        readiness={readinessQuery.data}
        isPending={readinessQuery.isPending}
        error={readinessQuery.error}
        onRefresh={() => void readinessQuery.refetch()}
        isFetching={readinessQuery.isFetching}
      />

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <HashIcon className="h-4 w-4" />
              Identity
            </CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="flex flex-col gap-2">
              <Row label="Id" value={campaign.id} mono />
              <Row label="Tenant" value={campaign.tenantId} mono />
              {campaign.clonedFromCampaignId ? (
                <Row label="Cloned from" value={campaign.clonedFromCampaignId} mono />
              ) : null}
              <Row label="Created" value={formatDateTime(campaign.createdAt)} />
              <Row label="Updated" value={formatDateTime(campaign.updatedAt)} />
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <InfoIcon className="h-4 w-4" />
              References
            </CardTitle>
            <CardDescription>
              Every reference must belong to this campaign&apos;s own tenant.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="flex flex-col gap-2">
              <Row label="Contact group" value={campaign.contactGroupId ?? "None"} mono={Boolean(campaign.contactGroupId)} />
              <Row label="DID" value={campaign.didId ?? "None"} mono={Boolean(campaign.didId)} />
              <Row
                label="Daily dial limit"
                value={campaign.dailyDialLimit === null ? `Platform default (3)` : String(campaign.dailyDialLimit)}
              />
              <Row
                label="Daily attempts"
                value={campaign.maxDailyAttempts === null ? "Platform default (10)" : String(campaign.maxDailyAttempts)}
              />
              <Row
                label="Max call duration"
                value={
                  campaign.maxCallDurationSeconds === null
                    ? "Platform default (300s)"
                    : `${campaign.maxCallDurationSeconds}s`
                }
              />
            </dl>
          </CardContent>
        </Card>
      </div>

      {campaign.schedule ? <CampaignScheduleCard schedule={campaign.schedule} /> : (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CalendarIcon className="h-4 w-4" />
              Schedule
            </CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-destructive text-sm font-medium">
              No schedule is configured. A schedule is required before this
              campaign can be scheduled, and a campaign without one is
              permanently unready.
            </p>
          </CardContent>
        </Card>
      )}

      <CampaignTypeConfigCard
        campaignType={campaign.campaignType}
        typeConfig={campaign.typeConfig}
        contentMode={campaign.contentMode}
        audioAssetId={campaign.audioAssetId}
        ttsTemplateId={campaign.ttsTemplateId}
        callOnWhitelistNumbers={campaign.callOnWhitelistNumbers}
      />

      {campaign.retryPolicy ? (
        <CampaignRetryPolicyCard retryPolicy={campaign.retryPolicy} />
      ) : null}

      {campaign.integrationConfig ? (
        <CampaignIntegrationCard integrationConfig={campaign.integrationConfig} />
      ) : null}

      {/* Executions. F4 removed the `typeConfig` JSON `<pre>` that rendered the
          frozen snapshot's contents — there is no endpoint that returns them, so
          the snapshot is only ever evidenced by its id. */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RocketIcon className="h-4 w-4" />
            Executions
          </CardTitle>
          <CardDescription>
            Each execution owns an immutable configuration snapshot frozen when it
            was requested, so later edits to this campaign cannot change what it
            dials. The snapshot itself is not readable through any endpoint — its
            id is the only evidence. Status advances on the platform&apos;s own
            30-second cycle: a requested execution becomes running when the engine
            starts it, and settles once every attempt has finished.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {executionsQuery.isPending ? (
            <TableSkeleton columns={4} rows={3} />
          ) : executionsQuery.isError ? (
            <QueryErrorState
              error={executionsQuery.error}
              entityLabel="executions"
              onRetry={() => void executionsQuery.refetch()}
            />
          ) : !executionsQuery.data || executionsQuery.data.length === 0 ? (
            <EmptyState
              icon={RocketIcon}
              title="No executions yet"
              description="Request one when the readiness panel reports this campaign as ready."
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-xs">
                  <tr>
                    <th scope="col" className="p-2 text-left">Status</th>
                    <th scope="col" className="p-2 text-left">Requested</th>
                    <th scope="col" className="p-2 text-left">Snapshot</th>
                    <th scope="col" className="p-2 text-left">Idempotency key</th>
                    <th scope="col" className="p-2 text-right">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {executionsQuery.data.map((execution) => (
                    <tr key={execution.id} className="border-t">
                      <td className="p-2">
                        <CampaignExecutionStatusBadge status={execution.status} />
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        {formatDateTime(execution.requestedAt)}
                      </td>
                      <td className="text-muted-foreground p-2 font-mono text-xs break-all">
                        {execution.configurationSnapshotId}
                      </td>
                      <td className="text-muted-foreground p-2 font-mono text-xs break-all">
                        {execution.idempotencyKey ?? "—"}
                      </td>
                      <td className="p-2 text-right">
                        <Button variant="ghost" size="sm" asChild>
                          <Link href={`/campaigns/${campaignId}/executions/${execution.id}`}>
                            View
                          </Link>
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      <p className="text-muted-foreground text-xs">
        Showing scope:{" "}
        {requiresTargetTenant(scope) ? "organization hierarchy" : "your tenant"}.
        Campaigns outside your boundary are reported as not found, by design.
      </p>

      <ChangeStatusDialog
        campaign={campaign}
        open={statusDialogOpen}
        onOpenChange={setStatusDialogOpen}
      />
      <CloneCampaignDialog
        campaign={campaign}
        open={cloneOpen}
        onOpenChange={setCloneOpen}
      />
      <EditCampaignDialog
        campaign={campaign}
        open={editOpen}
        onOpenChange={setEditOpen}
      />
      <DeleteCampaignDialog
        campaign={campaign}
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
      />
    </div>
  );
}

function Row({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:justify-between sm:gap-4">
      <dt className="text-muted-foreground text-sm">{label}</dt>
      <dd className={`text-sm break-all ${mono ? "font-mono" : ""}`}>{value}</dd>
    </div>
  );
}
