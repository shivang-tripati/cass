"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { toast } from "sonner";
import { ArrowLeftIcon, PhoneCallIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { EmptyState, QueryErrorState } from "@/components/common/query-state";
import {
  callAttemptsKeys,
  getCallAttempts,
  markAttemptFailed,
  markAttemptInProgress,
} from "@/lib/api/call-attempts";
import { getCampaignExecution, campaignsKeys } from "@/lib/api/campaigns";
import { toApiError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { CallAttemptStatusBadge } from "@/components/common/call-attempt-status-badge";
import {
  EXECUTION_POLL_MS,
  shouldPollAttempts,
} from "@/lib/domain/campaign-execution";
import { canPerformCampaignAction } from "@/lib/auth/campaign-gates";
import { useCan } from "@/lib/auth/use-can";

/**
 * The call attempts belonging to one execution.
 *
 * ## F4 made an orphaned, fully-written surface reachable
 *
 * `call-attempt-table.tsx` and `lib/api/call-attempts.ts` were both present and
 * correct, and neither was rendered. This view is the route that was missing, so
 * the existing table is used rather than rewritten.
 *
 * ## What this list is, precisely
 *
 * VERIFIED `CallAttemptService.createAttempt`'s own Javadoc, quoted by the
 * controller: "The request identifies the operation (which execution, which
 * contact, which attempt number) but never redefines the execution's
 * configuration: didId, scheduledAt and the permitted attempt number all come
 * from the execution's immutable configuration snapshot, so a campaign edit
 * after execution creation cannot change what this call dials."
 *
 * So an attempt is a *queued intent to dial*, and its status is then advanced by
 * the execution engine rather than by the person looking at the page.
 *
 * ## Sorting is the server's, because the endpoint offers none
 *
 * VERIFIED `CampaignController.listAttempts` declares no `@RequestParam` at
 * all and `CallAttemptService` returns a repository list ordered by
 * `scheduledAt` ascending. There is no server-side sorting to drive a control
 * from, so the F1 table's "sortable" headers were re-implementing an ordering
 * the API never had. This view keeps the server's order and says so.
 *
 * ## The engine advances these too
 *
 * The F1 table called the PATCH helpers from an onClick with no invalidation, no
 * error handling and no pending state, so a failed transition was invisible. It
 * also assumed the rows never changed on their own.
 *
 * Both are corrected. VERIFIED `CampaignExecutionOrchestrator.scheduledTick`
 * (30 s) drives attempt lifecycle through `OutboundDialService`, so the list
 * polls while a row is non-terminal, and the manual transitions go through a
 * mutation. Those PATCH endpoints remain genuinely reachable by a
 * CAMPAIGN_EXECUTE holder — they are real, capability-checked operations — but
 * the engine is what normally moves a row, so the UI does not present them as
 * the only way an attempt progresses.
 */
/**
 * F5 CORRECTED A REAL DUPLICATION HERE. This view used to declare its own
 * private `EXECUTION_POLL_MS = 5_000` and its own `TERMINAL_ATTEMPT_STATUSES`
 * copy, and its doc comment justified that as "keeps its local list so it reads
 * without a cross-module hop … pinned to the same values by
 * `campaign-execution.test.ts`".
 *
 * That reasoning does not hold. A test that asserts module A equals module B
 * protects A and B from *drifting from each other*; it does not protect a third
 * copy written inside a component. `campaign-detail-view.tsx` and
 * `execution-detail-view.tsx` each declared their own copies too, so four
 * transcriptions of one backend enum existed in production code while the test
 * suite only ever inspected two of them. The comment documented the duplication
 * instead of removing it.
 *
 * All three components now import the interval and the terminal set from
 * `@/lib/domain/campaign-execution`, which is the single transcription of
 * `CampaignExecutionOrchestrator`'s private `TERMINAL_ATTEMPT_STATUSES` — the
 * same set `reconcileExecutionInternal` settles an execution on. Adding a status
 * to the backend enum is now a one-line edit that reaches every poller.
 */
export function ExecutionAttemptsView({
  campaignId,
  executionId,
}: {
  campaignId: string;
  executionId: string;
}) {  const queryClient = useQueryClient();
  const { user } = useCan();
  const canExecute = canPerformCampaignAction(user, "execute");

  const executionQuery = useQuery({
    queryKey: campaignsKeys.execution(campaignId, executionId),
    queryFn: () => getCampaignExecution(campaignId, executionId),
  });

  // The engine advances attempt status on its own tick
  // (`CampaignExecutionOrchestrator.scheduledTick`, 30 s), so the list is polled
  // while any row is still in a non-terminal state. Terminal states are
  // VERIFIED `CallAttemptStatus` COMPLETED / FAILED / CANCELLED, which the
  // engine never moves again.
  const attemptsQuery = useQuery({
    queryKey: callAttemptsKeys.byExecution(campaignId, executionId),
    queryFn: () => getCallAttempts({ campaignId, executionId }),
    refetchInterval: (current) => {
      const items = current.state.data?.items;
      if (!items || items.length === 0) return false;
      return shouldPollAttempts(items.map((attempt) => attempt.status))
        ? EXECUTION_POLL_MS
        : false;
    },
  });

  // F4: the F1 table called the PATCH helpers directly from an onClick, so
  // there was no invalidation, no error handling and no pending state — a
  // failed transition was invisible. This goes through a mutation.
  const transitionMutation = useMutation({
    mutationFn: async (transition: { attemptId: string; kind: "in-progress" | "failed" }) => {
      if (transition.kind === "failed") {
        // VERIFIED: the `failed` endpoint carries its diagnostics as QUERY
        // PARAMS (`CampaignController.markFailed` declares `@RequestParam` and
        // no `@RequestBody`), which `markAttemptFailed` already sends correctly.
        return markAttemptFailed(campaignId, executionId, transition.attemptId, {
          failureReason: "Marked failed from the console.",
        });
      }
      return markAttemptInProgress(campaignId, executionId, transition.attemptId);
    },
    onSuccess: async (_result, transition) => {
      toast.success(
        transition.kind === "failed" ? "Attempt marked failed" : "Attempt in progress",
      );
      await queryClient.invalidateQueries({
        queryKey: callAttemptsKeys.byExecution(campaignId, executionId),
      });
    },
    onError: (error: unknown) => {
      const apiError = toApiError(error);
      toast.error(apiError.message, {
        description: apiError.requestId ? `Request ${apiError.requestId}` : undefined,
      });
    },
  });

  const backHref = `/campaigns/${campaignId}`;

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Button variant="ghost" asChild>
          <Link href={backHref}>
            <ArrowLeftIcon className="mr-2 h-4 w-4" />
            Back to campaign
          </Link>
        </Button>
        <Button variant="outline" asChild>
          <Link href={`/campaigns/${campaignId}/executions/${executionId}`}>
            Execution detail
          </Link>
        </Button>
      </div>

      <div>
        <h1 className="text-3xl font-bold tracking-tight">Call attempts</h1>
        <p className="text-muted-foreground text-sm">
          Each row is a queued intent to place one call, carrying the number and
          attempt number frozen in this execution&apos;s configuration snapshot.
          The platform&apos;s execution engine advances the status on its own
          30-second cycle; nothing you do on this page is what makes a call
          happen.
        </p>
      </div>

      {executionQuery.isSuccess ? (
        <Card>
          <CardHeader>
            <CardTitle>Execution</CardTitle>
            <CardDescription>
              Frozen configuration snapshot{" "}
              <span className="font-mono">{executionQuery.data.configurationSnapshotId}</span>
            </CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-muted-foreground text-sm">
              Requested {formatDateTime(executionQuery.data.requestedAt)} ·{" "}
              {executionQuery.data.status}
            </p>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <PhoneCallIcon className="h-4 w-4" />
            Attempts
          </CardTitle>
          <CardDescription>
            Ordered by scheduled time, oldest first. This ordering comes from the
            server; the endpoint declares no sorting or filtering parameters.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {attemptsQuery.isPending ? (
            <TableSkeleton columns={5} rows={4} />
          ) : attemptsQuery.isError ? (
            <QueryErrorState
              error={attemptsQuery.error}
              entityLabel="call attempts"
              onRetry={() => void attemptsQuery.refetch()}
            />
          ) : attemptsQuery.data.items.length === 0 ? (
            <EmptyState
              icon={PhoneCallIcon}
              title="No attempts in this execution"
              description="Attempts appear here once the execution engine starts placing calls."
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-xs">
                  <tr>
                    <th scope="col" className="p-2 text-left">#</th>
                    <th scope="col" className="p-2 text-left">Status</th>
                    <th scope="col" className="p-2 text-left">Scheduled</th>
                    <th scope="col" className="p-2 text-left">Contact</th>
                    <th scope="col" className="p-2 text-left">Failure</th>
                    {canExecute ? (
                      <th scope="col" className="p-2 text-right">
                        <span className="sr-only">Actions</span>
                      </th>
                    ) : null}
                  </tr>
                </thead>
                <tbody>
                  {attemptsQuery.data.items.map((attempt) => (
                    <tr key={attempt.id} className="border-t">
                      <td className="p-2 font-medium">{attempt.attemptNumber}</td>
                      <td className="p-2">
                        <CallAttemptStatusBadge status={attempt.status} />
                      </td>
                      <td className="p-2 whitespace-nowrap">
                        {attempt.scheduledAt ? formatDateTime(attempt.scheduledAt) : "—"}
                      </td>
                      <td className="text-muted-foreground p-2 font-mono text-xs break-all">
                        {attempt.contactId}
                      </td>
                      <td className="p-2">
                        {attempt.failureCode || attempt.failureReason ? (
                          <span className="text-destructive">
                            {[attempt.failureCode, attempt.failureReason]
                              .filter(Boolean)
                              .join(": ")}
                          </span>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      {canExecute ? (
                        <td className="p-2 text-right">
                          {attempt.status === "QUEUED" ? (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={transitionMutation.isPending}
                              onClick={() => {
                                transitionMutation.mutate({
                                  attemptId: attempt.id,
                                  kind: "in-progress",
                                });
                              }}
                            >
                              Mark in progress
                            </Button>
                          ) : null}
                          {attempt.status === "QUEUED" || attempt.status === "IN_PROGRESS" ? (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="text-destructive"
                              disabled={transitionMutation.isPending}
                              onClick={() => {
                                transitionMutation.mutate({ attemptId: attempt.id, kind: "failed" });
                              }}
                            >
                              Mark failed
                            </Button>
                          ) : null}
                        </td>
                      ) : null}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
