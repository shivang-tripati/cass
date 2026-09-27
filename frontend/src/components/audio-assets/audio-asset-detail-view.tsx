"use client";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon } from "lucide-react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { audioAssetsKeys, getAudioAsset } from "@/lib/api/audio-assets";
import { AudioAssetStatusBadge } from "@/components/common/audio-asset-status-badge";
import { formatDateTime } from "@/lib/format";

export function AudioAssetDetailView({ id }: { id: string }){
  const q = useQuery({ queryKey: audioAssetsKeys.detail(id), queryFn: ()=> getAudioAsset(id) });
  if(q.isPending) return <div className="p-8">Loading…</div>;
  if(q.error) return <div className="p-8 text-destructive">Not found or no permission.</div>;
  const a = q.data!;
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <Button variant="ghost" asChild><Link href="/audio-assets"><ArrowLeftIcon /> Back</Link></Button>
      <div className="flex items-center gap-2"><h1 className="text-2xl font-semibold">{a.name}</h1><AudioAssetStatusBadge status={a.status} /></div>
      {a.description && <p className="text-muted-foreground">{a.description}</p>}
      <Card><CardHeader><CardTitle>File</CardTitle></CardHeader><CardContent className="space-y-1 text-sm">
        <div>File name: <code>{a.fileName}</code></div>
        <div>Content type: {a.contentType}</div>
        <div>Size: {a.fileSize} bytes</div>
        {a.durationSeconds && <div>Duration: {a.durationSeconds}s</div>}
        {a.checksum && <div>Checksum: <code className="break-all">{a.checksum}</code></div>}
        {a.storageReference && <div>Storage ref: {a.storageReference}</div>}
      </CardContent></Card>
      <Card><CardHeader><CardTitle>Meta</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground">
        <div>Tenant: {a.tenantId}</div>
        <div>Created: {formatDateTime(a.createdAt)}</div>
        <div>Updated: {formatDateTime(a.updatedAt)}</div>
      </CardContent></Card>
    </div>
  );
}
