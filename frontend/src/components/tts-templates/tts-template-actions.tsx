"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { CheckIcon, PencilIcon, Trash2Icon, XIcon } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  ApprovalTransitionDialog,
  approvalConsequence,
} from "@/components/common/approval-transition-dialog";
import { EditTtsTemplateDialog } from "@/components/tts-templates/edit-tts-template-dialog";
import { DeleteTtsTemplateDialog } from "@/components/tts-templates/delete-tts-template-dialog";
import {
  approveTtsTemplate,
  rejectTtsTemplate,
  ttsTemplatesKeys,
} from "@/lib/api/tts-templates";
import type { TtsTemplateResponse } from "@/lib/api/contracts";
import { canTransition, type ApprovalTransition } from "@/lib/domain/approval";

/**
 * The four write actions on one TTS template, plus their dialogs.
 *
 * ## The scope rule is per-resource, not per-user
 *
 * VERIFIED `TtsTemplateService.manageCheckFor` (L239-243) and `transition`
 * (L223):
 *
 * ```java
 * return entity.getScope() == GLOBAL ? AccessCheck.platformWide()
 *                                     : AccessCheck.forTenant(entity.getTenantId());
 * ```
 *
 * So a **GLOBAL** row needs platform scope for *every* write — edit, delete,
 * approve and reject alike. A reseller administrator can read the shared
 * catalog and would get a 403 on all four if the buttons were shown, so the UI
 * resolves the scope with the caller and the row together
 * (`canManageTtsTemplate` / `canApproveTtsTemplate`) instead of checking a bare
 * capability.
 *
 * A **TENANT** row needs `TTS_MANAGE` / `TTS_APPROVE` on the owning tenant, and
 * a reseller-scoped assignment satisfies `forTenant(T)` for T in their
 * hierarchy (`AuthorizationService.coversReseller` L150-166) — so a reseller
 * administrator *does* get edit, delete, approve and reject on their own
 * tenants' templates. V20 grants `RESELLER_ADMIN` all three `TTS_*` keys.
 *
 * Both halves are asserted in `content-gates.test.ts`, because the two failure
 * modes here are symmetric and both are wrong: showing platform-only actions to
 * a reseller, and hiding a tenant's actions from one.
 *
 * The same component serves the list row and the detail page, so the rules are
 * stated once.
 */
export function TtsTemplateActions({
  template,
  canManage,
  canApprove,
  variant = "full",
}: {
  template: TtsTemplateResponse;
  canManage: boolean;
  canApprove: boolean;
  variant?: "full" | "row";
}) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [transition, setTransition] = useState<ApprovalTransition | null>(null);

  const approve = useMutation({
    mutationFn: () => approveTtsTemplate(template.id),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: ttsTemplatesKeys.all });
      toast.success("Template approved", {
        description: `${updated.name} can now be used by campaigns.`,
      });
    },
  });

  const reject = useMutation({
    mutationFn: () => rejectTtsTemplate(template.id),
    onSuccess: async (updated) => {
      await queryClient.invalidateQueries({ queryKey: ttsTemplatesKeys.all });
      toast.success("Template rejected", {
        description: `${updated.name} can no longer be used by campaigns.`,
      });
    },
  });

  const compact = variant === "row";
  const buttonSize = compact ? "sm" : "default";
  const canRejectAction = canApprove && canTransition(template.status, "reject");
  const canApproveAction =
    canApprove && canTransition(template.status, "approve");

  async function confirmTransition() {
    const next = transition;
    if (!next) return;
    await (next === "approve" ? approve : reject).mutateAsync();
    setTransition(null);
  }

  return (
    <>
      {canRejectAction ? (
        <Button
          variant="ghost"
          size={buttonSize}
          onClick={() => setTransition("reject")}
          title={`Reject ${template.name}`}
        >
          <XIcon aria-hidden="true" className="mr-1 h-4 w-4" />
          {compact ? <span className="sr-only">Reject</span> : "Reject"}
        </Button>
      ) : null}

      {canApproveAction ? (
        <Button
          variant="ghost"
          size={buttonSize}
          onClick={() => setTransition("approve")}
          title={`Approve ${template.name}`}
        >
          <CheckIcon aria-hidden="true" className="mr-1 h-4 w-4" />
          {compact ? <span className="sr-only">Approve</span> : "Approve"}
        </Button>
      ) : null}

      {canManage ? (
        <>
          <Button
            variant="ghost"
            size={buttonSize}
            onClick={() => setEditing(true)}
            title={`Edit ${template.name}`}
          >
            <PencilIcon aria-hidden="true" className="mr-1 h-4 w-4" />
            {compact ? <span className="sr-only">Edit</span> : "Edit"}
          </Button>
          <Button
            variant="ghost"
            size={buttonSize}
            className="text-destructive hover:bg-destructive/10"
            onClick={() => setDeleting(true)}
            title={`Delete ${template.name}`}
          >
            <Trash2Icon aria-hidden="true" className="mr-1 h-4 w-4" />
            {compact ? <span className="sr-only">Delete</span> : "Delete"}
          </Button>
        </>
      ) : null}

      {editing ? (
        <EditTtsTemplateDialog
          key={`edit-${template.id}`}
          template={template}
          open={editing}
          onOpenChange={(open) => {
            if (!open) setEditing(false);
          }}
        />
      ) : null}

      {deleting ? (
        <DeleteTtsTemplateDialog
          template={template}
          open={deleting}
          onOpenChange={(open) => {
            if (!open) setDeleting(false);
          }}
          onDeleted={() => router.push("/tts-templates")}
        />
      ) : null}

      {transition ? (
        <ApprovalTransitionDialog
          key={`transition-${template.id}-${transition}`}
          open={transition !== null}
          onOpenChange={(open) => {
            if (!open) setTransition(null);
          }}
          subject="TTS template"
          label={template.name}
          status={template.status}
          transition={transition}
          consequence={approvalConsequence("tts", transition)}
          onConfirm={confirmTransition}
        />
      ) : null}
    </>
  );
}
