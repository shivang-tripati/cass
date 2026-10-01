"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, FileAudioIcon, HardDriveIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { QueryErrorState } from "@/components/common/query-state";
import { ApprovalStatusBadge } from "@/components/common/approval-status-badge";
import { AudioAssetActions } from "@/components/audio-assets/audio-asset-actions";
import { formatBytes } from "@/components/audio-assets/audio-asset-table";
import { useCan } from "@/lib/auth/use-can";
import { canPerformAudioAction } from "@/lib/auth/content-gates";
import { audioAssetsKeys, getAudioAsset } from "@/lib/api/audio-assets";
import { formatDateTime } from "@/lib/format";

/**
 * One audio recording.
 *
 * ## Errors, no longer conflated
 *
 * The pre-F3 view rendered every failure as `<div>Not found or no permission.</div>`
 * — one string for 403, 404, 500 and a network drop, and a plain text node with
 * no retry. F3 uses the F1 shared `QueryErrorState`, which distinguishes them
 * and whose 404 wording already respects the backend's deliberate
 * 404-cloaking: `findVisible` returns "Audio asset not found" for a foreign
 * asset exactly as it does for a missing one, so the message says "does not
 * exist, or it is outside your organization" rather than claiming the asset does
 * not exist.
 *
 * ## There is no player, and that is the contract
 *
 * VERIFIED: `AudioAssetController` exposes no download, preview, playback or
 * media-URL endpoint, and `AudioAssetResponse` carries no URL. `MediaUriResolver`
 * resolves the stored reference to a FreeSWITCH *filesystem* path for the
 * telephony engine and is never exposed over HTTP. So there is no `<audio>`
 * element, no "open in new tab", and no blob fetch — the browser has no way to
 * reach the bytes through this API. The page says so plainly rather than
 * offering a dead control.
 *
 * ## What is deliberately not shown
 *
 *  - **`storageReference`** — a backend storage locator
 *    (`audio/{tenant}/{asset}/{file}`). Not a URL, meaningless to a user, and it
 *    discloses the deployment's storage layout.
 *  - **`tenantId`** — a raw UUID. The recording is in the caller's own
 *    organization; the page says that in words instead, as F2 did for contact
 *    groups.
 *  - **`checksum`** — the SHA-256 of the uploaded bytes. It is shown, because it
 *    is the one technical field that has a legitimate user-facing purpose:
 *    confirming that two uploads are the same recording. It is labelled as a
 *    fingerprint, not presented as something to configure.
 */
export function AudioAssetDetailView({ id }: { id: string }) {
  const query = useQuery({
    queryKey: audioAssetsKeys.detail(id),
    queryFn: () => getAudioAsset(id),
  });

  const { user } = useCan();
  const canManage = canPerformAudioAction(user, "write");
  const canApprove = canPerformAudioAction(user, "approve");

  if (query.isPending) {
    return (
      <div
        className="mx-auto flex w-full max-w-4xl items-center justify-center p-12"
        aria-busy="true"
      >
        <Spinner className="size-6" />
        <span className="sr-only">Loading recording…</span>
      </div>
    );
  }

  if (query.isError) {
    return (
      <div className="mx-auto w-full max-w-4xl space-y-4 p-6">
        <QueryErrorState
          error={query.error}
          entityLabel="this recording"
          onRetry={() => void query.refetch()}
        />
        <Button variant="ghost" asChild>
          <Link href="/audio-assets">
            <ArrowLeftIcon aria-hidden="true" />
            Back to audio assets
          </Link>
        </Button>
      </div>
    );
  }

  const asset = query.data;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div className="space-y-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/audio-assets">
            <ArrowLeftIcon aria-hidden="true" />
            Audio assets
          </Link>
        </Button>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{asset.name}</h1>
            <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
              <ApprovalStatusBadge status={asset.status} />
              <span>in your organization</span>
            </p>
          </div>
          <AudioAssetActions
            asset={asset}
            canManage={canManage}
            canApprove={canApprove}
          />
        </div>
      </div>

      {asset.description ? (
        <p className="text-sm text-muted-foreground">{asset.description}</p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <FileAudioIcon className="h-4 w-4" aria-hidden="true" />
            Audio file
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-3">
            <Row label="File name">
              <code className="text-sm">{asset.fileName}</code>
            </Row>
            <Row label="Content type">{asset.contentType}</Row>
            <Row label="Size">
              <span className="tabular-nums">{formatBytes(asset.fileSize)}</span>
              <span className="ml-2 text-muted-foreground">
                ({asset.fileSize.toLocaleString()} bytes)
              </span>
            </Row>
            <Row label="Duration">
              {/* `durationSeconds` is null for MP3 and for a WAV header the
                  backend could not parse — `AudioUploadValidator` never fails an
                  upload over optional metadata. A dash is the honest answer; the
                  pre-F3 `durationSeconds && …` hid a genuine 0 as well. */}
              {asset.durationSeconds !== null ? (
                <span className="tabular-nums">
                  {formatDuration(asset.durationSeconds)}
                </span>
              ) : (
                <Dash />
              )}
            </Row>
            <Row label="Fingerprint (SHA-256)">
              {asset.checksum ? (
                <code className="break-all text-xs">{asset.checksum}</code>
              ) : (
                <Dash />
              )}
            </Row>
          </dl>

          <p className="mt-4 flex items-start gap-2 rounded border bg-muted/40 p-3 text-sm text-muted-foreground">
            <HardDriveIcon
              aria-hidden="true"
              className="mt-0.5 size-4 shrink-0"
            />
            <span>
              There is no download or preview for recordings. The platform
              streams audio to the telephony engine directly; it does not expose
              the file to browsers. The details above are what the server
              recorded when you uploaded it.
            </span>
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>History</CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="space-y-3">
            <Row label="Uploaded">{formatDateTime(asset.createdAt)}</Row>
            <Row label="Last updated">
              {asset.updatedAt ? formatDateTime(asset.updatedAt) : <Dash />}
            </Row>
            <Row label="Recording ID">
              <code className="break-all text-xs">{asset.id}</code>
            </Row>
          </dl>
        </CardContent>
      </Card>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-sm sm:text-right">{children}</dd>
    </div>
  );
}

function Dash() {
  return <span className="text-muted-foreground">—</span>;
}

/** `m:ss`, or `h:mm:ss` past an hour. Integer seconds only — the backend stores
 *  a whole-second `Integer` derived from a best-effort WAV header parse. */
function formatDuration(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = seconds % 60;
  const padded = (value: number) => String(value).padStart(2, "0");
  return hours > 0
    ? `${hours}:${padded(minutes)}:${padded(secs)}`
    : `${minutes}:${padded(secs)}`;
}
