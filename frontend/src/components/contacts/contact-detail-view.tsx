"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeftIcon,
  CalendarIcon,
  MailIcon,
  PencilIcon,
  PhoneIcon,
  UsersIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { QueryErrorState } from "@/components/common/query-state";
import { Spinner } from "@/components/ui/spinner";
import { useCan } from "@/lib/auth/use-can";
import { canPerformContactAction } from "@/lib/auth/contact-gates";
import { contactsKeys, getContact } from "@/lib/api/contacts";
import { formatDateTime } from "@/lib/format";
import { EditContactDialog } from "@/components/contacts/edit-contact-dialog";
import type { ContactResponse } from "@/lib/api/contracts";

/**
 * Single contact detail.
 *
 * F2 — this route previously did not exist. `ContactTable` linked to
 * `/contact-groups/{id}/contacts/{contactId}`, which resolved to the
 * `not-found` page, so every "View" button on a contact was a dead link. F2
 * adds the route backed by the real endpoint
 * `GET /api/v1/contact-groups/{id}/contacts/{contactId}`.
 *
 * VERIFIED: `ContactResponse` is nine fields and contains NO group id and NO
 * status/lifecycle field. The group is the parent route segment, and the group
 * name is shown by a separate group query rather than fabricated from the
 * contact.
 *
 * VERIFIED semantics stated to the user, because they are surprising: a contact
 * is a tenant-level identity, so it may belong to several groups, and deleting
 * it (from the contacts list) removes it from ALL of them. Unlinking from this
 * group alone is a membership operation.
 */
export function ContactDetailView({
  contactGroupId,
  contactId,
}: {
  contactGroupId: string;
  contactId: string;
}) {
  const { user } = useCan();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);

  const query = useQuery({
    queryKey: contactsKeys.detail(contactGroupId, contactId),
    queryFn: () => getContact(contactGroupId, contactId),
  });

  if (query.isPending) {
    return (
      <div className="mx-auto flex w-full max-w-3xl items-center justify-center p-12" aria-busy="true">
        <Spinner className="size-6" />
        <span className="sr-only">Loading contact…</span>
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="mx-auto w-full max-w-3xl space-y-4 p-6">
        <QueryErrorState
          error={query.error}
          entityLabel="this contact"
          onRetry={() => void query.refetch()}
        />
        <Button variant="ghost" asChild>
          <Link href={`/contact-groups/${contactGroupId}/contacts`}>
            <ArrowLeftIcon aria-hidden="true" />
            Back to contacts
          </Link>
        </Button>
      </div>
    );
  }

  const contact: ContactResponse = query.data;
  const attributes = contact.attributes;

  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" asChild>
          <Link href={`/contact-groups/${contactGroupId}/contacts`}>
            <ArrowLeftIcon className="h-4 w-4" />
            <span className="sr-only">Back to contacts</span>
          </Link>
        </Button>
        <div>
          <h1 className="text-3xl font-bold tracking-tight">
            {contact.firstName ?? contact.lastName ?? contact.phoneNumber}
          </h1>
          <p className="text-muted-foreground">Contact in this group</p>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-3">
            <Row label="Phone">
              <span className="flex items-center gap-2">
                <PhoneIcon aria-hidden="true" className="size-4 text-muted-foreground" />
                <code className="font-mono text-sm">{contact.phoneNumber}</code>
              </span>
            </Row>
            <Row label="First name">
              {contact.firstName ?? <Dash />}
            </Row>
            <Row label="Last name">
              {contact.lastName ?? <Dash />}
            </Row>
            <Row label="Email">
              {contact.email ? (
                <span className="flex items-center gap-2">
                  <MailIcon aria-hidden="true" className="size-4 text-muted-foreground" />
                  <span className="break-all">{contact.email}</span>
                </span>
              ) : (
                <Dash />
              )}
            </Row>
            <Row label="Contact ID">
              <code className="font-mono text-xs break-all">{contact.id}</code>
            </Row>
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <CalendarIcon className="h-4 w-4" />
            Timestamps
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-2">
            <TimeRow label="Created" value={contact.createdAt} />
            <TimeRow label="Updated" value={contact.updatedAt} />
          </dl>
        </CardContent>
      </Card>

      {attributes && Object.keys(attributes).length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Attributes</CardTitle>
          </CardHeader>
          <CardContent>
            <pre className="overflow-x-auto rounded-md bg-muted p-3 text-xs">
              {safeStringify(attributes)}
            </pre>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <UsersIcon className="h-4 w-4" />
            About this contact
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm text-muted-foreground">
          <p>
            A contact belongs to your organization, not to this group. It may also
            be a member of other groups in the same organization.
          </p>
          <p>
            Deleting a contact removes it from <em>every</em> group it belongs to.
            To remove it from this group only, use the contacts list and choose
            Remove from group.
          </p>
        </CardContent>
      </Card>

      <div className="flex flex-wrap gap-2">
        {canPerformContactAction(user, "write") && (
          <Button variant="outline" onClick={() => setEditing(true)}>
            <PencilIcon className="mr-2 h-4 w-4" />
            Edit
          </Button>
        )}
        <Button variant="outline" asChild>
          <Link href={`/contact-groups/${contactGroupId}`}>
            View group
          </Link>
        </Button>
      </div>

      {editing ? (
        <EditContactDialog
          contact={contact}
          contactGroupId={contactGroupId}
          onOpenChange={(open) => {
            if (!open) setEditing(false);
          }}
          onSaved={async () => {
            await Promise.all([
              queryClient.invalidateQueries({
                queryKey: contactsKeys.forGroup(contactGroupId),
              }),
              queryClient.invalidateQueries({
                queryKey: contactsKeys.detail(contactGroupId, contact.id),
              }),
            ]);
          }}
        />
      ) : null}
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-sm text-right break-words">{children}</dd>
    </div>
  );
}

function TimeRow({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="whitespace-nowrap text-sm">
        {value ? formatDateTime(value) : <Dash />}
      </dd>
    </div>
  );
}

function Dash() {
  return <span className="text-muted-foreground">—</span>;
}

/** Never throws while rendering: a cyclic or exotic value shows a placeholder
 * rather than crashing the page. */
function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2) ?? "—";
  } catch {
    return "—";
  }
}
