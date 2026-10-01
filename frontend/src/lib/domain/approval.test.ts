import { describe, expect, it } from "vitest";

import {
  APPROVAL_CONSEQUENCE,
  APPROVAL_STATUS_LABEL,
  APPROVAL_STATUSES,
  TRANSITION_TARGET,
  availableTransitions,
  canTransition,
  isNoopTransition,
  noopConflictMessage,
  type ApprovalStatus,
  type ApprovalTransition,
} from "@/lib/domain/approval";

/**
 * F3 — the shared approval lifecycle.
 *
 * VERIFIED against `AudioAssetService.transition` (L265-277) and
 * `TtsTemplateService.transition` (L218-231), which are structurally identical:
 *
 * ```java
 * if (entity.getStatus() == target) {
 *     throw new ConflictException("... is already " + target + ".");
 * }
 * entity.setStatus(target);
 * ```
 *
 * That is the entire rule, and these tests pin all of it. The pre-F3 UI showed
 * Approve and Reject only when the status was `PENDING_APPROVAL`, which hid
 * three of the four legal transitions — most visibly, a REJECTED recording or
 * template could never be re-approved through the UI even though the backend
 * allows it.
 */
describe("statuses", () => {
  it("are exactly the three the backend enums declare", () => {
    // `AudioAssetStatus` and `TtsTemplateStatus` are the same three constants.
    expect([...APPROVAL_STATUSES]).toEqual([
      "PENDING_APPROVAL",
      "APPROVED",
      "REJECTED",
    ]);
  });

  it("each have a distinct human label", () => {
    const labels = APPROVAL_STATUSES.map((s) => APPROVAL_STATUS_LABEL[s]);
    expect(new Set(labels).size).toBe(labels.length);
    expect(APPROVAL_STATUS_LABEL.APPROVED).toBe("Approved");
  });
});

describe("transition targets", () => {
  it("approve targets APPROVED and reject targets REJECTED", () => {
    expect(TRANSITION_TARGET.approve).toBe("APPROVED");
    expect(TRANSITION_TARGET.reject).toBe("REJECTED");
  });
});

describe("the only refused transition is a no-op", () => {
  it("approving an APPROVED record is a no-op", () => {
    expect(isNoopTransition("APPROVED", "approve")).toBe(true);
    expect(canTransition("APPROVED", "approve")).toBe(false);
  });

  it("rejecting a REJECTED record is a no-op", () => {
    expect(isNoopTransition("REJECTED", "reject")).toBe(true);
    expect(canTransition("REJECTED", "reject")).toBe(false);
  });

  it("approving a PENDING record is allowed", () => {
    expect(canTransition("PENDING_APPROVAL", "approve")).toBe(true);
  });

  it("rejecting a PENDING record is allowed", () => {
    expect(canTransition("PENDING_APPROVAL", "reject")).toBe(true);
  });

  it("RE-APPROVING a REJECTED record is allowed — the re-submission path", () => {
    // The pre-F3 UI could not express this at all: a rejected record showed no
    // Approve button, so a reviewer who changed their mind had no way to undo
    // their own rejection.
    expect(isNoopTransition("REJECTED", "approve")).toBe(false);
    expect(canTransition("REJECTED", "approve")).toBe(true);
  });

  it("REJECTING an APPROVED record is allowed — the withdrawal path", () => {
    expect(isNoopTransition("APPROVED", "reject")).toBe(false);
    expect(canTransition("APPROVED", "reject")).toBe(true);
  });
});

describe("availableTransitions", () => {
  it("offers both transitions for a PENDING record", () => {
    expect(availableTransitions("PENDING_APPROVAL")).toEqual([
      "approve",
      "reject",
    ]);
  });

  it("offers only Reject for an APPROVED record", () => {
    expect(availableTransitions("APPROVED")).toEqual(["reject"]);
  });

  it("offers only Approve for a REJECTED record", () => {
    expect(availableTransitions("REJECTED")).toEqual(["approve"]);
  });

  it("never offers a transition that could only produce a 409", () => {
    for (const status of APPROVAL_STATUSES) {
      for (const transition of availableTransitions(status)) {
        expect(canTransition(status, transition)).toBe(true);
      }
    }
  });
});

describe("the 409 message", () => {
  it("reproduces the audio service's text", () => {
    // `AudioAssetService.transition`: "Audio asset is already " + target + "."
    expect(noopConflictMessage("Audio asset", "APPROVED", "approve")).toBe(
      "Audio asset is already APPROVED.",
    );
  });

  it("reproduces the TTS service's text", () => {
    // `TtsTemplateService.transition`: "TTS template is already " + target + "."
    expect(noopConflictMessage("TTS template", "REJECTED", "reject")).toBe(
      "TTS template is already REJECTED.",
    );
  });
});

describe("consequence copy", () => {
  it("exists for both domains and both transitions", () => {
    for (const domain of ["audio", "tts"] as const) {
      for (const transition of ["approve", "reject"] as const) {
        const text = APPROVAL_CONSEQUENCE[domain][transition];
        expect(typeof text).toBe("string");
        expect(text.length).toBeGreaterThan(20);
      }
    }
  });

  it("tells the user what breaks — campaign references fail activation", () => {
    // VERIFIED: `AudioAssetStatus` Javadoc ("Only APPROVED assets may be
    // referenced by executable campaigns") and the reject/delete Javadocs
    // ("existing campaign references fail activation afterwards").
    expect(APPROVAL_CONSEQUENCE.audio.reject).toMatch(/fail to start/i);
    expect(APPROVAL_CONSEQUENCE.tts.reject).toMatch(/fail to start/i);
  });

  it("explains that a GLOBAL template is shared, not private", () => {
    expect(APPROVAL_CONSEQUENCE.tts.approve).toMatch(/shared catalog/i);
  });
});

/** Exhaustive: every status × every transition, so a new enum value cannot be
 *  added without this table being revisited. */
describe("the full transition matrix", () => {
  const matrix: Record<ApprovalStatus, Record<ApprovalTransition, boolean>> = {
    PENDING_APPROVAL: { approve: true, reject: true },
    APPROVED: { approve: false, reject: true },
    REJECTED: { approve: true, reject: false },
  };

  for (const status of APPROVAL_STATUSES) {
    for (const transition of ["approve", "reject"] as const) {
      it(`${status} → ${transition} is ${matrix[status][transition] ? "allowed" : "a 409"}`, () => {
        expect(canTransition(status, transition)).toBe(matrix[status][transition]);
      });
    }
  }
});
