"use client";
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangleIcon, PlusIcon, RefreshCwIcon, SearchXIcon, MicIcon } from "lucide-react";
import { PageHeader } from "@/components/layout/page-header";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { toApiError } from "@/lib/api/error";
import { TTS_TEMPLATE_SORTABLE_FIELDS, getTtsTemplates, ttsTemplatesKeys, deleteTtsTemplate, approveTtsTemplate, rejectTtsTemplate, type TtsTemplateListParams, type TtsTemplateSortField } from "@/lib/api/tts-templates";
import type { TtsTemplateResponse, TtsTemplateStatus } from "@/lib/api/contracts";
import { TtsTemplateTable } from "@/components/tts-templates/tts-template-table";
import { CreateTtsTemplateDialog } from "@/components/tts-templates/create-tts-template-dialog";
import { EditTtsTemplateDialog } from "@/components/tts-templates/edit-tts-template-dialog";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";
import { toast } from "sonner";

const LIST_CONFIG = { sortableFields: TTS_TEMPLATE_SORTABLE_FIELDS, defaultSort: { field:"createdAt", direction:"desc" as const }, pageSizes:[20,10,50] as const, basePath:"/tts-templates" as const };

export function TtsTemplatesView(){
  const qc = useQueryClient();
  const { state, patch } = useUrlListState(LIST_CONFIG as any);
  const debounced = useDebouncedValue(state.q,250);
  const [status,setStatus]=useState<TtsTemplateStatus|"">("");
  const params: TtsTemplateListParams = { page: state.page, size: state.size, sortField: state.sort.field as TtsTemplateSortField, sortDirection: state.sort.direction, status: status||undefined, search: debounced||undefined };
  const q = useQuery({ queryKey: ttsTemplatesKeys.list(params), queryFn: ()=>getTtsTemplates(params), placeholderData:p=>p });
  const [createOpen,setCreateOpen]=useState(false);
  const [editing,setEditing]=useState<TtsTemplateResponse|null>(null);
  const filtersActive=!!state.q||!!status;
  const clear=useCallback(()=>{ patch({q:""},true); setStatus(""); },[patch]);
  const apiError=q.error?toApiError(q.error):null;
  const pag=q.data?.pagination;
  async function doDelete(t:TtsTemplateResponse){ if(!confirm(`Soft-delete "${t.name}"? Campaign refs will fail afterwards.`)) return; try{ await deleteTtsTemplate(t.id); toast.success("Deleted"); await qc.invalidateQueries({queryKey: ttsTemplatesKeys.all}); }catch(e){ toast.error(toApiError(e).message); } }
  async function doApprove(t:TtsTemplateResponse){ try{ await approveTtsTemplate(t.id); toast.success("Approved"); await qc.invalidateQueries({queryKey: ttsTemplatesKeys.all}); }catch(e){ toast.error(toApiError(e).message); } }
  async function doReject(t:TtsTemplateResponse){ try{ await rejectTtsTemplate(t.id); toast.success("Rejected"); await qc.invalidateQueries({queryKey: ttsTemplatesKeys.all}); }catch(e){ toast.error(toApiError(e).message); } }
  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader title="TTS Templates" description="Text-to-speech templates with {{variable}} placeholders. Only APPROVED usable by campaigns."><Button onClick={()=>setCreateOpen(true)}><PlusIcon/>Create template</Button></PageHeader>
      <Card><CardHeader><CardTitle>TTS Templates</CardTitle><CardDescription>Pending → approved for campaign use. Edit of approved returns to pending.</CardDescription></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2 flex-wrap">
            <Input placeholder="Search name, template text…" value={state.q} onChange={e=>patch({q:e.target.value},true)} className="max-w-xs" />
            <Select value={status||"ALL"} onValueChange={v=>setStatus(v==="ALL"?"":v as TtsTemplateStatus)}><SelectTrigger className="w-40"><SelectValue/></SelectTrigger><SelectContent><SelectItem value="ALL">All statuses</SelectItem><SelectItem value="PENDING_APPROVAL">Pending</SelectItem><SelectItem value="APPROVED">Approved</SelectItem><SelectItem value="REJECTED">Rejected</SelectItem></SelectContent></Select>
            {filtersActive && <Button variant="ghost" onClick={clear}>Clear</Button>}
          </div>
          {apiError && <Alert variant="destructive"><AlertTriangleIcon/><AlertTitle>{apiError.status===403?"No permission":apiError.message}</AlertTitle><AlertDescription>{apiError.requestId?`Request ID ${apiError.requestId}`:null}</AlertDescription><AlertAction><Button variant="outline" size="sm" onClick={()=>q.refetch()}><RefreshCwIcon/> Retry</Button></AlertAction></Alert>}
          {q.isPending ? <TableSkeleton columns={4}/> : q.data && q.data.items.length===0 ? (
            <div className="flex flex-col items-center gap-2 py-12 text-center border-dashed border rounded-lg">{filtersActive?<SearchXIcon className="size-8 text-muted-foreground"/>:<MicIcon className="size-8 text-muted-foreground"/>}<p className="text-sm font-medium">{filtersActive?"No templates match filters":"No TTS templates yet"}</p>{filtersActive && <Button variant="outline" size="sm" onClick={clear}>Clear</Button>}</div>
          ) : q.data ? <><div className="overflow-auto"><TtsTemplateTable templates={q.data.items} sort={state.sort} onSortChange={s=>patch({sort:s}, false)} onEdit={setEditing} onDelete={doDelete} onApprove={doApprove} onReject={doReject} /></div>{pag && <TablePagination pagination={pag} pageSize={state.size} entityLabel="templates" onPageChange={p=>patch({page:p},false)} onPageSizeChange={s=>patch({size:s},true)} />}</>:null}
        </CardContent>
      </Card>
      <CreateTtsTemplateDialog open={createOpen} onOpenChange={setCreateOpen} />
      {editing && <EditTtsTemplateDialog template={editing} onOpenChange={o=>{ if(!o) setEditing(null);}} />}
    </div>
  );
}
