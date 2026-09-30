import { describe, expect, it } from "vitest";

import {
  CAMPAIGN_LEGAL_TRANSITIONS,
  CAMPAIGN_RESERVED_STATUSES,
  CAMPAIGN_STATUS_DESCRIPTION,
  CAMPAIGN_STATUS_IS_OPERATOR_DRIVEN,
  CAMPAIGN_STATUS_LABEL,
  CAMPAIGN_STATUSES,
  CAMPAIGN_TRANSITION_CONSEQUENCE,
  availableTransitions,
  hasAvailableTransition,
  isEditable,
  isExecutable,
  isInExecutableState,
  isLegalTransition,
  isReservedTarget,
  illegalTransitionMessage,
  notEditableMessage,
} from "@/lib/domain/campaign-lifecycle";
import type { CampaignStatus } from "@/lib/api/contracts";
import type { ManuallyReachableStatus } from "@/lib/domain/campaign-lifecycle";

/**
 * Contract tests for the Campaign lifecycle model.
 *
 * ## This file is a RECONSTRUCTION
 *
 * The original `campaign-lifecycle.test.ts` was accidentally truncated to zero
 * bytes during the VB-8J backend phase and could not be recovered (it was
 * untracked, so no VCS copy existed). This file was rebuilt from source, not
 * restored. Nothing here is inherited from the previous version: every
 * expectation below is transcribed from `campaign-lifecycle.ts` and
 * independently re-derived from the backend, so any assertion that cannot be
 * traced to one of those was left out rather than guessed.
 *
 * ## What is being protected
 *
 * `campaign-lifecycle.ts` is a hand-maintained transcription of two backend
 * maps. Typecheck cannot detect drift in either, and every consumer renders
 * lifecycle controls from them, so a silent divergence shows up as UI offering
 * buttons that can only return 409. These tests fail on drift instead.
 *
 * ## VB-8J — the contract that changed
 *
 * `CampaignStatus` is the operator-controlled campaign configuration and
 * control lifecycle. It is never derived from `CampaignExecutionStatus`:
 * several executions may run for one campaign, so no single execution could
 * authoritatively set it.
 *
 * `RUNNING`, `COMPLETED` and `FAILED` are therefore *reserved execution-facts*.
 * No runtime producer writes them and the operator API refuses them. They are
 * retained in the enum only because `ck_campaigns_status` is a database CHECK
 * constraint (V15), so removing them would be a schema change.
 *
 * The consequence for this module is that there are still THREE outcomes for a
 * proposed transition, and the distinction that matters is *why* one is refused:
 *
 *  1. not in `LEGAL_TRANSITIONS`      -> 409, "Illegal campaign lifecycle ..."
 *  2. legal, but target is RESERVED  -> 409, "Campaign status X is reserved: ..."
 *  3. legal and not reserved         -> accepted
 *
 * VB-8H had step 2 attributed to "the execution engine". That attribution was
 * never true and no engine path writes campaign status. The distinction between
 * "illegal" and "legal but reserved" is what this file pins.
 *
 * ## Deliberately NOT asserted
 *
 *  - `systemDrivenTransitionMessage()`. It returns the VB-8H text
 *    "Transition X -> Y is performed by the execution engine.", which the
 *    backend no longer emits anywhere. Asserting it would lock in text that can
 *    never appear in a real 409. Reported separately as a source defect rather
 *    than enshrined here.
 *  - Anything derived from execution status. This module holds no such rule,
 *    and campaign status must never be inferred from executions.
 *  - Calling-window and duplicate-execution behaviour. Neither is a
 *    `CampaignStatus` concern; both are enforced server-side.
 */

// Transcribed independently from `CampaignStatus.java`, in declaration order.
// If the backend enum gains, drops or reorders a constant this fails.
const BACKEND_STATUSES: readonly CampaignStatus[] = [
  "DRAFT",
  "SCHEDULED",
  "RUNNING",
  "PAUSED",
  "COMPLETED",
  "FAILED",
  "ARCHIVED",
];

// Transcribed independently from `CampaignService.LEGAL_TRANSITIONS`. The
// backend type is a `Set`, so order carries no meaning and is normalised.
const BACKEND_LEGAL: Readonly<Record<CampaignStatus, readonly CampaignStatus[]>> =
  {
    DRAFT: ["SCHEDULED"],
    SCHEDULED: ["PAUSED", "DRAFT", "ARCHIVED", "RUNNING"],
    RUNNING: ["PAUSED", "ARCHIVED", "COMPLETED", "FAILED"],
    PAUSED: ["SCHEDULED", "ARCHIVED", "RUNNING"],
    COMPLETED: ["ARCHIVED"],
    FAILED: ["ARCHIVED"],
    ARCHIVED: [],
  };

