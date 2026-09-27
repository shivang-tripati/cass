"use client";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ArrowLeftIcon } from "lucide-react";
import { ttsTemplatesKeys, getTtsTemplate } from "@/lib/api/tts-templates";
import { TtsTemplateStatusBadge } from "@/components/common/tts-template-status-badge";
import { formatDateTime } from "@/lib/format";
export function TtsTemplateDetailView({ id }: { id: string }){
  const q = useQuery({ queryKey: ttsTemplatesKeys.detail(id), queryFn: ()=>getTtsTemplate(id) });
  if(q.isPending) return <div className="p-8">Loading…</div>;
  if(q.error) return <div className="p-8 text-destructive">Not found or no permission.</div>;
  const t = q.data!;
  return (
    <div className="mx-auto max-w-3xl space-y-4">
      <Button variant="ghost" asChild><Link href="/tts-templates"><ArrowLeftIcon/> Back</Link></Button>
      <div className="flex items-center gap-2"><h1 className="text-2xl font-semibold">{t.name}</h1><TtsTemplateStatusBadge status={t.status}/></div>
      {t.description && <p className="text-muted-foreground">{t.description}</p>}
      <Card><CardHeader><CardTitle>Template</CardTitle></CardHeader><CardContent><pre className="whitespace-pre-wrap text-sm bg-muted p-3 rounded">{t.templateText}</pre></CardContent></Card>
      <Card><CardHeader><CardTitle>Variables ({t.variables?.length ?? 0})</CardTitle></CardHeader><CardContent>{!t.variables?.length ? <p className="text-sm text-muted-foreground">No variables declared.</p> : <ul className="text-sm space-y-1">{t.variables.map(v=><li key={v.name}><code>{v.name}</code> — {v.type ?? "STRING"} {v.required?"(required)":""}</li>)}</ul>}</CardContent></Card>
      <Card><CardHeader><CardTitle>Meta</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground"><div>Tenant: {t.tenantId}</div><div>Created: {formatDateTime(t.createdAt)}</div><div>Updated: {formatDateTime(t.updatedAt)}</div></CardContent></Card>
    </div>
  );
}
