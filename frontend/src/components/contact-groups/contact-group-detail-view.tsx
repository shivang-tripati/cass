"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeftIcon,
  DownloadIcon,
  PencilIcon,
  Trash2Icon,
  UploadIcon,
  UsersIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { QueryErrorState } from "@/components/common/query-state";
import { useCan } from "@/lib/auth/use-can";
import { canPerformContactAction } from "@/lib/auth/contact-gates";
import { contactGroupsKeys, getContactGroup } from "@/lib/api/contact-groups";
import { formatDateTime } from "@/lib/format";
import { EditContactGroupDialog } from "@/components/contact-groups/edit-contact-group-dialog";
import { DeleteContactGroupDialog } from "@/components/contact-groups/delete-contact-group-dialog";
import { ContactGroupMembersPanel } from "@/components/contact-groups/contact-group-members-panel";
import { ImportContactsDialog } from "@/components/contacts/import-contacts-dialog";
import { ExportContactsDialog } from "@/components/contacts/export-contacts-dialog";

/**
 * One contact group: its metadata, its actions, and its member roster.
 *
 * F2 — this component previously contained four defects, all of them visible to
 * a user without any unusual conditions:
 *
 *  1. **A dead "Edit" link.** It pointed at `/contact-groups/{id}/edit`, a route
 *     that does not exist and never did. Editing is a dialog, so the link is now
 *     a button that opens it.
 *  2. **A raw tenant UUID on screen.** The old view rendered
 *     `Tenant: {group.tenantId}` as a field. `tenantId` IS on
 *     `ContactGroupResponse` (so the type is correct), but an internal identifier
 *     is not group information and there is nothing a user can do with it. The
 *     group is always in the caller's own authorized context, which is stated in
 *     words instead.
 *  3. **A hand-copied 403 block** and a `window.location.href` navigation after
 *     delete, which reloads the whole document. Both replaced: the shared F1
 *     `QueryErrorState`, and `router.push` from the delete dialog.
 *  4. **No capability gating.** Create/edit/delete/import all require
 *     `CONTACT_MANAGE`; the view rendered them for everyone.
 *
 * `memberCount` comes straight from the response. It is NOT recomputed from the
 * roster page — the backend computes it for the group in a single count query
 * (`countLiveByContactGroupId`), and counting a page of members in the browser
 * would report a page-sized number as the group's size.
 */
export function ContactGroupDetailView({ groupId }: { groupId: string }) {
  const { user } = useCan();
  // F2: resolved through the domain gate table so CONTACT_IMPORT /
  // CONTACT_EXPORT are never substituted for the keys the service
  // actually enforces.
  const canManage = canPerformContactAction(user, "write");

  const [editing, setEditing] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  const query = useQuery({
    queryKey: contactGroupsKeys.detail(groupId),
    queryFn: () => getContactGroup(groupId),
  });

  if (query.isPending) {
    return (
      <div
        className="mx-auto flex w-full max-w-5xl items-center justify-center p-12"
        aria-busy="true"
      >
        <Spinner className="size-6" />
        <span className="sr-only">Loading contact group…</span>
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="mx-auto w-full max-w-5xl space-y-4 p-6">
        <QueryErrorState
          error={query.error}
          entityLabel="this contact group"
          onRetry={() => void query.refetch()}
        />
        <Button variant="ghost" asChild>
          <Link href="/contact-groups">
            <ArrowLeftIcon aria-hidden="true" />
            Back to contact groups
          </Link>
        </Button>
      </div>
    );
  }

  const group = query.data;

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6">
      <div className="space-y-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/contact-groups">
            <ArrowLeftIcon aria-hidden="true" />
            Contact groups
          </Link>
        </Button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{group.name}</h1>
            <p className="text-sm text-muted-foreground">
              {group.memberCount} contact{group.memberCount === 1 ? "" : "s"} in
              this group
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            {/* Export is CONTACT_VIEW — the capability that already let this
                user see the page — so it is never gated away. */}
            <Button variant="outline" size="sm" onClick={() => setExportOpen(true)}>
              <DownloadIcon aria-hidden="true" />
              Export
            </Button>
            {canManage ? (
              <>
                <Button variant="outline" size="sm" onClick={() => setImportOpen(true)}>
                  <UploadIcon aria-hidden="true" />
                  Import
                </Button>
                <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                  <PencilIcon aria-hidden="true" />
                  Edit
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  className="text-destructive hover:bg-destructive/10"
                  onClick={() => setDeleteOpen(true)}
                >
                  <Trash2Icon aria-hidden="true" />
                  Delete
                </Button>
              </>
            ) : null}
          </div>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-3">
            <Row label="Name">{group.name}</Row>
            <Row label="Description">
              {group.description ?? <Dash />}
            </Row>
            <Row label="Contacts">
              <span className="inline-flex items-center gap-1.5">
                <UsersIcon aria-hidden="true" className="size-4 text-muted-foreground" />
                <span className="tabular-nums">{group.memberCount}</span>
                <Button variant="link" size="sm" className="h-auto p-0" asChild>
                  <Link href={`/contact-groups/${group.id}/contacts`}>
                    manage contacts
                  </Link>
                </Button>
              </span>
            </Row>
            <Row label="Created">{formatDateTime(group.createdAt)}</Row>
            <Row label="Updated">
              {group.updatedAt ? formatDateTime(group.updatedAt) : <Dash />}
            </Row>
          </dl>
          <p className="mt-4 text-sm text-muted-foreground">
            This group belongs to your organization. Its contacts are organization
            identities and may also belong to other groups.
          </p>
        </CardContent>
      </Card>

      <ContactGroupMembersPanel group={group} />

      {editing ? (
        <EditContactGroupDialog
          group={group}
          onOpenChange={(open) => {
            if (!open) setEditing(false);
          }}
        />
      ) : null}

      {importOpen ? (
        <ImportContactsDialog
          group={group}
          open={importOpen}
          canImport={canManage}
          onOpenChange={setImportOpen}
        />
      ) : null}

      {exportOpen ? (
        <ExportContactsDialog
          group={group}
          open={exportOpen}
          onOpenChange={setExportOpen}
        />
      ) : null}

      {deleteOpen ? (
        <DeleteContactGroupDialog
          group={group}
          open={deleteOpen}
          onOpenChange={setDeleteOpen}
        />
      ) : null}
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="break-words text-right text-sm">{children}</dd>
    </div>
  );
}

function Dash() {
  return <span className="text-muted-foreground">—</span>;
}
