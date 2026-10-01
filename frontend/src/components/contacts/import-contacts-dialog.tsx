"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
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
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import type {
  ContactGroupResponse,
  ContactImportError,
  ContactImportResponse,
} from "@/lib/api/contracts";
import { contactGroupsKeys } from "@/lib/api/contact-groups";
import { contactGroupMembersKeys } from "@/lib/api/contact-group-members";
import { contactsKeys, importContacts } from "@/lib/api/contacts";

/**
 * Bulk import for one contact group.
 *
 * F2 — this is now the ONLY import implementation. The repo carried two
 * byte-near identical copies (`components/contacts/import-contacts-dialog.tsx`
 * and `components/contact-groups/import-contacts-dialog.tsx`) with different
 * prop names, and the group copy imported `importContacts` / `exportContacts`
 * from `lib/api/contact-groups`, where they no longer live. A file existing in
 * two places is a file that will be fixed in one place.
 *
 * The export half moved to `export-contacts-dialog.tsx` so the group LIST can
 * offer Export per row without opening an import form.
 *
 * The behaviours encoded here are all VERIFIED, and each one changes what the
 * user should expect:
 *
 *  - **Import is `CONTACT_MANAGE`.** The controller's own `@ApiResponse`
 *    annotation says so (L377). The catalogue also contains `CONTACT_IMPORT`,
 *    granted to the same roles, but the service does NOT enforce it — gating on
 *    it would be an invented permission. That is why `canImport` is passed in
 *    from the caller rather than re-derived here.
 *  - **Import is per-row, and partial success is normal.** Every row is
 *    validated individually; duplicates within the file and against existing
 *    live tenant identities are SKIPPED and reported, not rejected. So
 *    "1,900 created, 100 skipped" is a success, and the dialog says so.
 *  - **`errors` is capped at 100 rows** (`MAX_REPORTED_ERRORS`), so
 *    `errors.length` is NOT `errorCount` and the UI must not claim otherwise.
 *  - **Extra columns are ignored.** The readers look up only `phoneNumber`,
 *    `firstName`, `lastName`, `email`, `attributes`; a `tenantId` column in the
 *    file has no effect because ownership comes exclusively from the group.
 *  - **A duplicate row in the file does not create a second contact.** The
 *    later row is reported as `DUPLICATE_PHONE` and the earlier row's identity
 *    gets the membership — so re-importing the same file is safe.
 */
const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
const MAX_IMPORT_ROWS = 5000;
/** VERIFIED: `MAX_REPORTED_ERRORS` — the server truncates the list here. */
const MAX_REPORTED_ERRORS = 100;

const ACCEPTED_EXTENSIONS = [".csv", ".xlsx", ".json"] as const;

function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot === -1 ? "" : fileName.slice(dot).toLowerCase();
}

/** Human summary of an import result. `skipped` = duplicates + invalid rows. */
function describeImportResult(result: ContactImportResponse): string {
  const parts = [`${result.created} created`];
  if (result.duplicateCount > 0) parts.push(`${result.duplicateCount} duplicate`);
  if (result.errorCount > 0) parts.push(`${result.errorCount} invalid`);
  const skipped = result.skipped > 0 ? `, ${result.skipped} skipped` : "";
  return `${result.totalRows} rows read — ${parts.join(", ")}${skipped}.`;
}

