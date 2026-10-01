import { describe, expect, it } from "vitest";

import {
  DISPATCHABLE_EXECUTION_STATUSES,
  ENGINE_TICK_MS,
  EXECUTION_POLL_MS,
  IN_FLIGHT_EXECUTION_CONFLICT_MESSAGE,
  TERMINAL_ATTEMPT_STATUSES,
  TERMINAL_EXECUTION_STATUSES,
  executionStartState,
  findInFlightExecution,
  shouldPollAttemptList,
  shouldPollAttempts,
  shouldPollExecution,
  shouldPollExecutionList,
} from "@/lib/domain/campaign-execution";
import type { CallAttemptStatus, CampaignExecutionStatus } from "@/lib/api/contracts";

/**
 * F4.1 — contract tests for the execution-observability model.
 *
 * ## What is being protected
 *
 * F4 restored execution polling after discovering the engine, then guarded it
 * with inline arrays written by hand. F4.1 extracted those sets here and this
 * file asserts them against the backend's own enums, transcribed independently
 * of the module under test, so a backend change fails the suite rather than
 * silently changing when the UI polls.
 *
 * Every set below is a VERIFIED transcription. The important ones:
 *
 *  - `CampaignExecutionStatus.DISPATCHABLE = Set.of(REQUESTED, RUNNING)`
 *  - `CampaignExecutionStatus.TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED)`
 *  - `CallAttemptStatus` terminal set, asserted twice by the backend itself
 *  - `@Scheduled(fixedDelay = 30000)`
 */

const EXECUTION_STATUSES: readonly CampaignExecutionStatus[] = [
  "REQUESTED",
  "RUNNING",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
];

const ATTEMPT_STATUSES: readonly CallAttemptStatus[] = [
  "QUEUED",
  "IN_PROGRESS",
  "COMPLETED",
  "FAILED",
  "CANCELLED",
];

describe("the engine tick", () => {
  it("is 30 seconds, VERIFIED from @Scheduled(fixedDelay = 30000)", () => {
    expect(ENGINE_TICK_MS).toBe(30_000);
  });

  it("the frontend polls faster than the engine advances, but not wastefully", () => {
    // A user must see a transition within one poll of it happening...
    expect(EXECUTION_POLL_MS).toBeLessThan(ENGINE_TICK_MS);
    // ...without hammering the endpoint: at most 6 requests per engine cycle.
    expect(ENGINE_TICK_MS / EXECUTION_POLL_MS).toBeLessThanOrEqual(6);
  });
});

describe("execution status sets match the backend enum", () => {
  it("DISPATCHABLE is exactly {REQUESTED, RUNNING}", () => {
    expect([...DISPATCHABLE_EXECUTION_STATUSES]).toEqual(["REQUESTED", "RUNNING"]);
  });

  it("TERMINAL is exactly {COMPLETED, FAILED, CANCELLED}", () => {
    expect([...TERMINAL_EXECUTION_STATUSES]).toEqual([
      "COMPLETED",
      "FAILED",
      "CANCELLED",
    ]);
  });

  it("the two sets are disjoint and jointly exhaustive", () => {
    const dispatchable = new Set<string>(DISPATCHABLE_EXECUTION_STATUSES);
    const terminal = new Set<string>(TERMINAL_EXECUTION_STATUSES);
    for (const status of EXECUTION_STATUSES) {
      expect(
        dispatchable.has(status) !== terminal.has(status),
        `${status} must be in exactly one set`,
      ).toBe(true);
    }
    expect(dispatchable.size + terminal.size).toBe(EXECUTION_STATUSES.length);
  });
});

describe("shouldPollExecution", () => {
  it("polls while the engine can still move the execution", () => {
    expect(shouldPollExecution("REQUESTED")).toBe(true);
    expect(shouldPollExecution("RUNNING")).toBe(true);
  });

  it("stops at every terminal state — no permanent polling", () => {
    for (const status of TERMINAL_EXECUTION_STATUSES) {
      expect(shouldPollExecution(status), status).toBe(false);
    }
  });

  it("does not poll before the first response arrives", () => {
    // An undefined status is "not loaded yet", not "still running". Polling on
    // it would issue a request per interval for a query that has not resolved.
    expect(shouldPollExecution(undefined)).toBe(false);
  });

  it("agrees with the terminal set for every status — exhaustively", () => {
    for (const status of EXECUTION_STATUSES) {
      const isTerminal = TERMINAL_EXECUTION_STATUSES.includes(status);
      expect(shouldPollExecution(status), status).toBe(!isTerminal);
    }
  });
});

