"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { ArrowLeftIcon, ClockIcon, PhoneCallIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { QueryErrorState } from "@/components/common/query-state";
import { campaignsKeys, getCampaignExecution } from "@/lib/api/campaigns";
import { formatDateTime } from "@/lib/format";
import { CampaignExecutionStatusBadge } from "@/components/common/campaign-execution-status-badge";
import {
  EXECUTION_POLL_MS,
  executionStartState,
  shouldPollExecution,
} from "@/lib/domain/campaign-execution";

/**
 * One campaign execution.
 *
 * ## Polling: the engine's tick, not an arbitrary interval
 *
 * The engine ticks every 30 s (`CampaignExecutionOrchestrator`'s
 * `@Scheduled(fixedDelay = 30000) scheduledTick()`), starting REQUESTED
 * executions, dialling due attempts and settling RUNNING ones. So execution
 * status genuinely changes on its own, roughly once per 30 s, and a view that
 * does not poll shows a permanently stale value.
 *
 * `EXECUTION_POLL_MS` and `shouldPollExecution` come from
 * `@/lib/domain/campaign-execution`, which is the single home for that model and
 * derives the interval from the engine tick rather than picking it.
 *
 * F5 CORRECTED A REAL DUPLICATION HERE. This component used to declare its own
 * private `EXECUTION_POLL_MS = 5_000` and its own `DISPATCHABLE` array, while
 * `campaign-detail-view.tsx` and `execution-attempts-view.tsx` declared a third
 * and fourth set of copies. `campaign-execution.ts` was written as the single
 * home for the poll model and given its own test suite, but no production
 * component imported it — so the "single home" was verified only by tests that
 * exercised it directly. All three components now import from it, which is what
 * makes the guard enforceable: a change to the poll model now reaches the UI.
 *
 * F4.1 re-verified the guard itself: the poll is bounded by `DISPATCHABLE` =
 * `{REQUESTED, RUNNING}`, which is the backend's own `DISPATCHABLE` set rather
 * than the negation of `TERMINAL`. Using the positive form means a future
 * non-terminal status is polled by default instead of silently freezing. React
 * Query stops the interval when the component unmounts, so a closed tab does not
 * keep the request alive.
 */

/**
 * F4.1 — an execution can sit in `REQUESTED` for a long time, and that is not a
 * fault.
 *
 * VERIFIED `doStartExecution` + `isDeferredRatherThanFailed`: when the campaign
 * is not in an executable lifecycle state, the engine logs a deferral and
 * returns **without touching the execution**, so the scheduler retries on its
 * next 30 s tick. A paused or still-draft campaign therefore leaves its
 * requested executions pending indefinitely, and the user resumes them by making
 * the campaign executable again — not by re-requesting.
 *
 * ## F5 — the ambiguity is now RESOLVED by the backend, not guessed at
 *
 * F4.1 recorded that "`REQUESTED` is genuinely ambiguous between 'queued, will
 * start shortly' and 'deferred, waiting on the campaign's state', and this page
 * does not claim to know which." That was B10.
 *
 * The backend has since answered it. VERIFIED `CampaignExecutionResponse`
 * carries a `deferredReason`, derived on read by
 * `CampaignExecutionService.deriveDeferredReason` and never persisted. It is
 * non-null **only** while the status is `REQUESTED` — which is only possible
 * because an execution can only be *created* while its campaign is ready, so the
 * single remaining reason to still be `REQUESTED` is that the campaign has since
 * left the executable set. It is deliberately `null` for RUNNING and for every
 * terminal status, so it can never be mistaken for a second `failureReason`.
 *
 * So this page renders the backend's own answer and, when the field is `null`,
 * says the honest thing: the execution is queued and awaiting the engine. It
 * does NOT infer a reason from the campaign's status, because the backend has
 * already decided what the answer is and a local re-derivation would be a second
 * source of truth that could drift.
 */

