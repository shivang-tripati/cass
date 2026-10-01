"use client";

import { useState } from "react";
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import type { ContactGroupResponse } from "@/lib/api/contracts";
import { exportContacts } from "@/lib/api/contacts";

/**
 * Download a group's contacts as a file.
 *
 * F2 — extracted from the combined import/export dialog so the group LIST can
 * offer Export per row without dragging an import form along with it. One file
 * per operation beats one file with two modes.
 *
 * VERIFIED contract (`ContactGroupService.exportContacts`, controller L400-409):
 *  - `GET /contact-groups/{id}/contacts/export?format=csv|xlsx|json`
 *  - requires `CONTACT_VIEW` — the same capability needed to see the list, so
 *    this action is always available to a user who can see the row. It is NOT
 *    gated on `CONTACT_EXPORT`, which exists in the catalogue but is not
 *    enforced by the service.
 *  - the response is a raw file download, not the `ApiResponse` envelope;
 *  - `Content-Disposition` carries the server's filename;
 *  - columns are `phoneNumber, firstName, lastName, email, attributes` only —
 *    no ownership, no audit fields;
 *  - an unsupported format is a 400 from the service, not a silent CSV.
 */
const FORMAT_OPTIONS = [
  { value: "csv", label: "CSV" },
  { value: "xlsx", label: "Excel (.xlsx)" },
  { value: "json", label: "JSON" },
] as const;

type ExportFormat = (typeof FORMAT_OPTIONS)[number]["value"];

export function ExportContactsDialog({
  group,
  open,
  onOpenChange,
}: {
  group: ContactGroupResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [format, setFormat] = useState<ExportFormat>("csv");
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);

  function handleOpenChange(next: boolean) {
    if (exporting) return;
    if (!next) setError(null);
    onOpenChange(next);
  }

  async function handleExport() {
    setError(null);
    setExporting(true);
    try {
      const download = await exportContacts(group.id, format);
      const href = URL.createObjectURL(download.blob);
      const anchor = document.createElement("a");
      anchor.href = href;
      // Prefer the server's filename; only fall back if the header was absent.
      anchor.download = download.filename ?? `contacts-${group.id}.${format}`;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      // Revoked after the click has been dispatched, not before — revoking
      // first can cancel the download in some browsers.
      URL.revokeObjectURL(href);
      toast.success("Export downloaded", {
        description: download.filename ?? `${format.toUpperCase()} file`,
      });
      onOpenChange(false);
    } catch (e) {
      setError(toApiError(e).message);
    } finally {
      setExporting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Export contacts</DialogTitle>
          <DialogDescription>
            Download the {group.memberCount} contact
            {group.memberCount === 1 ? "" : "s"} currently in{" "}
            <strong>{group.name}</strong>. The file contains contact fields only —
            no group, tenant or audit data.
          </DialogDescription>
        </DialogHeader>

        {error ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {error}
          </p>
        ) : null}

        <div>
          <Label htmlFor="contact-export-format" className="mb-2 block text-sm font-medium">
            Format
          </Label>
          <Select
            value={format}
            onValueChange={(value) => setFormat(value as ExportFormat)}
          >
            <SelectTrigger id="contact-export-format" className="w-full">
              <SelectValue placeholder="Select format" />
            </SelectTrigger>
            <SelectContent>
              {FORMAT_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={exporting}
          >
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => void handleExport()}
            disabled={exporting}
          >
            {exporting ? (
              <>
                <Spinner className="size-4" />
                Exporting…
              </>
            ) : (
              "Download"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