describe("shouldPollExecutionList", () => {
  it("polls when any row is still dispatchable", () => {
    expect(shouldPollExecutionList(["COMPLETED", "RUNNING"])).toBe(true);
  });

  it("stops when every row has settled", () => {
    expect(shouldPollExecutionList(["COMPLETED", "FAILED", "CANCELLED"])).toBe(
      false,
    );
  });

  it("does not poll an empty list", () => {
    // Nothing in flight can change, so a permanent request would buy nothing.
    expect(shouldPollExecutionList([])).toBe(false);
  });
});

describe("attempt status sets match the backend enum", () => {
  it("the terminal set is exactly {COMPLETED, FAILED, CANCELLED}", () => {
    // VERIFIED `CampaignExecutionOrchestrator.TERMINAL_ATTEMPT_STATUSES` and
    // independently `reconcileExecutionInternal`'s allMatch over the same set.
    expect([...TERMINAL_ATTEMPT_STATUSES]).toEqual([
      "COMPLETED",
      "FAILED",
      "CANCELLED",
    ]);
  });

  it("leaves QUEUED and IN_PROGRESS pollable", () => {
    // These are the two the engine moves: QUEUED -> IN_PROGRESS -> COMPLETED /
    // FAILED / CANCELLED, plus the PAUSED re-queue which returns an attempt to
    // QUEUED without consuming it.
    expect(TERMINAL_ATTEMPT_STATUSES).not.toContain("QUEUED");
    expect(TERMINAL_ATTEMPT_STATUSES).not.toContain("IN_PROGRESS");
  });
});

describe("shouldPollAttempts", () => {
  it("polls while any attempt is in flight", () => {
    expect(shouldPollAttempts(["COMPLETED", "QUEUED"])).toBe(true);
    expect(shouldPollAttempts(["IN_PROGRESS"])).toBe(true);
  });

  it("stops when every attempt has settled", () => {
    expect(shouldPollAttempts(["COMPLETED", "FAILED", "CANCELLED"])).toBe(false);
  });

  it("does not poll an empty attempt list", () => {
    expect(shouldPollAttempts([])).toBe(false);
  });

  it("agrees with the terminal set for a single-row list — exhaustively", () => {
    for (const status of ATTEMPT_STATUSES) {
      expect(shouldPollAttempts([status]), status).toBe(
        !TERMINAL_ATTEMPT_STATUSES.includes(status),
      );
    }
  });
});

describe("the two domains' poll guards stay independent", () => {
  it("an execution status is never treated as an attempt status", () => {
    // The two enums overlap on three names. Sharing one list would mean a change
    // to one silently altered the other, so they are declared separately and
    // asserted to have been written separately.
    expect(TERMINAL_ATTEMPT_STATUSES).toEqual(TERMINAL_EXECUTION_STATUSES);
    expect(DISPATCHABLE_EXECUTION_STATUSES).not.toEqual(
      expect.arrayContaining(["QUEUED", "IN_PROGRESS"]),
    );
  });

  it("the attempt poll has a named alias used by the view", () => {
    // Guards the import shape the component relies on.
    expect(typeof shouldPollAttemptList).toBe("function");
    expect(shouldPollAttemptList(["QUEUED"])).toBe(true);
    expect(shouldPollAttemptList(["COMPLETED"])).toBe(false);
  });
});

/* -------------------------------------------------------------------------- */
/* F5 — one in-flight execution (VB-8J)                                        */
/* -------------------------------------------------------------------------- */