export function ExecutionDetailView({
  campaignId,
  executionId,
}: {
  campaignId: string;
  executionId: string;
}) {
  const query = useQuery({
    queryKey: campaignsKeys.execution(campaignId, executionId),
    queryFn: () => getCampaignExecution(campaignId, executionId),
    refetchInterval: (current) => {
      const status = current.state.data?.status;
      return shouldPollExecution(status) ? EXECUTION_POLL_MS : false;
    },
  });

  const backHref = `/campaigns/${campaignId}`;
  const startState = executionStartState(query.data);

  if (query.isPending) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <h1 className="text-2xl font-semibold">Execution</h1>
        <TableSkeleton columns={2} rows={4} />
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="mx-auto max-w-3xl space-y-4">
        <Button asChild variant="ghost">
          <Link href={backHref}>
            <ArrowLeftIcon className="mr-2 h-4 w-4" />
            Back
          </Link>
        </Button>
        <QueryErrorState
          error={query.error}
          entityLabel="this execution"
          onRetry={() => void query.refetch()}
        />
      </div>
    );
  }

  const execution = query.data;

  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button asChild variant="ghost">
          <Link href={backHref}>
            <ArrowLeftIcon className="mr-2 h-4 w-4" />
            Back to campaign
          </Link>
        </Button>
        <Button asChild variant="outline">
          <Link href={`/campaigns/${campaignId}/executions/${executionId}/attempts`}>
            <PhoneCallIcon className="mr-2 h-4 w-4" />
            Call attempts
          </Link>
        </Button>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <h1 className="text-2xl font-semibold">Execution</h1>
        <CampaignExecutionStatusBadge status={execution.status} />
      </div>

      {startState === "DEFERRED_BY_CAMPAIGN_STATE" ? (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <ClockIcon className="h-4 w-4" />
              Waiting to start
            </CardTitle>
            <CardDescription>
              This execution has been accepted but the engine has not started it.
              No calls are being placed yet.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <p className="text-sm font-medium">Held by the campaign&apos;s state</p>
            <p className="text-muted-foreground text-sm">
              {execution.deferredReason}
            </p>
            <p className="text-muted-foreground text-sm">
              The engine re-checks this every 30 seconds and starts the execution
              on its own once the campaign is executable again — nothing further
              is needed here, and requesting another execution is not the fix.
            </p>
          </CardContent>
        </Card>
      ) : startState === "AWAITING_ENGINE" ? (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <ClockIcon className="h-4 w-4" />
              Waiting to start
            </CardTitle>
            <CardDescription>
              This execution has been accepted but the engine has not started it.
              No calls are being placed yet.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-sm">
              Queued for the engine, which picks it up on its next 30-second
              cycle. Nothing is blocking it.
            </p>
          </CardContent>
        </Card>
      ) : null}

      {execution.status === "RUNNING" ? (
        <p className="text-muted-foreground text-sm">
          The engine has started this execution and it still has unfinished work.
          Running does not mean a call is being placed at this exact moment — the
          engine works on its own 30-second cycle and may be waiting for a calling
          window, retry budget, or availability.
        </p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Identity</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="flex flex-col gap-2 text-sm">
            <Row label="Execution id" value={execution.id} mono />
            <Row label="Campaign" value={execution.campaignId} mono />
            <Row label="Tenant" value={execution.tenantId} mono />
            <Row label="Idempotency key" value={execution.idempotencyKey ?? "—"} mono />
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Configuration snapshot</CardTitle>
          <CardDescription>
            Frozen when this execution was requested. It is immutable, so later
            edits to the campaign cannot change what this execution dials — and
            the campaign may only be edited while it is a draft anyway.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p className="font-mono text-sm break-all">
            {execution.configurationSnapshotId}
          </p>
          <p className="text-muted-foreground mt-2 text-sm">
            No endpoint returns the snapshot&apos;s contents, so the id is the
            only available evidence that it exists and is frozen.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Timestamps</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="flex flex-col gap-2 text-sm">
            <Row label="Requested" value={formatDateTime(execution.requestedAt)} />
            <Row label="Requested by" value={execution.requestedBy} mono />
            <Row
              label="Started"
              value={execution.startedAt ? formatDateTime(execution.startedAt) : "Not started"}
            />
            <Row
              label="Completed"
              value={execution.completedAt ? formatDateTime(execution.completedAt) : "Not completed"}
            />
          </dl>
        </CardContent>
      </Card>

      {execution.failureReason ? (
        <Card>
          <CardHeader>
            <CardTitle>Failure reason</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm whitespace-pre-wrap">{execution.failureReason}</p>
          </CardContent>
        </Card>
      ) : null}
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
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={`break-all ${mono ? "font-mono" : ""}`}>{value}</dd>
    </div>
  );
}
