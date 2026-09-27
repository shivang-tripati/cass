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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { toApiError } from "@/lib/api/error";
import type { ContactGroupResponse } from "@/lib/api/contracts";
import { contactGroupsKeys, importContacts, exportContacts } from "@/lib/api/contact-groups";
import type { ContactImportError } from "@/lib/api/contracts";

const FORMAT_OPTIONS = [
  { value: "csv", label: "CSV" },
  { value: "xlsx", label: "Excel (.xlsx)" },
  { value: "json", label: "JSON" },
] as const;

interface ImportContactsDialogProps {
  group: ContactGroupResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ImportContactsDialog({ group, open, onOpenChange }: ImportContactsDialogProps) {
  const queryClient = useQueryClient();
  const [alert, setAlert] = useState<string | null>(null);
  const [errors, setErrors] = useState<ContactImportError[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [exportFormat, setExportFormat] = useState<"csv" | "xlsx" | "json">("csv");
  const [showExport, setShowExport] = useState(false);
  const [importing, setImporting] = useState(false);
  const [exporting, setExporting] = useState(false);

  if (!group) return null;

  async function handleImport() {
    if (!selectedFile) {
      setAlert("Please select a file to import.");
      return;
    }
    if (!group) return;

    setAlert(null);
    setErrors([]);
    setImporting(true);

    try {
      const result = await importContacts(group.id, selectedFile);
      toast.success("Import completed", {
        description: `${result.created} contacts imported, ${result.skipped} skipped (${result.duplicateCount} duplicates, ${result.errorCount} errors).`,
      });
      setErrors(result.errors);
      if (result.skipped > 0) {
        setAlert(`${result.skipped} rows skipped. See details below.`);
      }
      await queryClient.invalidateQueries({ queryKey: contactGroupsKeys.all });
      setSelectedFile(null);
    } catch (error) {
      const apiError = toApiError(error);
      setAlert(apiError.message);
    } finally {
      setImporting(false);
    }
  }

  async function handleExport() {
    if (!group) return;
    setExporting(true);
    try {
      const blob = await exportContacts(group.id, exportFormat);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      const ext = exportFormat === "xlsx" ? "xlsx" : exportFormat;
      a.download = `contacts-${group.id}.${ext}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      toast.success("Export downloaded");
    } catch (error) {
      const apiError = toApiError(error);
      toast.error(apiError.message);
    } finally {
      setExporting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-hidden">
        <DialogHeader>
          <DialogTitle>Import / Export Contacts</DialogTitle>
          <DialogDescription>
            Manage contacts for <strong>{group.name}</strong>. Import from CSV, XLSX, or JSON.
            Maximum 5 MB / 5,000 rows per import.
          </DialogDescription>
        </DialogHeader>

        {alert ? (
          <div className="mx-6 mb-4 p-3 text-sm font-medium text-destructive bg-destructive/10 rounded">
            {alert}
          </div>
        ) : null}

        <div className="flex flex-col h-[calc(100%-120px)] overflow-y-auto px-6 pb-6">
          <div className="border-b pb-6">
            <h4 className="font-medium mb-3">Import Contacts</h4>
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">
                Required column: <code>phoneNumber</code> (E.164 format). Optional columns:{' '}
                <code>firstName</code>, <code>lastName</code>, <code>email</code>,{' '}
                <code>attributes</code> (JSON object string).
              </p>
              <input
                type="file"
                accept=".csv,.xlsx,.json"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  if (file) {
                    const validTypes = [".csv", ".xlsx", ".json"];
                    const ext = "." + file.name.split(".").pop()?.toLowerCase();
                    if (!validTypes.includes(ext)) {
                      setAlert("Invalid file type. Supported: .csv, .xlsx, .json");
                      return;
                    }
                    if (file.size > 5 * 1024 * 1024) {
                      setAlert("File size exceeds 5 MB limit.");
                      return;
                    }
                    setSelectedFile(file);
                    setAlert(null);
                  }
                }}
                className="block w-full text-sm"
              />
              {selectedFile && (
                <p className="text-sm text-muted-foreground">
                  Selected: {selectedFile.name} ({(selectedFile.size / 1024).toFixed(1)} KB)
                </p>
              )}
              <Button
                variant="default"
                onClick={handleImport}
                disabled={!selectedFile || importing}
              >
                {importing ? (
                  <>
                    <Spinner aria-hidden="true" />
                    Importing…
                  </>
                ) : (
                  "Import Contacts"
                )}
              </Button>
            </div>

            {errors.length > 0 && (
              <div className="mt-6 p-4 bg-destructive/10 rounded-lg">
                <h5 className="font-medium text-destructive mb-2">
                  Import Issues ({errors.length} reported)
                </h5>
                <div className="max-h-60 overflow-y-auto text-sm">
                  <table className="w-full text-left">
                    <thead>
                      <tr className="border-b">
                        <th className="pb-1 pr-4 text-left">Row</th>
                        <th className="pb-1 pr-4 text-left">Field</th>
                        <th className="pb-1 pr-4 text-left">Code</th>
                        <th className="pb-1 text-left">Message</th>
                      </tr>
                    </thead>
                      <tbody>
                        {errors.slice(0, 50).map((err, idx) => (
                          <tr key={idx} className="border-b">
                            <td className="py-1 pr-4 font-mono">{err.rowNumber}</td>
                            <td className="py-1 pr-4">{err.field}</td>
                            <td className="py-1 pr-4 font-mono">{err.code}</td>
                            <td className="py-1">{err.message}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {errors.length > 50 && (
                      <p className="mt-2 text-xs text-muted-foreground">
                        Showing first 50 of {errors.length} errors.
                      </p>
                    )}
                  </div>
                </div>
              )}
            </div>

            <div className="border-t my-6 pt-6">
              <div className="flex items-center justify-between mb-4">
                <h4 className="font-medium">Export Contacts</h4>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setShowExport(!showExport)}
                >
                  {showExport ? "Hide Export" : "Show Export"}
                </Button>
              </div>

              {showExport && (
                <div className="space-y-4 p-4 border rounded-lg bg-muted/30">
                  <div>
                    <Label className="block text-sm font-medium mb-2">Format</Label>
                    <Select
                      value={exportFormat}
                      onValueChange={(v: "csv" | "xlsx" | "json") => setExportFormat(v)}
                    >
                      <SelectTrigger className="w-full max-w-xs">
                        <SelectValue placeholder="Select format" />
                      </SelectTrigger>
                      <SelectContent>
                        {FORMAT_OPTIONS.map((opt) => (
                          <SelectItem key={opt.value} value={opt.value}>
                            {opt.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <Button
                    variant="outline"
                    onClick={handleExport}
                    disabled={exporting}
                  >
                    {exporting ? (
                      <>
                        <Spinner aria-hidden="true" />
                        Exporting…
                      </>
                    ) : (
                      `Download ${exportFormat.toUpperCase()}`
                    )}
                  </Button>
                </div>
              )}
            </div>
          </div>

        <DialogFooter className="mt-auto">
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
            disabled={importing || exporting}
          >
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}