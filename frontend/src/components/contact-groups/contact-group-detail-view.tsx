"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeftIcon,
  CalendarIcon,
  CopyIcon,
  EditIcon,
  DownloadIcon,
  Trash2Icon,
  UploadIcon,
  UsersIcon,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import Link from "next/link";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
// import type { ContactGroupResponse } from "@/lib/api/contracts";
import { formatDateTime } from "@/lib/format";
import { getContactGroup, contactGroupsKeys, deleteContactGroup } from "@/lib/api/contact-groups";
import { ImportContactsDialog } from "@/components/contact-groups/import-contacts-dialog";

interface ContactGroupDetailViewProps {
  groupId: string;
}

export function ContactGroupDetailView({ groupId }: ContactGroupDetailViewProps) {
  const [importDialogOpen, setImportDialogOpen] = useState(false);

  const query = useQuery({
    queryKey: contactGroupsKeys.detail(groupId),
    queryFn: () => getContactGroup(groupId),
  });

  const group = query.data;

  if (query.isPending) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Contact Group</h1>
            <p className="text-muted-foreground">Loading…</p>
          </div>
        </div>
        <Card>
          <CardContent className="py-12">
            <div className="flex items-center justify-center gap-3">
              <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
              <span className="text-muted-foreground">Loading contact group details…</span>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!group) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold tracking-tight">Contact Group</h1>
            <p className="text-muted-foreground">Not found</p>
          </div>
        </div>
        <Card>
          <CardContent className="py-12 text-center">
            <p className="text-muted-foreground">Contact group not found or access denied.</p>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" asChild>
            <Link href="/contact-groups">
              <ArrowLeftIcon className="h-4 w-4" />
            </Link>
          </Button>
          <div>
            <h1 className="text-3xl font-bold tracking-tight">{group.name}</h1>
            <p className="text-muted-foreground">Tenant: {group.tenantId}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {/* Contact groups don't have a status field per backend contract */}
        </div>
      </div>

      {group.description && (
        <Card>
          <CardHeader>
            <CardTitle>Description</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="whitespace-pre-wrap">{group.description}</p>
          </CardContent>
        </Card>
      )}

      <div className="grid gap-4 md:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <CalendarIcon className="h-4 w-4" />
              Timestamps
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <dl className="space-y-2">
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">Created</dt>
                <dd className="text-sm text-right whitespace-nowrap">{formatDateTime(group.createdAt)}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-sm text-muted-foreground">Updated</dt>
                <dd className="text-sm text-right whitespace-nowrap">{formatDateTime(group.updatedAt)}</dd>
              </div>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <UsersIcon className="h-4 w-4" />
              Contacts
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center gap-2">
              <Button variant="outline" asChild>
                <a href={`/contact-groups/${group.id}/contacts`}>
                  <UsersIcon className="mr-2 h-4 w-4" />
                  View Contacts
                </a>
              </Button>
              <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
                <UploadIcon className="mr-2 h-4 w-4" />
                Import Contacts
              </Button>
              <Button variant="outline" onClick={() => handleExport("csv")}>
                <DownloadIcon className="mr-2 h-4 w-4" />
                Export CSV
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Actions</CardTitle>
          <CardDescription>
            Available operations for this contact group
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button variant="outline" asChild>
            <a href={`/contact-groups/${group.id}/edit`}>
              <EditIcon className="mr-2 h-4 w-4" />
              Edit
            </a>
          </Button>
          <Button variant="outline" onClick={() => navigator.clipboard.writeText(group.id)}>
            <CopyIcon className="mr-2 h-4 w-4" />
            Copy ID
          </Button>
          <Button variant="outline" onClick={() => setImportDialogOpen(true)}>
            <UploadIcon className="mr-2 h-4 w-4" />
            Import Contacts
          </Button>
          <Button variant="outline" onClick={() => handleExport("csv")}>
            <DownloadIcon className="mr-2 h-4 w-4" />
            Export CSV
          </Button>
          <Button variant="destructive" onClick={() => confirmDelete(group.id)}>
            <Trash2Icon className="mr-2 h-4 w-4" />
            Delete
          </Button>
        </CardContent>
      </Card>

      <ImportContactsDialog
        group={group}
        open={importDialogOpen}
        onOpenChange={setImportDialogOpen}
      />
    </div>
  );
}

function handleExport(format: "csv" | "xlsx" | "json") {
  // In a real implementation, this would call the export API
  // For now, we'll use the import dialog's export functionality
  console.log(`Export ${format} not yet implemented in detail view`);
}

function confirmDelete(groupId: string) {
  if (confirm("Are you sure you want to delete this contact group? This action cannot be undone. The group must be empty (no contacts).")) {
    deleteContactGroup(groupId)
      .then(() => {
        window.location.href = "/contact-groups";
      })
      .catch((error) => {
        alert(error.message);
      });
  }
}