// Transcribed independently from `CampaignService.RESERVED_STATES`.
const BACKEND_RESERVED: readonly CampaignStatus[] = [
  "RUNNING",
  "COMPLETED",
  "FAILED",
];

// `availableTransitions` preserves the order of `CAMPAIGN_LEGAL_TRANSITIONS`,
// so this table is order-significant: it is the order the UI renders controls
// in. Each row is the legal row above with reserved targets filtered out.
const EXPECTED_CLICKABLE: Record<CampaignStatus, readonly CampaignStatus[]> = {
  DRAFT: ["SCHEDULED"],
  // RUNNING removed: reserved execution-fact.
  SCHEDULED: ["PAUSED", "DRAFT", "ARCHIVED"],
  // COMPLETED and FAILED removed: reserved execution-facts.
  RUNNING: ["PAUSED", "ARCHIVED"],
  // RUNNING removed: reserved execution-fact.
  PAUSED: ["SCHEDULED", "ARCHIVED"],
  COMPLETED: ["ARCHIVED"],
  FAILED: ["ARCHIVED"],
  ARCHIVED: [],
};

describe("CampaignStatus matches the backend enum", () => {
  it("has exactly the seven backend constants, in backend order", () => {
    expect([...CAMPAIGN_STATUSES]).toEqual([...BACKEND_STATUSES]);
  });

  it("exposes no status the backend does not declare", () => {
    for (const status of CAMPAIGN_STATUSES) {
      expect(BACKEND_STATUSES, status).toContain(status);
    }
  });
});

describe("LEGAL_TRANSITIONS matches the backend map", () => {
  it("every row equals the backend row, order-independent", () => {
    for (const status of BACKEND_STATUSES) {
      expect([...CAMPAIGN_LEGAL_TRANSITIONS[status]].sort(), status).toEqual(
        [...BACKEND_LEGAL[status]].sort(),
      );
    }
  });

  it("declares no row the backend enum does not have", () => {
    expect(Object.keys(CAMPAIGN_LEGAL_TRANSITIONS).sort()).toEqual(
      [...BACKEND_STATUSES].sort(),
    );
  });

  it("invents no edge the backend map does not contain", () => {
    for (const from of BACKEND_STATUSES) {
      for (const to of CAMPAIGN_LEGAL_TRANSITIONS[from]) {
        expect(BACKEND_LEGAL[from], `${from} -> ${to}`).toContain(to);
      }
    }
  });
});

describe("RESERVED_STATES matches the backend set", () => {
  it("is exactly the three reserved execution-facts", () => {
    expect([...CAMPAIGN_RESERVED_STATUSES]).toEqual([...BACKEND_RESERVED]);
  });

  it("reserves nothing else — the other four are real control states", () => {
    for (const status of CAMPAIGN_STATUSES) {
      expect(isReservedTarget(status), status).toBe(
        BACKEND_RESERVED.includes(status),
      );
    }
  });

  it("is a subset of the legal targets, so every reserved state is a status", () => {
    // A reserved value that no edge can target would be silently unreachable for
    // a different reason than the intended one.
    for (const status of CAMPAIGN_RESERVED_STATUSES) {
      const reachable = BACKEND_STATUSES.some((from) =>
        BACKEND_LEGAL[from].includes(status),
      );
      expect(reachable, status).toBe(true);
    }
  });
});

