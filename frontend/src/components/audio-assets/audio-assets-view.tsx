"use client";
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useCallback, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangleIcon, PlusIcon, RefreshCwIcon, SearchXIcon, MusicIcon } from "lucide-react";
import { PageHeader } from "@/components/layout/page-header";
import { TableSkeleton } from "@/components/common/table-skeleton";
import { TablePagination } from "@/components/common/table-pagination";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { toApiError } from "@/lib/api/error";
import { AUDIO_ASSET_SORTABLE_FIELDS, getAudioAssets, audioAssetsKeys, deleteAudioAsset, approveAudioAsset, rejectAudioAsset, type AudioAssetListParams, type AudioAssetSortField } from "@/lib/api/audio-assets";
import type { AudioAssetResponse, AudioAssetStatus } from "@/lib/api/contracts";
import { AudioAssetTable } from "@/components/audio-assets/audio-asset-table";
import { CreateAudioAssetDialog } from "@/components/audio-assets/create-audio-asset-dialog";
import { EditAudioAssetDialog } from "@/components/audio-assets/edit-audio-asset-dialog";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useUrlListState } from "@/hooks/use-url-list-state";
import { toast } from "sonner";

const LIST_CONFIG = {
  sortableFields: AUDIO_ASSET_SORTABLE_FIELDS,
  defaultSort: { field: "createdAt", direction: "desc" as const },
  pageSizes: [20, 10, 50] as const,
  basePath: "/audio-assets" as const,
};

export function AudioAssetsView(){
  const qc = useQueryClient();
  const { state, patch } = useUrlListState(LIST_CONFIG as any);
  const debounced = useDebouncedValue(state.q, 250);
  const [status, setStatus] = useState<AudioAssetStatus | "">("");
  const params: AudioAssetListParams = { page: state.page, size: state.size, sortField: state.sort.field as AudioAssetSortField, sortDirection: state.sort.direction, status: status || undefined, search: debounced || undefined };
  const q = useQuery({ queryKey: audioAssetsKeys.list(params), queryFn: ()=> getAudioAssets(params), placeholderData: p=>p });
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<AudioAssetResponse|null>(null);
  const filtersActive = !!state.q || !!status;
  const clear = useCallback(()=>{ patch({ q:"" }, true); setStatus(""); },[patch]);
  const apiError = q.error ? toApiError(q.error) : null;
  const pag = q.data?.pagination;

  async function doDelete(a: AudioAssetResponse){
    if(!confirm(`Soft-delete "${a.name}"? Campaign references will fail activation afterwards.`)) return;
    try{ await deleteAudioAsset(a.id); toast.success("Audio asset deleted"); await qc.invalidateQueries({ queryKey: audioAssetsKeys.all }); }catch(e){ toast.error(toApiError(e).message); }
  }
  async function doApprove(a: AudioAssetResponse){
    try{ await approveAudioAsset(a.id); toast.success("Approved"); await qc.invalidateQueries({ queryKey: audioAssetsKeys.all }); }catch(e){ const ae=toApiError(e); toast.error(ae.message); }
  }
  async function doReject(a: AudioAssetResponse){
    try{ await rejectAudioAsset(a.id); toast.success("Rejected"); await qc.invalidateQueries({ queryKey: audioAssetsKeys.all }); }catch(e){ toast.error(toApiError(e).message); }
  }

  return (
    <div className="mx-auto w-full max-w-6xl">
      <PageHeader title="Audio Assets" description="Tenant-owned audio registry. Only APPROVED assets are usable by campaigns.">
        <Button onClick={()=>setCreateOpen(true)}><PlusIcon /> Register asset</Button>
      </PageHeader>
      <Card>
        <CardHeader><CardTitle>Audio Assets</CardTitle><CardDescription>Metadata-only registry — binary pipeline deferred.</CardDescription></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex gap-2 flex-wrap">
            <div className="relative flex-1 max-w-xs">
              <Input placeholder="Search name, file name…" value={state.q} onChange={e=>patch({ q:e.target.value }, true)} />
            </div>
            <Select value={status || "ALL"} onValueChange={v=> setStatus(v==="ALL" ? "" : v as AudioAssetStatus)}>
              <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">All statuses</SelectItem>
                <SelectItem value="PENDING_APPROVAL">Pending</SelectItem>
                <SelectItem value="APPROVED">Approved</SelectItem>
                <SelectItem value="REJECTED">Rejected</SelectItem>
              </SelectContent>
            </Select>
            {filtersActive && <Button variant="ghost" onClick={clear}>Clear</Button>}
          </div>
          {apiError && (
            <Alert variant="destructive"><AlertTriangleIcon /><AlertTitle>{apiError.status===403?"No permission":apiError.message}</AlertTitle><AlertDescription>{apiError.requestId?`Request ID ${apiError.requestId}`:null}</AlertDescription><AlertAction><Button variant="outline" size="sm" onClick={()=>q.refetch()}><RefreshCwIcon/> Retry</Button></AlertAction></Alert>
          )}
          {q.isPending ? <TableSkeleton columns={5} /> : q.data && q.data.items.length===0 ? (
            <div className="flex flex-col items-center gap-2 py-12 text-center border-dashed border rounded-lg">
              {filtersActive ? <SearchXIcon className="size-8 text-muted-foreground"/> : <MusicIcon className="size-8 text-muted-foreground"/>}
              <p className="text-sm font-medium">{filtersActive?"No assets match filters":"No audio assets yet"}</p>
              {filtersActive && <Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>}
            </div>
          ) : q.data ? <>
            <div className="overflow-auto"><AudioAssetTable assets={q.data.items} sort={state.sort} onSortChange={s=>patch({sort:s}, false)} onEdit={setEditing} onDelete={doDelete} onApprove={doApprove} onReject={doReject} /></div>
            {pag && <TablePagination pagination={pag} pageSize={state.size} entityLabel="assets" onPageChange={p=>patch({page:p}, false)} onPageSizeChange={s=>patch({size:s}, true)} />}
          </> : null}
        </CardContent>
      </Card>
      <CreateAudioAssetDialog open={createOpen} onOpenChange={setCreateOpen} />
      {editing && <EditAudioAssetDialog asset={editing} onOpenChange={o=>{ if(!o) setEditing(null);}} />}
    </div>
  );
}
