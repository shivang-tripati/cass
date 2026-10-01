"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

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
import { contactGroupsKeys } from "@/lib/api/contact-groups";
import { contactGroupMembersKeys, removeContactGroupMember } from "@/lib/api/contact-group-members";
import { contactsKeys } from "@/lib/api/contacts";
import type { ContactResponse } from "@/lib/api/contracts";

/**
 * Remove a contact from ONE group, keeping the contact itself.
 *
 * F2 — this is the operation F0 and the previous UI did not have, and getting it
 * wrong is data loss. The two deletions are materially different:
 *
 *   `DELETE /contact-groups/{g}/contacts/{c}`  soft-deletes the contact IDENTITY
 *     and removes it from EVERY group it belongs to.
 *   `DELETE /contact-groups/{g}/members/{c}`   removes only the (group, contact)
 *     RELATIONSHIP. The contact survives, stays live, and remains a member of
 *     any other group. VERIFIED idempotent: a missing membership is a
 *     successful no-op returning 204.
 *
 * The dialog names the difference explicitly so the user chooses deliberately.
 *
 * Both deletions are CONTACT_MANAGE; the "remove from group" wording is kept
 * distinct from "delete contact" throughout.
 */
export function RemoveFromGroupDialog({
  groupId,
  contact,
  open,
  onOpenChange,
  /** Group display name, so the consequence can be stated in plain language. */
  groupName,
}: {
  groupId: string;
  contact: ContactResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  groupName: string;
}) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => removeContactGroupMember(groupId, contact.id),
    onSuccess: async () => {
      // The contact leaves this group's contacts list AND its member roster, and
      // `memberCount` on the group changes — so all three are invalidated.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: contactsKeys.forGroup(groupId) }),
        queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
      ]);
      toast.success("Removed from group", {
        description: `${contact.firstName ?? contact.lastName ?? contact.phoneNumber} is no longer in ${groupName}. The contact itself was not deleted.`,
      });
      setError(null);
      onOpenChange(false);
    },
    onError: (e: unknown) => {
      const apiError = toApiError(e);
      setError(
        apiError.requestId
          ? `${apiError.message} (Request ID: ${apiError.requestId})`
          : apiError.message,
      );
    },
  });

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!mutation.isPending) {
          if (!next) setError(null);
          onOpenChange(next);
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Remove from this group?</DialogTitle>
          <DialogDescription>
            {contact.firstName ?? contact.lastName ?? contact.phoneNumber} will
            no longer be a member of <strong>{groupName}</strong>. The contact
            itself is kept and stays available to your organization, including
            in any other group.
          </DialogDescription>
        </DialogHeader>

        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {error}
          </p>
        ) : null}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={mutation.isPending}
          >
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => {
              setError(null);
              mutation.mutate();
            }}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? <Spinner className="size-4" /> : null}
            {mutation.isPending ? "Removing…" : "Remove from group"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