describe("legal versus available are genuinely different questions", () => {
  it("a reserved target is legal but never available", () => {
    // This is the distinction VB-8J turned on. Step 1 alone says "legal", so
    // `isLegalTransition` alone would still offer a control that can only 409.
    const reservedButLegal: ReadonlyArray<readonly [CampaignStatus, CampaignStatus]> =
      [
        ["SCHEDULED", "RUNNING"],
        ["PAUSED", "RUNNING"],
        ["RUNNING", "COMPLETED"],
        ["RUNNING", "FAILED"],
      ];
    for (const [from, to] of reservedButLegal) {
      expect(isLegalTransition(from, to), `${from} -> ${to}`).toBe(true);
      expect(availableTransitions(from), `${from} -> ${to}`).not.toContain(to);
    }
  });

  it("availableTransitions is the legal set minus reserved targets — exhaustively", () => {
    for (const from of BACKEND_STATUSES) {
      expect([...availableTransitions(from)], from).toEqual([
        ...EXPECTED_CLICKABLE[from],
      ]);
    }
  });

  it("never offers a reserved status from any status", () => {
    for (const from of BACKEND_STATUSES) {
      for (const to of availableTransitions(from)) {
        expect(isReservedTarget(to), `${from} -> ${to}`).toBe(false);
      }
    }
  });

  it("invents no available edge the legal map lacks", () => {
    for (const from of BACKEND_STATUSES) {
      for (const to of availableTransitions(from)) {
        expect(isLegalTransition(from, to), `${from} -> ${to}`).toBe(true);
      }
    }
  });
});

describe("PAUSED is the control-intent state, and nothing more", () => {
  it("offers only resume and archive — no draft, no terminal states", () => {
    expect([...availableTransitions("PAUSED")]).toEqual([
      "SCHEDULED",
      "ARCHIVED",
    ]);
  });

  it("cannot be reached from DRAFT, so pausing requires an active campaign", () => {
    expect(CAMPAIGN_LEGAL_TRANSITIONS.DRAFT).not.toContain("PAUSED");
    expect(availableTransitions("DRAFT")).not.toContain("PAUSED");
  });

  it("is not executable, so a paused campaign stops being dispatch-eligible", () => {
    expect(isExecutable("PAUSED")).toBe(false);
    expect(isEditable("PAUSED")).toBe(false);
  });

  it("is not derived from any execution status — it is a manual state", () => {
    // There is no reserved target reachable out of PAUSED, which is what keeps
    // pause from reading as an execution fact.
    expect(availableTransitions("PAUSED")).toEqual(
      expect.not.arrayContaining([...CAMPAIGN_RESERVED_STATUSES]),
    );
  });
});

describe("terminal states", () => {
  it("ARCHIVED is the only dead end", () => {
    for (const status of BACKEND_STATUSES) {
      const dead = !hasAvailableTransition(status);
      expect(dead, status).toBe(status === "ARCHIVED");
    }
  });

  it("COMPLETED and FAILED are reserved yet still offer archiving", () => {
    // They describe an execution outcome, so the operator may not set them — but
    // the API can still return them for a row that already holds one, and that
    // row must remain archivable.
    expect(isReservedTarget("COMPLETED")).toBe(true);
    expect(isReservedTarget("FAILED")).toBe(true);
    expect([...availableTransitions("COMPLETED")]).toEqual(["ARCHIVED"]);
    expect([...availableTransitions("FAILED")]).toEqual(["ARCHIVED"]);
  });

  it("ARCHIVED itself is not reserved — it is a real terminal control state", () => {
    expect(isReservedTarget("ARCHIVED")).toBe(false);
  });
});

describe("editability, VERIFIED CampaignLifecyclePolicy.EDITABLE_STATUSES", () => {
  it("is DRAFT and nowhere else — exhaustively", () => {
    for (const status of BACKEND_STATUSES) {
      expect(isEditable(status), status).toBe(status === "DRAFT");
    }
  });

  it("is why availableTransitions exposes DRAFT as a target from SCHEDULED", () => {
    // Returning a locked campaign to DRAFT is the only way to edit it, so the
    // edge has to be clickable or the Edit control would be unreachable.
    expect(availableTransitions("SCHEDULED")).toContain("DRAFT");
  });
});

describe("executability, VERIFIED CampaignLifecyclePolicy.EXECUTABLE_STATUSES", () => {
  it("is exactly SCHEDULED and RUNNING", () => {
    for (const status of BACKEND_STATUSES) {
      expect(isExecutable(status), status).toBe(
        status === "SCHEDULED" || status === "RUNNING",
      );
    }
  });

  it("is the same predicate as isInExecutableState", () => {
    for (const status of BACKEND_STATUSES) {
      expect(isInExecutableState(status), status).toBe(isExecutable(status));
    }
  });

  it("never overlaps editability — the backend asserts this too", () => {
    // A status that is both editable and executable would let a campaign be
    // reconfigured mid-run. `CampaignLifecyclePolicy` guards it server-side;
    // this guards the frontend copy.
    for (const status of BACKEND_STATUSES) {
      expect(isEditable(status) && isExecutable(status), status).toBe(false);
    }
  });
});