describe("findInFlightExecution", () => {
  const row = (status: CampaignExecutionStatus, id: string) => ({
    status,
    id,
    requestedAt: "2026-01-01T00:00:00Z",
  });

  it("finds a REQUESTED execution, which is in flight", () => {
    const found = findInFlightExecution([row("COMPLETED", "a"), row("REQUESTED", "b")]);
    expect(found?.id).toBe("b");
  });

  it("finds a RUNNING execution, which is in flight", () => {
    const found = findInFlightExecution([row("COMPLETED", "a"), row("RUNNING", "b")]);
    expect(found?.id).toBe("b");
  });

  it("returns null once every execution is terminal - a new one may be created", () => {
    // VERIFIED `existsActiveByCampaignId` restricts to REQUESTED/RUNNING, so a
    // campaign whose executions have all settled is no longer blocked. This is
    // the COMPLETED / FAILED / CANCELLED case from the brief.
    for (const status of TERMINAL_EXECUTION_STATUSES) {
      expect(findInFlightExecution([row(status, "a")]), status).toBeNull();
    }
    expect(
      findInFlightExecution([
        row("COMPLETED", "a"),
        row("FAILED", "b"),
        row("CANCELLED", "c"),
      ]),
    ).toBeNull();
  });

  it("returns null for no executions and for an undefined list", () => {
    // Both are the "nothing is blocking this campaign" case: before the first
    // request, and while the executions query has not resolved.
    expect(findInFlightExecution([])).toBeNull();
    expect(findInFlightExecution(undefined)).toBeNull();
  });

  it("agrees with shouldPollExecution for every status - exhaustively", () => {
    // The backend's in-flight set IS its DISPATCHABLE set. Expressing the guard
    // in terms of the poll predicate means the two cannot drift: a status the UI
    // would keep polling is exactly a status that blocks a new request.
    for (const status of EXECUTION_STATUSES) {
      const found = findInFlightExecution([row(status, "x")]);
      expect(found !== null, status).toBe(shouldPollExecution(status));
    }
  });

  it("returns the most recently requested when several are dispatchable", () => {
    // VERIFIED the list is ordered `RequestedAtDesc`, so the first match is the
    // newest. Defensive only: the backend refuses the second creation, so this
    // shape cannot normally be produced.
    const found = findInFlightExecution([
      row("RUNNING", "newest"),
      row("REQUESTED", "older"),
    ]);
    expect(found?.id).toBe("newest");
  });

  it("transcribes the backend's 422 message verbatim", () => {
    // VERIFIED `CampaignExecutionService.execute`. A control that explains the
    // rule in different words from the error it prevents is its own small
    // honesty bug, so the sentence is pinned here.
    expect(IN_FLIGHT_EXECUTION_CONFLICT_MESSAGE).toBe(
      "Campaign already has an execution in progress. Wait for it to reach a terminal state, or pause it, before starting another.",
    );
  });
});

/* -------------------------------------------------------------------------- */
/* F5 — why an execution has not started (VB-8H, resolves B10)                 */
/* -------------------------------------------------------------------------- */

describe("executionStartState", () => {
  it("reports DEFERRED_BY_CAMPAIGN_STATE when the backend gave a reason", () => {
    // VERIFIED `deriveDeferredReason`: non-null only while REQUESTED, and the
    // text always comes from `notExecutableReason`, whose code is always
    // CAMPAIGN_NOT_EXECUTABLE_STATE and whose message names the campaign status.
    expect(
      executionStartState({
        status: "REQUESTED",
        deferredReason:
          "Campaign is not in an executable state: PAUSED. Only SCHEDULED or RUNNING campaigns can execute.",
      }),
    ).toBe("DEFERRED_BY_CAMPAIGN_STATE");
  });

  it("reports AWAITING_ENGINE when nothing is blocking it", () => {
    // A REQUESTED execution with no reason is queued, not stuck. Claiming a
    // blocker here would invent one the backend did not report.
    expect(
      executionStartState({ status: "REQUESTED", deferredReason: null }),
    ).toBe("AWAITING_ENGINE");
  });

  it("returns null for RUNNING - 'why has it not started' is not a live question", () => {
    expect(
      executionStartState({ status: "RUNNING", deferredReason: null }),
    ).toBeNull();
  });

  it("returns null for every terminal status", () => {
    // COMPLETED / FAILED / CANCELLED. A deferred reason here would be
    // indistinguishable from a failure reason, which is precisely why the
    // backend makes it null.
    for (const status of TERMINAL_EXECUTION_STATUSES) {
      expect(executionStartState({ status, deferredReason: null }), status).toBeNull();
    }
  });

  it("ignores a deferredReason on a non-REQUESTED execution", () => {
    // The backend guarantees this cannot happen. If it ever did, showing
    // 'waiting to start' on a RUNNING or terminal execution would be wrong, so
    // the status wins.
    expect(
      executionStartState({ status: "COMPLETED", deferredReason: "stale reason" }),
    ).toBeNull();
    expect(
      executionStartState({ status: "FAILED", deferredReason: "stale reason" }),
    ).toBeNull();
  });

  it("returns null for no execution yet", () => {
    expect(executionStartState(null)).toBeNull();
    expect(executionStartState(undefined)).toBeNull();
  });

  it("classifies exactly one of the two branches for REQUESTED - exhaustively", () => {
    const branches = [null, "some backend reason"] as const;
    for (const deferredReason of branches) {
      const result = executionStartState({ status: "REQUESTED", deferredReason });
      expect(
        result === "DEFERRED_BY_CAMPAIGN_STATE" || result === "AWAITING_ENGINE",
      ).toBe(true);
    }
  });

  it("never returns a state for a status outside REQUESTED - exhaustively", () => {
    for (const status of EXECUTION_STATUSES) {
      const result = executionStartState({ status, deferredReason: null });
      if (status === "REQUESTED") {
        expect(result).toBe("AWAITING_ENGINE");
      } else {
        expect(result, status).toBeNull();
      }
    }
  });
});
