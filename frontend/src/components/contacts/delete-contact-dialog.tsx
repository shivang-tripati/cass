"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
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
import { contactGroupMembersKeys } from "@/lib/api/contact-group-members";
import { contactsKeys, deleteContact } from "@/lib/api/contacts";
import type { ContactResponse } from "@/lib/api/contracts";

/**
 * Delete a contact.
 *
 * F2 — the previous implementation used a bare `confirm()` plus
 * `window.location.reload()`. This is a real dialog that states the VERIFIED
 * consequence, which is the part that matters:
 *
 * `DELETE /contact-groups/{groupId}/contacts/{contactId}` soft-deletes the
 * contact IDENTITY, not just this group's link to it. All of its memberships are
 * physically removed, in every group in the tenant, and its phone number becomes
 * re-creatable (ContactGroupService.deleteContact).
 *
 * To keep the contact and only drop it from this one group, use
 * `RemoveFromGroupDialog` instead. Both are offered so the choice is explicit.
 *
 * Deletes are confirmed server-side, never optimistic (F2 §37): the dialog waits
 * for the 204, and a failure is shown rather than assumed.
 */
export function DeleteContactDialog({
  groupId,
  groupName,
  contact,
  open,
  onOpenChange,
}: {
  groupId: string;
  groupName: string;
  contact: ContactResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  const label = contact.firstName ?? contact.lastName ?? contact.phoneNumber;

  const mutation = useMutation({
    mutationFn: () => deleteContact(groupId, contact.id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: contactsKeys.forGroup(groupId) }),
        queryClient.invalidateQueries({ queryKey: contactsKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
      ]);
      toast.success("Contact deleted", {
        description: `${label} was removed from every group.`,
      });
      setError(null);
      onOpenChange(false);
      // Any page showing this contact is now stale, including a detail view.
      router.push(`/contact-groups/${groupId}/contacts`);
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
          <DialogTitle>Delete this contact?</DialogTitle>
          <DialogDescription>
            <strong>{label}</strong> will be deleted from your organization, not
            just from {groupName}. It will be removed from every group it belongs
            to, and its phone number becomes available for a new contact.
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
            variant="destructive"
            onClick={() => {
              setError(null);
              mutation.mutate();
            }}
            disabled={mutation.isPending}
          >
            {mutation.isPending ? <Spinner className="size-4" /> : null}
            {mutation.isPending ? "Deleting…" : "Delete contact"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