describe("the model declares campaign status operator-driven", () => {
  it("sets CAMPAIGN_STATUS_IS_OPERATOR_DRIVEN", () => {
    // A documentation constant rather than behaviour, but it is the module's
    // stated position and it is what the rest of the module encodes.
    expect(CAMPAIGN_STATUS_IS_OPERATOR_DRIVEN).toBe(true);
  });
});

describe("409 message helpers match the backend strings verbatim", () => {
  it("illegalTransitionMessage matches CampaignService", () => {
    // VERIFIED: "Illegal campaign lifecycle transition: " + from + " -> "
    // + target + "."
    for (const from of BACKEND_STATUSES) {
      for (const to of BACKEND_STATUSES) {
        expect(illegalTransitionMessage(from, to)).toBe(
          `Illegal campaign lifecycle transition: ${from} -> ${to}.`,
        );
      }
    }
  });

  it("notEditableMessage matches CampaignLifecyclePolicy.assertEditable", () => {
    // VERIFIED: "Campaign configuration can only be modified while the campaign
    // is in DRAFT state (current: " + status + ")."
    for (const status of BACKEND_STATUSES) {
      expect(notEditableMessage(status)).toBe(
        "Campaign configuration can only be modified while the campaign is in " +
          `DRAFT state (current: ${status}).`,
      );
    }
  });

  it("names the current status, so the operator can tell which campaign", () => {
    expect(notEditableMessage("PAUSED")).toContain("PAUSED");
  });
});

describe("labels and descriptions cover every status", () => {
  it("CAMPAIGN_STATUS_LABEL has an entry per backend status", () => {
    for (const status of BACKEND_STATUSES) {
      const label = CAMPAIGN_STATUS_LABEL[status];
      expect(typeof label, status).toBe("string");
      expect(label.length, status).toBeGreaterThan(0);
    }
  });

  it("labels are distinct, so a badge cannot render two states identically", () => {
    const labels = BACKEND_STATUSES.map((s) => CAMPAIGN_STATUS_LABEL[s]);
    expect(new Set(labels).size).toBe(BACKEND_STATUSES.length);
  });

  it("CAMPAIGN_STATUS_DESCRIPTION has an entry per backend status", () => {
    for (const status of BACKEND_STATUSES) {
      const description = CAMPAIGN_STATUS_DESCRIPTION[status];
      expect(typeof description, status).toBe("string");
      expect(description.length, status).toBeGreaterThan(0);
    }
  });

  it("declares no label or description for a non-backend status", () => {
    expect(Object.keys(CAMPAIGN_STATUS_LABEL).sort()).toEqual(
      [...BACKEND_STATUSES].sort(),
    );
    expect(Object.keys(CAMPAIGN_STATUS_DESCRIPTION).sort()).toEqual(
      [...BACKEND_STATUSES].sort(),
    );
  });
});

describe("every transition a user can click explains itself", () => {
  it("CAMPAIGN_TRANSITION_CONSEQUENCE covers exactly the reachable targets", () => {
    // The map is typed over `ManuallyReachableStatus`, which the compiler
    // cannot tie back to what `availableTransitions` actually returns. This
    // closes that gap: a status that becomes reachable cannot lack its
    // explanation.
    const reachable = new Set<string>();
    for (const from of BACKEND_STATUSES) {
      for (const to of availableTransitions(from)) {
        reachable.add(to);
      }
    }
    expect(Object.keys(CAMPAIGN_TRANSITION_CONSEQUENCE).sort()).toEqual(
      [...reachable].sort(),
    );
  });

  it("has a non-empty consequence for each reachable target", () => {
    for (const target of Object.keys(
      CAMPAIGN_TRANSITION_CONSEQUENCE,
    ) as ManuallyReachableStatus[]) {
      expect(CAMPAIGN_TRANSITION_CONSEQUENCE[target].length, target).toBeGreaterThan(
        0,
      );
    }
  });

  it("describes the reserved statuses nowhere, because they are unreachable", () => {
    // If a reserved status ever gained a consequence entry, that would imply it
    // is clickable, which is the exact bug VB-8J fixed.
    for (const reserved of CAMPAIGN_RESERVED_STATUSES) {
      expect(
        Object.keys(CAMPAIGN_TRANSITION_CONSEQUENCE),
        reserved,
      ).not.toContain(reserved);
    }
  });
});