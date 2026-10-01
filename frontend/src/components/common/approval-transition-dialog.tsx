"use client";

import { useState } from "react";
import { CheckIcon, XIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import {
  APPROVAL_CONSEQUENCE,
  APPROVAL_STATUS_LABEL,
  TRANSITION_TARGET,
  type ApprovalStatus,
  type ApprovalTransition,
} from "@/lib/domain/approval";

/**
 * One confirmation dialog for the Approve / Reject transitions, shared by Audio
 * Assets and TTS Templates.
 *
 * ## Why these two domains share a dialog
 *
 * Not for brevity — because the interaction is genuinely one thing.
 * `AudioAssetService.transition` (L265-277) and `TtsTemplateService.transition`
 * (L218-231) are structurally identical, both expose exactly two transitions,
 * and both refuse only a no-op with a 409. The full model lives in
 * `lib/domain/approval.ts`; this component is its presentation.
 *
 * The domain-specific parts arrive as parameters: the noun the user reads, the
 * per-domain consequence text, and the mutation to run. Nothing here knows an
 * endpoint.
 *
 * ## Why approval is confirmed at all
 *
 * Both transitions change whether a campaign can use a recording or a template.
 * `APPROVED` is the only state an executable campaign may reference, and
 * rejecting one that campaigns already point at makes those campaigns fail to
 * start (`AudioAssetStatus` Javadoc, `TtsTemplateService.reject`). The
 * pre-F3 UI fired both with an unguarded click and a toast.
 *
 * ## The race the 409 branch still covers
 *
 * The dialog hides a no-op transition, so a 409 should be unreachable from a
 * normal click. It is still handled, because the status can change underneath an
 * open dialog — a second reviewer approving the same item first. The message is
 * the server's own conflict text, not an invented reason.
 */
export function ApprovalTransitionDialog({
  open,
  onOpenChange,
  subject,
  /** Noun used in the title and body, e.g. `"Welcome message"`. */
  label,
  status,
  transition,
  consequence,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** "Audio asset" or "TTS template" — used in the 409 echo. */
  subject: "Audio asset" | "TTS template";
  label: string;
  status: ApprovalStatus;
  transition: ApprovalTransition;
  /** Plain-language consequence, from `APPROVAL_CONSEQUENCE`. */
  consequence: string;
  /** Runs the transition. Rejecting to prevent a close-on-failure bug. */
  onConfirm: () => Promise<unknown>;
}) {
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const target = TRANSITION_TARGET[transition];
  const isApprove = transition === "approve";

  function handleOpenChange(next: boolean) {
    // F3 §38: the dialog cannot be dismissed mid-flight, so a slow request is
    // never left running against a record the user has navigated away from.
    if (pending) return;
    if (!next) setError(null);
    onOpenChange(next);
  }

  async function handleConfirm() {
    setError(null);
    setPending(true);
    try {
      await onConfirm();
      onOpenChange(false);
    } catch (e) {
      const apiError = toApiError(e);
      setError(
        apiError.requestId
          ? `${apiError.message} (Request ID: ${apiError.requestId})`
          : apiError.message,
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {isApprove ? (
              <CheckIcon aria-hidden="true" className="size-5" />
            ) : (
              <XIcon aria-hidden="true" className="size-5" />
            )}
            {isApprove ? "Approve" : "Reject"} {label}?
          </DialogTitle>
          <DialogDescription>{consequence}</DialogDescription>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          This {subject === "Audio asset" ? "recording" : "template"}{" "}
          (<strong>{label}</strong>) is currently{" "}
          {APPROVAL_STATUS_LABEL[status].toLowerCase()}. After this it will be{" "}
          {APPROVAL_STATUS_LABEL[target].toLowerCase()}.
        </p>

        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={pending}
          >
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => void handleConfirm()}
            disabled={pending}
          >
            {pending ? <Spinner className="size-4" /> : null}
            {pending
              ? isApprove
                ? "Approving…"
                : "Rejecting…"
              : isApprove
                ? "Approve"
                : "Reject"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** The consequence text for a transition, kept beside the dialog that shows it
 *  so the copy and the model cannot drift apart. */
export function approvalConsequence(
  domain: keyof typeof APPROVAL_CONSEQUENCE,
  transition: ApprovalTransition,
): string {
  return APPROVAL_CONSEQUENCE[domain][transition];
}
