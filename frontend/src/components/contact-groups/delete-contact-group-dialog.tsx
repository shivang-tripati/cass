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
import { contactGroupsKeys, deleteContactGroup } from "@/lib/api/contact-groups";
import { contactGroupMembersKeys } from "@/lib/api/contact-group-members";
import { contactsKeys } from "@/lib/api/contacts";
import type { ContactGroupResponse } from "@/lib/api/contracts";

/**
 * Delete a contact group.
 *
 * F2 — replaces a bare `confirm()` plus a bespoke 409 branch. Two corrections
 * matter here, and both come from reading `ContactGroupService.deleteGroup`
 * (L171-181) rather than the controller's OpenAPI annotation:
 *
 *  1. **There is no 409.** The `@ApiResponse(responseCode = "409", …,
 *     description = "Group still contains contacts")` on the DELETE mapping is
 *     stale. The service calls `memberService.removeAllForGroup(groupId)` and
 *     then soft-deletes the group; the only `ConflictException` in the file is
 *     the contact identity race at L194. The service Javadoc is explicit:
 *     *"A group with live members can be deleted — the membership links are
 *     what die, not the identities."* The 409 branch below is retained purely
 *     as defence; it is not an expected path and the dialog does not advertise
 *     it.
 *
 *  2. **The consequence is partial, and the old message got it backwards.** The
 *     old confirm text said *"The group must be empty (no contacts)"* — which is
 *     both wrong (no such requirement exists) and alarming in the wrong
 *     direction. What actually happens: the group's MEMBERSHIP ROWS are
 *     physically removed, but the CONTACTS are tenant-level identities that
 *     survive, stay live, and remain members of any other group. The count in
 *     this dialog is the backend's own `memberCount`, never a client-side tally.
 *
 * The delete is server-confirmed (F2 §37: no optimistic updates); the button is
 * disabled while pending and the dialog cannot be dismissed mid-flight.
 */
export function DeleteContactGroupDialog({
  group,
  open,
  onOpenChange,
}: {
  group: ContactGroupResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => deleteContactGroup(group.id),
    onSuccess: async () => {
      // Memberships are gone, so the group's `memberCount` changes and every
      // roster cached anywhere in the app is stale. Invalidate all three.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactsKeys.all }),
      ]);
      toast.success("Contact group deleted", {
        description:
          group.memberCount > 0
            ? `${group.name} was deleted. Its ${group.memberCount} membership${
                group.memberCount === 1 ? " was" : "s were"
              } removed; the contacts themselves were kept.`
            : `${group.name} was deleted.`,
      });
      setError(null);
      onOpenChange(false);
      // Any open detail/member/contact view under this group is now a 404.
      router.push("/contact-groups");
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
          <DialogTitle>Delete this contact group?</DialogTitle>
          <DialogDescription>
            <strong>{group.name}</strong> will no longer be available.
            {group.memberCount > 0 ? (
              <>
                {" "}
                Its {group.memberCount} membership
                {group.memberCount === 1 ? "" : "s"} will be removed, so the
                group will no longer be an audience for any campaign.
              </>
            ) : null}
          </DialogDescription>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          The contacts themselves are not deleted. They stay in your
          organization and remain in any other group they belong to — only this
          group and its links to them go away.
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
            {mutation.isPending ? "Deleting…" : "Delete group"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