export function ImportContactsDialog({
  group,
  open,
  onOpenChange,
  /** Import requires CONTACT_MANAGE — the capability the service enforces. */
  canImport,
}: {
  group: ContactGroupResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  canImport: boolean;
}) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);
  const [errors, setErrors] = useState<ContactImportError[]>([]);
  const [result, setResult] = useState<ContactImportResponse | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);

  const busy = importing;

  function handleOpenChange(next: boolean) {
    // F2 §38: the dialog cannot be dismissed mid-flight, so a slow import cannot
    // be left running against a group the user has navigated away from.
    if (busy) return;
    if (!next) {
      setAlert(null);
      setErrors([]);
      setResult(null);
      setSelectedFile(null);
    }
    onOpenChange(next);
  }

  function selectFile(file: File | null | undefined) {
    if (!file) {
      setSelectedFile(null);
      return;
    }
    const ext = extensionOf(file.name);
    if (!(ACCEPTED_EXTENSIONS as readonly string[]).includes(ext)) {
      setAlert("Unsupported file type. Upload a .csv, .xlsx or .json file.");
      setSelectedFile(null);
      return;
    }
    if (file.size > MAX_IMPORT_BYTES) {
      setAlert(
        `File is ${(file.size / (1024 * 1024)).toFixed(1)} MB. The limit is 5 MB.`,
      );
      setSelectedFile(null);
      return;
    }
    setAlert(null);
    setErrors([]);
    setResult(null);
    setSelectedFile(file);
  }

  async function handleImport() {
    if (!selectedFile) {
      setAlert("Choose a file to import.");
      return;
    }
    setAlert(null);
    setErrors([]);
    setResult(null);
    setImporting(true);
    try {
      const imported = await importContacts(group.id, selectedFile);
      setResult(imported);
      setErrors(imported.errors);
      setSelectedFile(null);
      // Imported rows are new MEMBERSHIPS in this group, so the roster, the
      // contacts list and the group's memberCount all change.
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: contactsKeys.forGroup(group.id),
        }),
        queryClient.invalidateQueries({ queryKey: contactGroupMembersKeys.all }),
        queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all }),
      ]);
      toast.success("Import complete", { description: describeImportResult(imported) });
    } catch (error) {
      setAlert(toApiError(error).message);
    } finally {
      setImporting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="flex max-h-[90vh] max-w-2xl flex-col overflow-hidden">
        <DialogHeader>
          <DialogTitle>Import contacts</DialogTitle>
          <DialogDescription>
            Bulk-load contacts into <strong>{group.name}</strong>. Ownership always
            comes from this group — any owner column in the file is ignored.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 space-y-6 overflow-y-auto px-6">
          {alert ? (
            <p role="alert" className="rounded bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {alert}
            </p>
          ) : null}

          {/* ---------------------------------------------------------- import */}
          {canImport ? (
            <section className="space-y-3 border-b pb-6">
              <h4 className="font-medium">Import contacts</h4>
              <p className="text-sm text-muted-foreground">
                CSV, XLSX or JSON, up to 5 MB and {MAX_IMPORT_ROWS.toLocaleString()} rows.
                The <code>phoneNumber</code> column is required and must be E.164
                (for example <code>+918012345678</code>). Optional columns:{" "}
                <code>firstName</code>, <code>lastName</code>, <code>email</code>,{" "}
                <code>attributes</code> (a JSON object).
              </p>
              <div>
                <Label htmlFor="contact-import-file" className="text-sm font-medium">
                  File
                </Label>
                <input
                  id="contact-import-file"
                  type="file"
                  accept=".csv,.xlsx,.json"
                  disabled={busy}
                  onChange={(event) => selectFile(event.target.files?.[0])}
                  className="mt-1 block w-full text-sm disabled:opacity-60"
                />
              </div>
              {selectedFile ? (
                <p className="text-sm text-muted-foreground">
                  Selected: {selectedFile.name} (
                  {(selectedFile.size / 1024).toFixed(1)} KB)
                </p>
              ) : null}
              <Button onClick={handleImport} disabled={!selectedFile || busy}>
                {importing ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Importing…
                  </>
                ) : (
                  "Import contacts"
                )}
              </Button>
              <p className="text-xs text-muted-foreground">
                Each row is checked on its own. Rows that duplicate a number
                already in your organization, or that duplicate a row earlier in
                the same file, are skipped and listed below — the rest of the file
                still imports.
              </p>
            </section>
          ) : (
            <section className="space-y-1 border-b pb-6">
              <h4 className="font-medium">Import contacts</h4>
              <p className="text-sm text-muted-foreground">
                Importing is not available with your current permissions.
              </p>
            </section>
          )}

          {result ? (
            <section className="space-y-1" aria-live="polite">
              <h4 className="font-medium">Import result</h4>
              <p className="text-sm text-muted-foreground">
                {describeImportResult(result)}
              </p>
            </section>
          ) : null}

          {errors.length > 0 ? (
            <section className="rounded-lg bg-destructive/10 p-4">
              <h4 className="mb-2 font-medium text-destructive">
                Rows not imported ({errors.length} reported
                {result && result.skipped > errors.length
                  ? ` of ${result.skipped} skipped — the server reports at most ${MAX_REPORTED_ERRORS})`
                  : ""}
                )
              </h4>
              <div className="max-h-60 overflow-y-auto text-sm">
                <table className="w-full text-left">
                  <thead>
                    <tr className="border-b">
                      <th scope="col" className="pb-1 pr-4">Row</th>
                      <th scope="col" className="pb-1 pr-4">Field</th>
                      <th scope="col" className="pb-1">Reason</th>
                    </tr>
                  </thead>
                  <tbody>
                    {errors.map((rowError, index) => (
                      <tr key={`${rowError.rowNumber}-${index}`} className="border-b">
                        <td className="py-1 pr-4 font-mono">{rowError.rowNumber}</td>
                        <td className="py-1 pr-4 font-mono">{rowError.field}</td>
                        <td className="py-1">{rowError.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ) : null}

        </div>

        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => handleOpenChange(false)}
            disabled={busy}
          >
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
